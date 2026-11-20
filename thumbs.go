package main

import (
	"image"
	"image/color"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
)

type thumbKey struct {
	key string
	dim int
}

var (
	thumbMu       sync.Mutex
	thumbInflight = map[thumbKey]chan struct{}{}
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
