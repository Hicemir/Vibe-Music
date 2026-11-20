package main

import (
	"context"
	"encoding/json"
	"image"
	"image/jpeg"
	_ "image/png"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/fsnotify/fsnotify"
)

var audioExts = map[string]bool{
	".mp3": true, ".m4a": true, ".opus": true, ".ogg": true, ".oga": true, ".flac": true,
	".wav": true, ".aac": true, ".wma": true, ".webm": true, ".mka": true, ".mp4": true, ".alac": true,
}

type Scanner struct {
	store     *Store
	emit      func(event string, data ...any)
	scanMu    sync.Mutex
	mu        sync.Mutex
	watcher   *fsnotify.Watcher
	timer     *time.Timer
	onChanged func() // called when scan added/updated/removed tracks
	onDone    func() // called after every full scan
}

func NewScanner(store *Store, emit func(string, ...any)) *Scanner {
	return &Scanner{store: store, emit: emit}
}

type probeOut struct {
	Format struct {
		Duration   string            `json:"duration"`
		FormatName string            `json:"format_name"`
		BitRate    string            `json:"bit_rate"`
		Tags       map[string]string `json:"tags"`
	} `json:"format"`
	Streams []struct {
		CodecType   string            `json:"codec_type"`
		CodecName   string            `json:"codec_name"`
		Disposition map[string]int    `json:"disposition"`
		Tags        map[string]string `json:"tags"`
	} `json:"streams"`
}

func probe(path string) (*probeOut, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", path)
	setCmdHidden(cmd)
	out, err := cmd.Output()
	if err != nil {
		return nil, err
	}
	var p probeOut
	if err := json.Unmarshal(out, &p); err != nil {
		return nil, err
	}
	return &p, nil
}

func lowerTags(m map[string]string) map[string]string {
	o := map[string]string{}
	for k, v := range m {
		o[strings.ToLower(k)] = v
	}
	return o
}

func (p *probeOut) tags() map[string]string {
	t := lowerTags(p.Format.Tags)
	for _, s := range p.Streams {
		if s.CodecType == "audio" {
			for k, v := range lowerTags(s.Tags) {
				if _, ok := t[k]; !ok {
					t[k] = v
				}
			}
		}
	}
	return t
}

var idSuffix = regexp.MustCompile(`\s*\[[A-Za-z0-9_-]{11}\]\s*$`)
var yearRe = regexp.MustCompile(`(\d{4})`)
var artistSplitRe = regexp.MustCompile(`(?i)\s*(?:,|;|/|\s&\s|\sfeat\.?\s|\sft\.?\s|\sfeaturing\s)\s*`)
var topicSuffixRe = regexp.MustCompile(`(?i)\s*-\s*topic\s*$`)

func splitArtistsGo(raw string) []string {
	parts := artistSplitRe.Split(raw, -1)
	seen := map[string]bool{}
	var out []string
	for _, p := range parts {
		p = topicSuffixRe.ReplaceAllString(strings.TrimSpace(p), "")
		p = strings.TrimSpace(p)
		if p == "" {
			continue
		}
		key := strings.ToLower(p)
		if !seen[key] {
			seen[key] = true
			out = append(out, p)
		}
	}
	return out
}

func cleanArtistString(raw string) string {
	list := splitArtistsGo(raw)
	if len(list) == 0 {
		return "Unknown Artist"
	}
	return strings.Join(list, ", ")
}

func fallbackTitle(path string) (artist, title string) {
	base := strings.TrimSuffix(filepath.Base(path), filepath.Ext(path))
	base = idSuffix.ReplaceAllString(base, "")
	if i := strings.Index(base, " - "); i > 0 {
		return strings.TrimSpace(base[:i]), strings.TrimSpace(base[i+3:])
	}
	return "", strings.TrimSpace(base)
}

// readTrack probes a file and derives its canonical key (yt:<id> or local:<hash>) without using its path.
func (s *Scanner) readTrack(path string, info fs.FileInfo, rootKey, rel string) (Track, bool, bool) {
	t := Track{Path: path, RootKey: rootKey, RelPath: rel, Size: info.Size(), MTime: info.ModTime().Unix(), AddedAt: info.ModTime().Unix()}
	t.Format = strings.TrimPrefix(strings.ToLower(filepath.Ext(path)), ".")
	p, err := probe(path)
	var tags map[string]string
	if err == nil {
		tags = p.tags()
	}
	if yt := firstNonEmpty(ytIDFromTags(tags), ytIDFromName(rel)); yt != "" {
		t.YtID, t.ID = yt, "yt:"+yt
	} else if h, herr := localContentID(path, info.Size()); herr == nil {
		t.ID = h
	} else {
		return t, false, false
	}
	if err != nil {
		fa, ft := fallbackTitle(path)
		t.Title = ft
		t.Artist = cleanArtistString(fa)
		return t, false, true
	}
	t.Title = strings.TrimSpace(tags["title"])
	t.Artist = strings.TrimSpace(tags["artist"])
	if t.Artist == "" {
		t.Artist = strings.TrimSpace(tags["album_artist"])
	}
	t.Album = strings.TrimSpace(tags["album"])
	fa, ft := fallbackTitle(path)
	if t.Title == "" {
		t.Title = ft
	}
	if t.Artist == "" {
		t.Artist = fa
	}
	t.Artist = cleanArtistString(t.Artist)
	if y := yearRe.FindString(firstNonEmpty(tags["date"], tags["year"])); y != "" {
		t.Year, _ = strconv.Atoi(y)
	}
	if gs := tagGenres(tags["genre"]); len(gs) > 0 {
		t.Genre, t.GenreSource = joinGenres(gs), "tag"
	}
	t.Duration, _ = strconv.ParseFloat(p.Format.Duration, 64)
	if br, err := strconv.Atoi(p.Format.BitRate); err == nil {
		t.Bitrate = br / 1000
	}
	hasVideo := false
	gotAudio := false
	for _, st := range p.Streams {
		if st.CodecType == "audio" && st.CodecName != "" && !gotAudio {
			t.Format = st.CodecName
			gotAudio = true
		} else if st.CodecType == "video" {
			hasVideo = true
		}
	}
	return t, hasVideo, true
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if s != "" {
			return s
		}
	}
	return ""
}

func coverPath(id string) string {
	return filepath.Join(coverDir(), coverKey(id)+".jpg")
}

func writeAtomicBytes(dst string, data []byte) bool {
	tmp, err := os.CreateTemp(filepath.Dir(dst), "cover-*.tmp")
	if err != nil {
		return false
	}
	tmpPath := tmp.Name()
	_, wErr := tmp.Write(data)
	cErr := tmp.Close()
	if wErr != nil || cErr != nil {
		_ = os.Remove(tmpPath)
		return false
	}
	if err := os.Rename(tmpPath, dst); err != nil {
		_ = os.Remove(tmpPath)
		return false
	}
	return true
}

func resizeSidecarGo(src, dst string, maxWidth int) bool {
	f, err := os.Open(src)
	if err != nil {
		return false
	}
	img, _, decErr := image.Decode(f)
	_ = f.Close()
	if decErr != nil || img == nil {
		return false
	}
	b := img.Bounds()
	w, h := b.Dx(), b.Dy()
	if w <= 0 || h <= 0 {
		return false
	}
	ext := strings.ToLower(filepath.Ext(src))
	if w <= maxWidth && (ext == ".jpg" || ext == ".jpeg") {
		if data, err := os.ReadFile(src); err == nil && len(data) > 0 {
			if writeAtomicBytes(dst, data) {
				return true
			}
		}
	}
	var outImg image.Image = img
	if w > maxWidth {
		nw := maxWidth
		nh := (h * maxWidth) / w
		if nh < 1 {
			nh = 1
		}
		outImg = downscaleCover(img, nw, nh)
	}
	tmp, err := os.CreateTemp(filepath.Dir(dst), "cover-*.tmp")
	if err != nil {
		return false
	}
	tmpPath := tmp.Name()
	encErr := jpeg.Encode(tmp, outImg, &jpeg.Options{Quality: 90})
	closeErr := tmp.Close()
	if encErr != nil || closeErr != nil {
		_ = os.Remove(tmpPath)
		return false
	}
	if err := os.Rename(tmpPath, dst); err != nil {
		_ = os.Remove(tmpPath)
		return false
	}
	return true
}

// extractCover tries embedded art (when a video/cover stream is present), then a sidecar image next to the file.
func extractCover(path string, id string, hasVideoStream bool) bool {
	out := coverPath(id)
	if hasVideoStream {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		cmd := exec.CommandContext(ctx, "ffmpeg", "-y", "-v", "error", "-i", path, "-an", "-map", "0:v:0", "-frames:v", "1",
			"-vf", "scale='min(800,iw)':-2", "-q:v", "2", out)
		setCmdHidden(cmd)
		err := cmd.Run()
		cancel()
		if err == nil {
			if st, err := os.Stat(out); err == nil && st.Size() > 0 {
				return true
			}
		}
	}
	base := strings.TrimSuffix(path, filepath.Ext(path))
	for _, ext := range []string{".jpg", ".jpeg", ".png", ".webp"} {
		side := base + ext
		if _, err := os.Stat(side); err == nil {
			if ext != ".webp" && resizeSidecarGo(side, out, 800) {
				return true
			}
			c2, cancel2 := context.WithTimeout(context.Background(), 20*time.Second)
			cmd2 := exec.CommandContext(c2, "ffmpeg", "-y", "-v", "error", "-i", side, "-frames:v", "1",
				"-vf", "scale='min(800,iw)':-2", "-q:v", "2", out)
			setCmdHidden(cmd2)
			err := cmd2.Run()
			cancel2()
			if err == nil {
				return true
			}
			if b, err := os.ReadFile(side); err == nil && len(b) > 0 {
				if writeAtomicBytes(out, b) {
					return true
				}
			}
		}
	}
	_ = os.Remove(out)
	return false
}

// Scan walks all configured dirs, adds/updates new files and removes vanished ones.
func (s *Scanner) Scan(dirs []string) {
	s.scanMu.Lock()
	defer s.scanMu.Unlock()

	s.emit("library:scan", map[string]any{"scanning": true, "done": 0, "total": 0})
	existing := map[string]Track{}
	if all, err := s.store.AllTracks(); err == nil {
		for _, t := range all {
			existing[t.Path] = t
		}
	}

	type job struct {
		path    string
		info    fs.FileInfo
		old     *Track
		rootKey string
		rel     string
	}
	var jobs []job
	seen := map[string]bool{}
	liveRoots := map[string]bool{}
	for _, d := range dirs {
		if st, err := os.Stat(d); err != nil || !st.IsDir() {
			continue
		}
		liveRoots[d] = true
		rootKey := s.store.EnsureRoot(d)
		_ = filepath.WalkDir(d, func(p string, de fs.DirEntry, err error) error {
			if err != nil || de.IsDir() {
				return nil
			}
			if !audioExts[strings.ToLower(filepath.Ext(p))] {
				return nil
			}
			info, err := de.Info()
			if err != nil {
				return nil
			}
			r, rerr := filepath.Rel(d, p)
			if rerr != nil {
				return nil
			}
			rel, okRel := cleanRelPath(filepath.ToSlash(r))
			if !okRel {
				return nil
			}
			seen[p] = true
			if old, ok := existing[p]; ok && old.MTime == info.ModTime().Unix() && old.Size == info.Size() &&
				(!old.HasCover || fileExists(coverPath(old.ID))) {
				return nil
			}
			j := job{path: p, info: info, rootKey: rootKey, rel: rel}
			if old, ok := existing[p]; ok {
				o := old
				j.old = &o
			}
			jobs = append(jobs, j)
			return nil
		})
	}

	// remove vanished files (only those under reachable roots)
	var gone []string
	for p, t := range existing {
		if seen[p] || p == "" {
			continue
		}
		for root := range liveRoots {
			if strings.HasPrefix(p, root+string(filepath.Separator)) {
				gone = append(gone, t.ID)
				_ = os.Remove(coverPath(t.ID))
				break
			}
		}
	}
	if len(gone) > 0 {
		_ = s.store.DeleteTracks(gone)
	}

	total := len(jobs)
	var done atomic.Int32
	ch := make(chan job)
	workers := runtime.NumCPU()
	if workers < 4 {
		workers = 4
	} else if workers > 12 {
		workers = 12
	}
	var wg sync.WaitGroup
	for i := 0; i < workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range ch {
				t, hasVideo, ok := s.readTrack(j.path, j.info, j.rootKey, j.rel)
				if ok {
					if j.old != nil {
						t.AddedAt = j.old.AddedAt
					}
					if j.old != nil || !fileExists(coverPath(t.ID)) {
						t.HasCover = extractCover(j.path, t.ID, hasVideo)
					} else {
						t.HasCover = true
					}
					_, _ = s.store.UpsertTrack(t)
				}
				n := int(done.Add(1))
				if n%10 == 0 || n == total {
					s.emit("library:scan", map[string]any{"scanning": true, "done": n, "total": total})
				}
			}
		}()
	}
	for _, j := range jobs {
		ch <- j
	}
	close(ch)
	wg.Wait()
	s.emit("library:scan", map[string]any{"scanning": false, "done": total, "total": total})
	if total > 0 || len(gone) > 0 {
		if s.onChanged != nil {
			s.onChanged()
		}
		s.emit("library:changed")
	}
	if s.onDone != nil {
		s.onDone()
	}
}

// IngestFile adds or refreshes a single file without walking any directory. dirs are the configured
// library roots; the file must live under one of them. Returns false if it could not be indexed.
func (s *Scanner) IngestFile(path string, dirs []string) bool {
	abs, err := filepath.Abs(path)
	if err != nil {
		return false
	}
	info, err := os.Stat(abs)
	if err != nil || info.IsDir() || !audioExts[strings.ToLower(filepath.Ext(abs))] {
		return false
	}
	best := ""
	for _, d := range dirs {
		d = filepath.Clean(d)
		if strings.HasPrefix(abs, d+string(filepath.Separator)) && len(d) > len(best) {
			best = d
		}
	}
	if best == "" {
		return false
	}
	rootKey := s.store.EnsureRoot(best)
	r, err := filepath.Rel(best, abs)
	if err != nil {
		return false
	}
	rel, ok := cleanRelPath(filepath.ToSlash(r))
	if !ok {
		return false
	}
	t, hasVideo, ok := s.readTrack(abs, info, rootKey, rel)
	if !ok {
		return false
	}
	if old, found := s.store.TrackByRel(rootKey, rel); found {
		t.AddedAt = old.AddedAt
	}
	t.HasCover = extractCover(abs, t.ID, hasVideo)
	_, err = s.store.UpsertTrack(t)
	return err == nil
}

func fileExists(p string) bool {
	_, err := os.Stat(p)
	return err == nil
}

// Watch (re)starts the filesystem watcher on the given dirs; changes trigger a debounced rescan.
func (s *Scanner) Watch(dirs []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.watcher != nil {
		_ = s.watcher.Close()
	}
	w, err := fsnotify.NewWatcher()
	if err != nil {
		return
	}
	s.watcher = w
	for _, d := range dirs {
		_ = filepath.WalkDir(d, func(p string, de fs.DirEntry, err error) error {
			if err == nil && de.IsDir() {
				_ = w.Add(p)
			}
			return nil
		})
	}
	go func() {
		for {
			select {
			case ev, ok := <-w.Events:
				if !ok {
					return
				}
				if ev.Has(fsnotify.Create) {
					if st, err := os.Stat(ev.Name); err == nil && st.IsDir() {
						_ = w.Add(ev.Name)
					}
				}
				if !ev.Has(fsnotify.Chmod) {
					ext := strings.ToLower(filepath.Ext(ev.Name))
					if audioExts[ext] || ext == "" {
						s.debounce(dirs)
					}
				}
			case _, ok := <-w.Errors:
				if !ok {
					return
				}
			}
		}
	}()
}

func (s *Scanner) debounce(dirs []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.timer != nil {
		s.timer.Stop()
	}
	s.timer = time.AfterFunc(3*time.Second, func() { s.Scan(dirs) })
}

// ReadLyrics returns raw lyrics text from a sidecar .lrc or embedded tag.
func ReadLyrics(path string) string {
	base := strings.TrimSuffix(path, filepath.Ext(path))
	for _, ext := range []string{".lrc", ".LRC"} {
		if b, err := os.ReadFile(base + ext); err == nil {
			return string(b)
		}
	}
	p, err := probe(path)
	if err != nil {
		return ""
	}
	tags := p.tags()
	for k, v := range tags {
		if strings.Contains(k, "lyrics") && strings.TrimSpace(v) != "" {
			return v
		}
	}
	return ""
}

var lrcRe = regexp.MustCompile(`\[(\d+):(\d+(?:\.\d+)?)\]`)

type LyricLine struct {
	Time float64 `json:"time"`
	Text string  `json:"text"`
}

type Lyrics struct {
	Synced bool        `json:"synced"`
	Lines  []LyricLine `json:"lines"`
}

func ParseLyrics(raw string) Lyrics {
	raw = strings.ReplaceAll(raw, "\r\n", "\n")
	var out Lyrics
	if lrcRe.MatchString(raw) {
		for _, line := range strings.Split(raw, "\n") {
			ms := lrcRe.FindAllStringSubmatch(line, -1)
			if len(ms) == 0 {
				continue
			}
			text := strings.TrimSpace(lrcRe.ReplaceAllString(line, ""))
			for _, m := range ms {
				mn, _ := strconv.Atoi(m[1])
				sc, _ := strconv.ParseFloat(m[2], 64)
				out.Lines = append(out.Lines, LyricLine{Time: float64(mn)*60 + sc, Text: text})
			}
		}
		if len(out.Lines) > 0 {
			out.Synced = true
			// sort by time (insertion sort, lists are small)
			for i := 1; i < len(out.Lines); i++ {
				for j := i; j > 0 && out.Lines[j].Time < out.Lines[j-1].Time; j-- {
					out.Lines[j], out.Lines[j-1] = out.Lines[j-1], out.Lines[j]
				}
			}
			return out
		}
	}
	for _, line := range strings.Split(strings.TrimSpace(raw), "\n") {
		out.Lines = append(out.Lines, LyricLine{Time: -1, Text: strings.TrimRight(line, " \t")})
	}
	if len(out.Lines) == 1 && out.Lines[0].Text == "" {
		out.Lines = nil
	}
	return out
}
