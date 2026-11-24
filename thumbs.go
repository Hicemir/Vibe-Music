package main

import (
	"context"
	"image"
	"image/color"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"io"
	"math"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

type thumbKey struct {
	key string
	dim int
}

var (
	thumbMu           sync.Mutex
	thumbInflight     = map[thumbKey]chan struct{}{}
	ytThumbHTTPClient = &http.Client{Timeout: 6 * time.Second}
)

func normalizeThumbSize(raw string) int {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return 0
	}
	n, err := strconv.Atoi(raw)
	if err != nil || n <= 0 {
		return 0
	}
	switch {
	case n <= 128:
		return 128
	case n <= 300:
		return 300
	case n <= 480:
		return 480
	default:
		return 0
	}
}

func downscaleCover(src image.Image, nw, nh int) *image.RGBA {
	b := src.Bounds()
	sw, sh := b.Dx(), b.Dy()
	dst := image.NewRGBA(image.Rect(0, 0, nw, nh))
	ycbcr, isYCbCr := src.(*image.YCbCr)

	scaleX := float64(sw) / float64(nw)
	scaleY := float64(sh) / float64(nh)

	for dy := 0; dy < nh; dy++ {
		fy0 := float64(dy) * scaleY
		fy1 := float64(dy+1) * scaleY
		iy0 := int(math.Floor(fy0))
		iy1 := int(math.Ceil(fy1))
		if iy1 > sh {
			iy1 = sh
		}
		if iy1 <= iy0 {
			iy1 = min(sh, iy0+1)
		}
		rowOff := dy * dst.Stride
		for dx := 0; dx < nw; dx++ {
			fx0 := float64(dx) * scaleX
			fx1 := float64(dx+1) * scaleX
			ix0 := int(math.Floor(fx0))
			ix1 := int(math.Ceil(fx1))
			if ix1 > sw {
				ix1 = sw
			}
			if ix1 <= ix0 {
				ix1 = min(sw, ix0+1)
			}

			var sumR, sumG, sumB, wSum float64
			for iy := iy0; iy < iy1; iy++ {
				wy := math.Min(fy1, float64(iy+1)) - math.Max(fy0, float64(iy))
				if wy <= 0 {
					continue
				}
				sy := b.Min.Y + iy
				for ix := ix0; ix < ix1; ix++ {
					wx := math.Min(fx1, float64(ix+1)) - math.Max(fx0, float64(ix))
					if wx <= 0 {
						continue
					}
					w := wx * wy
					sx := b.Min.X + ix
					if isYCbCr {
						yo := ycbcr.YOffset(sx, sy)
						co := ycbcr.COffset(sx, sy)
						r, g, bl := color.YCbCrToRGB(ycbcr.Y[yo], ycbcr.Cb[co], ycbcr.Cr[co])
						sumR += float64(r) * w
						sumG += float64(g) * w
						sumB += float64(bl) * w
					} else {
						r, g, bl, _ := src.At(sx, sy).RGBA()
						sumR += float64(r>>8) * w
						sumG += float64(g>>8) * w
						sumB += float64(bl>>8) * w
					}
					wSum += w
				}
			}
			if wSum <= 0 {
				wSum = 1
			}
			off := rowOff + dx*4
			dst.Pix[off] = uint8(math.Min(255, math.Max(0, math.Round(sumR/wSum))))
			dst.Pix[off+1] = uint8(math.Min(255, math.Max(0, math.Round(sumG/wSum))))
			dst.Pix[off+2] = uint8(math.Min(255, math.Max(0, math.Round(sumB/wSum))))
			dst.Pix[off+3] = 255
		}
	}
	return dst
}

// resizedCoverPath returns a disk-cached resized JPEG path for id capped to maxDim,
// falling back to the original cover path on decode/write errors or when already small enough.
func resizedCoverPath(id string, maxDim int) string {
	srcPath := coverPath(id)
	srcStat, err := os.Stat(srcPath)
	if err != nil {
		return ""
	}
	if maxDim <= 0 {
		return srcPath
	}

	ck := coverKey(id)
	dstDir := filepath.Join(coverDir(), "thumbs", "v2", strconv.Itoa(maxDim))
	dstPath := filepath.Join(dstDir, ck+".jpg")
	if dstStat, err := os.Stat(dstPath); err == nil && dstStat.Size() > 0 && !dstStat.ModTime().Before(srcStat.ModTime()) {
		return dstPath
	}

	tk := thumbKey{key: ck, dim: maxDim}
	for {
		thumbMu.Lock()
		waitCh, inFlight := thumbInflight[tk]
		if !inFlight {
			waitCh = make(chan struct{})
			thumbInflight[tk] = waitCh
			thumbMu.Unlock()
			break
		}
		thumbMu.Unlock()
		<-waitCh
		if dstStat, err := os.Stat(dstPath); err == nil && dstStat.Size() > 0 && !dstStat.ModTime().Before(srcStat.ModTime()) {
			return dstPath
		}
		// If the winner skipped writing (e.g. image was already <= maxDim), return srcPath instead of retrying endlessly.
		return srcPath
	}
	defer func() {
		thumbMu.Lock()
		if ch := thumbInflight[tk]; ch != nil {
			delete(thumbInflight, tk)
			close(ch)
		}
		thumbMu.Unlock()
	}()

	if dstStat, err := os.Stat(dstPath); err == nil && dstStat.Size() > 0 && !dstStat.ModTime().Before(srcStat.ModTime()) {
		return dstPath
	}

	f, err := os.Open(srcPath)
	if err != nil {
		return srcPath
	}
	img, _, decErr := image.Decode(f)
	_ = f.Close()
	if decErr != nil || img == nil {
		return srcPath
	}

	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	if w <= 0 || h <= 0 || (w <= maxDim && h <= maxDim) {
		return srcPath
	}

	var nw, nh int
	if w >= h {
		nw = maxDim
		nh = (h * maxDim) / w
	} else {
		nh = maxDim
		nw = (w * maxDim) / h
	}
	if nw < 1 {
		nw = 1
	}
	if nh < 1 {
		nh = 1
	}

	resized := downscaleCover(img, nw, nh)
	if err := os.MkdirAll(dstDir, 0o755); err != nil {
		return srcPath
	}
	tmp, err := os.CreateTemp(dstDir, ck+"-*.tmp")
	if err != nil {
		return srcPath
	}
	tmpPath := tmp.Name()
	encErr := jpeg.Encode(tmp, resized, &jpeg.Options{Quality: 92})
	closeErr := tmp.Close()
	if encErr != nil || closeErr != nil {
		_ = os.Remove(tmpPath)
		return srcPath
	}
	if err := os.Rename(tmpPath, dstPath); err != nil {
		_ = os.Remove(tmpPath)
		return srcPath
	}
	return dstPath
}

type subImager interface {
	SubImage(r image.Rectangle) image.Image
}

func cropSquareCover(src image.Image) image.Image {
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	if w <= 0 || h <= 0 || w == h {
		return src
	}
	side := min(w, h)
	// Standard 4:3 YouTube thumbnails (480x360 hqdefault, 640x480 sddefault) have top/bottom black bars around a 16:9 frame.
	if w == 480 && h == 360 {
		side = 270
	} else if w == 640 && h == 480 {
		side = 360
	}
	x0 := b.Min.X + (w-side)/2
	y0 := b.Min.Y + (h-side)/2
	rect := image.Rect(x0, y0, x0+side, y0+side)
	if si, ok := src.(subImager); ok {
		return si.SubImage(rect)
	}
	return src
}

func ytThumbCandidateURLs(videoID, rawURL string) []string {
	var out []string
	if rawURL != "" {
		if u, err := url.Parse(rawURL); err == nil && (u.Scheme == "http" || u.Scheme == "https") {
			host := strings.ToLower(u.Hostname())
			if strings.Contains(host, "googleusercontent.com") || strings.Contains(host, "ggpht.com") {
				cand := strings.TrimSpace(rawURL)
				if googleThumbParamRe.MatchString(cand) {
					cand = googleThumbParamRe.ReplaceAllString(cand, "=w480-h480-l90-rj")
				} else if idx := strings.LastIndex(cand, "="); idx > strings.LastIndex(cand, "/") {
					cand = cand[:idx] + "=w480-h480-l90-rj"
				}
				out = append(out, cand)
			} else if host == "127.0.0.1" || host == "localhost" {
				out = append(out, rawURL)
			}
		}
	}
	out = append(out,
		"https://i.ytimg.com/vi/"+videoID+"/maxresdefault.jpg",
		"https://i.ytimg.com/vi/"+videoID+"/sddefault.jpg",
		"https://i.ytimg.com/vi/"+videoID+"/hqdefault.jpg",
	)
	return out
}

// cachedYtThumbPath returns a disk-cached 1:1 square JPEG thumbnail path (<=480x480) for a YouTube videoID.
func cachedYtThumbPath(videoID, rawURL string) string {
	if !ytIDRe.MatchString(videoID) {
		return ""
	}

	dstDir := filepath.Join(coverDir(), "thumbs", "v3", "yt")
	dstPath := filepath.Join(dstDir, videoID+".jpg")
	if st, err := os.Stat(dstPath); err == nil && st.Size() > 0 {
		return dstPath
	}

	tk := thumbKey{key: "yt_" + videoID, dim: 480}
	for {
		thumbMu.Lock()
		waitCh, inFlight := thumbInflight[tk]
		if !inFlight {
			waitCh = make(chan struct{})
			thumbInflight[tk] = waitCh
			thumbMu.Unlock()
			break
		}
		thumbMu.Unlock()
		<-waitCh
		if st, err := os.Stat(dstPath); err == nil && st.Size() > 0 {
			return dstPath
		}
		return ""
	}
	defer func() {
		thumbMu.Lock()
		if ch := thumbInflight[tk]; ch != nil {
			delete(thumbInflight, tk)
			close(ch)
		}
		thumbMu.Unlock()
	}()

	if st, err := os.Stat(dstPath); err == nil && st.Size() > 0 {
		return dstPath
	}

	var decoded image.Image
	for _, candidate := range ytThumbCandidateURLs(videoID, rawURL) {
		req, err := http.NewRequest(http.MethodGet, candidate, nil)
		if err != nil {
			continue
		}
		req.Header.Set("User-Agent", "Mozilla/5.0")
		req.Header.Set("Accept", "image/jpeg,image/png,image/*;q=0.8")
		resp, err := ytThumbHTTPClient.Do(req)
		if err != nil {
			continue
		}
		var img image.Image
		var decErr error
		if resp.StatusCode == http.StatusOK {
			img, _, decErr = image.Decode(io.LimitReader(resp.Body, 4<<20))
		}
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		_ = resp.Body.Close()
		if decErr == nil && img != nil {
			decoded = img
			break
		}
	}
	if decoded == nil {
		return ""
	}

	cropped := cropSquareCover(decoded)
	cb := cropped.Bounds()
	cw, ch := cb.Dx(), cb.Dy()
	if cw <= 0 || ch <= 0 {
		return ""
	}
	var outImg image.Image = cropped
	if cw != ch || cw > 480 || ch > 480 {
		target := min(480, min(cw, ch))
		outImg = downscaleCover(cropped, target, target)
	}

	if err := os.MkdirAll(dstDir, 0o755); err != nil {
		return ""
	}
	tmp, err := os.CreateTemp(dstDir, videoID+"-*.tmp")
	if err != nil {
		return ""
	}
	tmpPath := tmp.Name()
	encErr := jpeg.Encode(tmp, outImg, &jpeg.Options{Quality: 92})
	closeErr := tmp.Close()
	if encErr != nil || closeErr != nil {
		_ = os.Remove(tmpPath)
		return ""
	}
	if err := os.Rename(tmpPath, dstPath); err != nil {
		_ = os.Remove(tmpPath)
		return ""
	}
	return dstPath
}

// prewarmYtThumbs downloads and caches square JPEG thumbnails for the first maxItems discover entries.
func prewarmYtThumbs(ctx context.Context, items []DiscoverItem, maxItems int) {
	if len(items) == 0 || maxItems <= 0 || ctx.Err() != nil {
		return
	}
	n := min(len(items), maxItems)
	sem := make(chan struct{}, 4)
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		it := items[i]
		if it.VideoID == "" {
			continue
		}
		wg.Add(1)
		go func(item DiscoverItem) {
			defer wg.Done()
			select {
			case sem <- struct{}{}:
			case <-ctx.Done():
				return
			}
			defer func() { <-sem }()
			if ctx.Err() != nil {
				return
			}
			_ = cachedYtThumbPath(item.VideoID, item.ThumbURL)
		}(it)
	}
	wg.Wait()
}
