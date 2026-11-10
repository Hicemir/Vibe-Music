package main

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math/rand"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	fileVidIDRe    = regexp.MustCompile(`\[([A-Za-z0-9_-]{11})\][^/\\]*$`)
	archiveSkipRe  = regexp.MustCompile(`\[download\]\s+([A-Za-z0-9_-]{11}):\s+has already been recorded in the archive`)
	alreadyDlRe    = regexp.MustCompile(`\[download\]\s+(.+?)\s+has already been downloaded`)
)

func extractURLVideoID(raw string) string {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return ""
	}
	if v := u.Query().Get("v"); len(v) == 11 {
		return v
	}
	host := strings.ToLower(u.Host)
	if strings.Contains(host, "youtu.be") {
		seg := strings.Trim(u.Path, "/")
		if i := strings.IndexByte(seg, '/'); i >= 0 {
			seg = seg[:i]
		}
		if len(seg) == 11 {
			return seg
		}
	}
	return ""
}

// existingVideoIDMap returns a map of 11-char YouTube video IDs -> file path for all tracks currently in the library.
func (d *Downloader) existingVideoIDMap() map[string]string {
	out := make(map[string]string)
	d.app.trMu.RLock()
	defer d.app.trMu.RUnlock()
	for _, t := range d.app.tracks {
		if m := fileVidIDRe.FindStringSubmatch(t.Path); len(m) == 2 {
			if _, err := os.Stat(t.Path); err == nil {
				out[m[1]] = t.Path
			}
		}
	}
	return out
}

// ---------- yt-dlp binary management ----------

type YtdlpStatus struct {
	State   string `json:"state"` // idle | updating | ok | error
	Version string `json:"version"`
	Path    string `json:"path"`
	Message string `json:"message"`
}

type Ytdlp struct {
	mu     sync.Mutex
	status YtdlpStatus
	emit   func(string, ...any)
	jsFlag bool
}

func NewYtdlp(emit func(string, ...any)) *Ytdlp {
	y := &Ytdlp{emit: emit}
	y.status = YtdlpStatus{State: "idle"}
	return y
}

func (y *Ytdlp) ownPath() string {
	if runtime.GOOS == "windows" {
		return filepath.Join(binDir(), "yt-dlp.exe")
	}
	return filepath.Join(binDir(), "yt-dlp")
}

// Path returns the managed yt-dlp binary if present, else the system one.
func (y *Ytdlp) Path() string {
	if st, err := os.Stat(y.ownPath()); err == nil && st.Size() > 1_000_000 {
		return y.ownPath()
	}
	if p, err := exec.LookPath("yt-dlp"); err == nil {
		return p
	}
	return ""
}

func (y *Ytdlp) Status() YtdlpStatus {
	y.mu.Lock()
	defer y.mu.Unlock()
	return y.status
}

func (y *Ytdlp) set(s YtdlpStatus) {
	y.mu.Lock()
	y.status = s
	y.mu.Unlock()
	y.emit("ytdlp:status", s)
}

func (y *Ytdlp) version(path string) string {
	cmd := exec.Command(path, "--version")
	setCmdHidden(cmd)
	out, err := cmd.Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// EnsureUpToDate downloads yt-dlp if we have no managed copy, otherwise self-updates it.
func (y *Ytdlp) EnsureUpToDate() {
	own := y.ownPath()
	y.set(YtdlpStatus{State: "updating", Path: y.Path(), Message: "Checking for yt-dlp updates…"})
	_, err := os.Stat(own)
	if err != nil {
		if err := y.download(own); err != nil {
			p := y.Path()
			y.set(YtdlpStatus{State: "error", Path: p, Version: y.version(orEmpty(p)), Message: "Update failed: " + err.Error()})
			return
		}
	} else {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		cmd := exec.CommandContext(ctx, own, "-U")
		setCmdHidden(cmd)
		out, err := cmd.CombinedOutput()
		if err != nil {
			p := y.Path()
			y.set(YtdlpStatus{State: "error", Path: p, Version: y.version(orEmpty(p)),
				Message: "Update failed: " + lastLine(string(out))})
			return
		}
	}
	p := y.Path()
	y.refreshFlags(p)
	y.set(YtdlpStatus{State: "ok", Path: p, Version: y.version(orEmpty(p)), Message: "yt-dlp is up to date"})
}

func orEmpty(p string) string {
	if p == "" {
		return "/nonexistent"
	}
	return p
}

func lastLine(s string) string {
	s = strings.TrimSpace(s)
	if i := strings.LastIndex(s, "\n"); i >= 0 {
		return strings.TrimSpace(s[i+1:])
	}
	return s
}

func (y *Ytdlp) download(dst string) error {
	url := "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp_linux"
	if runtime.GOOS == "windows" {
		url = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe"
	}
	client := &http.Client{Timeout: 5 * time.Minute}
	resp, err := client.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	tmp := dst + ".part"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, resp.Body); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	f.Close()
	return os.Rename(tmp, dst)
}

func (y *Ytdlp) refreshFlags(path string) {
	if path == "" {
		return
	}
	cmd := exec.Command(path, "--help")
	setCmdHidden(cmd)
	out, _ := cmd.Output()
	y.mu.Lock()
	y.jsFlag = strings.Contains(string(out), "--js-runtimes")
	y.mu.Unlock()
}

// ---------- download jobs ----------

type DownloadJob struct {
	ID         int64   `json:"id"`
	URL        string  `json:"url"`
	Mode       string  `json:"mode"` // track | playlist
	Status     string  `json:"status"`
	Title      string  `json:"title"`
	Playlist   string  `json:"playlist"`
	Percent    float64 `json:"percent"`
	Speed      string  `json:"speed"`
	Index      int     `json:"index"`
	Total      int     `json:"total"`
	Done       int     `json:"done"`
	Error      string  `json:"error"`
	StartedAt  int64   `json:"startedAt"`

	cancel           context.CancelFunc
	files            []string
	targetPlaylistID int64
}

type Downloader struct {
	app    *App
	mu     sync.Mutex
	jobs   []*DownloadJob
	nextID int64
	queue  chan *DownloadJob
}

func NewDownloader(app *App) *Downloader {
	d := &Downloader{app: app, queue: make(chan *DownloadJob, 256)}
	go d.worker()
	return d
}

func (d *Downloader) List() []DownloadJob {
	d.mu.Lock()
	defer d.mu.Unlock()
	out := make([]DownloadJob, 0, len(d.jobs))
	for _, j := range d.jobs {
		out = append(out, *j)
	}
	return out
}

func (d *Downloader) push(j *DownloadJob) {
	d.mu.Lock()
	cp := *j
	d.mu.Unlock()
	d.app.emit("download:update", cp)
}

func (d *Downloader) Add(url, mode string) (int64, error) {
	return d.addInternal(url, mode, 0)
}

func (d *Downloader) AddToPlaylist(url string, playlistID int64) (int64, error) {
	return d.addInternal(url, "track", playlistID)
}

func (d *Downloader) addInternal(url, mode string, targetPlaylistID int64) (int64, error) {
	url = strings.TrimSpace(url)
	if !strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://") {
		return 0, fmt.Errorf("please paste a valid URL (starting with http/https)")
	}
	if mode != "playlist" {
		mode = "track"
	}
	if d.app.ytdlp.Path() == "" {
		return 0, fmt.Errorf("yt-dlp not found yet – wait for the updater or install yt-dlp")
	}
	d.mu.Lock()
	d.nextID++
	j := &DownloadJob{
		ID:               d.nextID,
		URL:              url,
		Mode:             mode,
		Status:           "queued",
		Title:            url,
		StartedAt:        time.Now().Unix(),
		targetPlaylistID: targetPlaylistID,
	}
	d.jobs = append(d.jobs, j)
	d.mu.Unlock()
	d.push(j)
	d.queue <- j
	return j.ID, nil
}

func (d *Downloader) Cancel(id int64) {
	d.mu.Lock()
	var j *DownloadJob
	for _, x := range d.jobs {
		if x.ID == id {
			j = x
		}
	}
	if j == nil {
		d.mu.Unlock()
		return
	}
	if j.Status == "queued" {
		j.Status = "cancelled"
	} else if j.Status == "running" && j.cancel != nil {
		j.cancel()
	}
	d.mu.Unlock()
	d.push(j)
}

func (d *Downloader) ClearFinished() {
	d.mu.Lock()
	var keep []*DownloadJob
	for _, j := range d.jobs {
		if j.Status == "queued" || j.Status == "running" {
			keep = append(keep, j)
		}
	}
	d.jobs = keep
	d.mu.Unlock()
	d.app.emit("download:cleared")
}

func (d *Downloader) worker() {
	for j := range d.queue {
		d.mu.Lock()
		if j.Status == "cancelled" {
			d.mu.Unlock()
			continue
		}
		j.Status = "running"
		ctx, cancel := context.WithCancel(context.Background())
		j.cancel = cancel
		d.mu.Unlock()
		d.push(j)
		d.run(ctx, j)
		cancel()
	}
}

func formatArgs(format string) []string {
	switch format {
	case "opus":
		return []string{"-f", "bestaudio", "-x", "--audio-format", "opus"}
	case "mp3":
		return []string{"-f", "bestaudio", "-x", "--audio-format", "mp3", "--audio-quality", "0"}
	case "best":
		return []string{"-f", "bestaudio/best", "-x", "--audio-format", "best"}
	default: // m4a
		return []string{"-f", "bestaudio[ext=m4a]/bestaudio", "-x", "--audio-format", "m4a"}
	}
}

func (d *Downloader) run(ctx context.Context, j *DownloadJob) {
	set := d.app.Settings()
	_ = os.MkdirAll(set.DownloadDir, 0o755)
	path := d.app.ytdlp.Path()

	existingByID := d.existingVideoIDMap()

	// Fast path for single-track downloads if the video ID is already in the library
	if j.Mode == "track" {
		if vid := extractURLVideoID(j.URL); vid != "" {
			if existingPath, ok := existingByID[vid]; ok {
				d.mu.Lock()
				j.Title = strings.TrimSuffix(filepath.Base(existingPath), filepath.Ext(existingPath))
				j.Done = 1
				j.files = []string{existingPath}
				d.mu.Unlock()
				if j.targetPlaylistID > 0 {
					if t, ok := d.app.store.TrackByPath(existingPath); ok {
						_, _ = d.app.store.AddToPlaylist(j.targetPlaylistID, []int64{t.ID})
						d.app.emit("playlists:changed")
					}
				}
				d.finish(j, "done", "Already in your library — skipped download")
				return
			}
		}
	}

	// Build temporary archive file from existing local tracks so yt-dlp skips redownloading them
	archiveFile := filepath.Join(dataDir(), fmt.Sprintf("dl-archive-%d.txt", j.ID))
	var archBuf strings.Builder
	for vid := range existingByID {
		archBuf.WriteString("youtube ")
		archBuf.WriteString(vid)
		archBuf.WriteByte('\n')
		archBuf.WriteString("youtubetab ")
		archBuf.WriteString(vid)
		archBuf.WriteByte('\n')
	}
	_ = os.WriteFile(archiveFile, []byte(archBuf.String()), 0o644)
	defer os.Remove(archiveFile)

	args := []string{"--newline", "--no-colors", "--ignore-errors", "--no-overwrites",
		"--download-archive", archiveFile,
		"--embed-metadata", "--embed-thumbnail",
		"--convert-thumbnails", "jpg", "--no-warnings"}
	if j.Mode == "playlist" {
		args = append(args, "--yes-playlist")
	} else {
		args = append(args, "--no-playlist")
	}
	if set.CookieBrowser != "" {
		args = append(args, "--cookies-from-browser", set.CookieBrowser)
	}
	args = append(args, formatArgs(set.DownloadFormat)...)
	if _, err := exec.LookPath("node"); err == nil {
		d.app.ytdlp.mu.Lock()
		ok := d.app.ytdlp.jsFlag
		d.app.ytdlp.mu.Unlock()
		if ok {
			args = append(args, "--js-runtimes", "node")
		}
	}
	args = append(args,
		"-o", filepath.Join(set.DownloadDir, "%(title)s [%(id)s].%(ext)s"),
		"--progress-template", "download:VMPROG|%(progress._percent_str)s|%(progress._speed_str)s|%(info.playlist_index)s|%(info.n_entries)s|%(info.title)s",
		"--print", "before_dl:VMPL|%(playlist_title)s",
		"--print", "after_move:VMFILE|%(filepath)s|%(playlist_title)s",
		j.URL)

	cmd := exec.CommandContext(ctx, path, args...)
	setCmdHidden(cmd)
	stdout, _ := cmd.StdoutPipe()
	stderr, _ := cmd.StderrPipe()
	if err := cmd.Start(); err != nil {
		d.finish(j, "error", err.Error())
		return
	}
	var errLines []string
	var emu sync.Mutex
	go func() {
		sc := bufio.NewScanner(stderr)
		sc.Buffer(make([]byte, 1<<20), 1<<20)
		for sc.Scan() {
			emu.Lock()
			errLines = append(errLines, sc.Text())
			emu.Unlock()
		}
	}()

	sc := bufio.NewScanner(stdout)
	sc.Buffer(make([]byte, 1<<20), 1<<20)
	var plName string
	skippedExisting := 0
	seenFiles := map[string]bool{}
	addFile := func(fp string) {
		if fp == "" || seenFiles[fp] {
			return
		}
		seenFiles[fp] = true
		j.files = append(j.files, fp)
	}

	lastEmit := time.Time{}
	for sc.Scan() {
		line := sc.Text()
		switch {
		case strings.HasPrefix(line, "VMPL|"):
			name := strings.TrimSpace(strings.TrimPrefix(line, "VMPL|"))
			if name != "" && name != "NA" {
				plName = name
			}
		case strings.HasPrefix(line, "VMPROG|"):
			parts := strings.SplitN(line, "|", 6)
			if len(parts) < 6 {
				continue
			}
			pct, _ := strconv.ParseFloat(strings.TrimSuffix(strings.TrimSpace(parts[1]), "%"), 64)
			idx, _ := strconv.Atoi(strings.TrimSpace(parts[3]))
			tot, _ := strconv.Atoi(strings.TrimSpace(parts[4]))
			d.mu.Lock()
			j.Percent = pct
			j.Speed = strings.TrimSpace(parts[2])
			j.Index, j.Total = idx, tot
			j.Title = strings.TrimSpace(parts[5])
			d.mu.Unlock()
			if time.Since(lastEmit) > 200*time.Millisecond {
				lastEmit = time.Now()
				d.push(j)
			}
		case strings.HasPrefix(line, "VMFILE|"):
			parts := strings.SplitN(line, "|", 3)
			if len(parts) < 2 {
				continue
			}
			d.mu.Lock()
			addFile(parts[1])
			j.Done++
			j.Percent = 0
			if len(parts) == 3 && parts[2] != "NA" && parts[2] != "" {
				plName = parts[2]
			}
			d.mu.Unlock()
			d.push(j)
		default:
			if m := archiveSkipRe.FindStringSubmatch(line); len(m) == 2 {
				if existingPath, ok := existingByID[m[1]]; ok {
					d.mu.Lock()
					addFile(existingPath)
					skippedExisting++
					j.Title = strings.TrimSuffix(filepath.Base(existingPath), filepath.Ext(existingPath))
					d.mu.Unlock()
					d.push(j)
				}
			} else if m := alreadyDlRe.FindStringSubmatch(line); len(m) == 2 {
				fp := strings.TrimSpace(m[1])
				if _, err := os.Stat(fp); err == nil {
					d.mu.Lock()
					addFile(fp)
					skippedExisting++
					d.mu.Unlock()
					d.push(j)
				}
			}
		}
	}
	err := cmd.Wait()
	emu.Lock()
	errText := lastLine(strings.Join(errLines, "\n"))
	emu.Unlock()

	if ctx.Err() != nil {
		d.finish(j, "cancelled", "")
		return
	}
	d.mu.Lock()
	got := len(j.files)
	files := append([]string(nil), j.files...)
	d.mu.Unlock()
	if got == 0 {
		if errText == "" && err != nil {
			errText = err.Error()
		}
		if errText == "" {
			errText = "nothing was downloaded (already exists?)"
		}
		d.finish(j, "error", errText)
		return
	}

	// Index the files and refresh library, then group into a playlist when requested
	d.app.scanner.Scan(d.app.scanDirs())
	d.app.reloadTracks()
	d.app.emit("library:changed")
	if strings.Contains(j.URL, "list=LM") {
		d.app.likeFiles(files)
	}
	if j.targetPlaylistID > 0 {
		var ids []int64
		for _, f := range files {
			if t, ok := d.app.store.TrackByPath(f); ok {
				ids = append(ids, t.ID)
			}
		}
		if len(ids) > 0 {
			_, _ = d.app.store.AddToPlaylist(j.targetPlaylistID, ids)
			d.app.emit("playlists:changed")
		}
	}
	if j.Mode == "playlist" {
		if plName == "" {
			if strings.Contains(j.URL, "list=LM") {
				plName = "YouTube Liked Music"
			} else {
				plName = "Downloaded Playlist"
			}
		}
		d.app.addFilesToPlaylist(plName, files)
		d.mu.Lock()
		j.Playlist = plName
		d.mu.Unlock()
	}
	msg := ""
	if skippedExisting > 0 && j.Done == 0 {
		msg = fmt.Sprintf("All %d track(s) already in library — skipped redownload", skippedExisting)
	} else if skippedExisting > 0 {
		msg = fmt.Sprintf("Skipped %d already-downloaded track(s)", skippedExisting)
	} else if err != nil && errText != "" {
		msg = "Finished with some errors: " + errText
	}
	d.finish(j, "done", msg)
}

func (d *Downloader) finish(j *DownloadJob, status, msg string) {
	d.mu.Lock()
	j.Status = status
	j.Error = msg
	if status == "done" {
		j.Percent = 100
	}
	done := j.Done
	totalFiles := len(j.files)
	d.mu.Unlock()
	d.push(j)
	if status == "done" {
		if done == 0 && totalFiles > 0 {
			d.app.emit("app:toast", fmt.Sprintf("Skipped %d existing track(s) (already in library)", totalFiles))
		} else {
			d.app.emit("app:toast", fmt.Sprintf("Downloaded %d track(s)", done))
		}
	}
}

// ---------- Online Discovery (YouTube Music Radio via yt-dlp) ----------

type DiscoverItem struct {
	VideoID  string  `json:"videoId"`
	Title    string  `json:"title"`
	Artist   string  `json:"artist"`
	Duration float64 `json:"duration"`
	ThumbURL string  `json:"thumbUrl"`
	URL      string  `json:"url"`
}

type ytFlatPlaylist struct {
	Entries []struct {
		ID       string  `json:"id"`
		Title    string  `json:"title"`
		Uploader string  `json:"uploader"`
		Channel  string  `json:"channel"`
		Duration float64 `json:"duration"`
	} `json:"entries"`
}

func (d *Downloader) DiscoverOnline(seed int) []DiscoverItem {
	path := d.app.ytdlp.Path()
	if path == "" {
		return []DiscoverItem{}
	}
	existingByID := d.existingVideoIDMap()
	localTitles := map[string]bool{}

	type seedCandidate struct {
		vid    string
		artist string
	}
	byArtist := map[string][]seedCandidate{}
	d.app.trMu.RLock()
	for _, t := range d.app.tracks {
		localTitles[strings.ToLower(strings.TrimSpace(t.Title))] = true
		if m := fileVidIDRe.FindStringSubmatch(t.Path); len(m) == 2 {
			artists := splitArtistsGo(t.Artist)
			primary := "unknown"
			if len(artists) > 0 {
				primary = strings.ToLower(strings.TrimSpace(artists[0]))
			}
			byArtist[primary] = append(byArtist[primary], seedCandidate{vid: m[1], artist: primary})
		}
	}
	d.app.trMu.RUnlock()

	if len(byArtist) == 0 {
		return []DiscoverItem{}
	}

	rng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(seed)*99991))
	artistKeys := make([]string, 0, len(byArtist))
	for k := range byArtist {
		artistKeys = append(artistKeys, k)
	}
	rng.Shuffle(len(artistKeys), func(i, j int) {
		artistKeys[i], artistKeys[j] = artistKeys[j], artistKeys[i]
	})

	// Pick up to 4 distinct artists so every refresh mixes different genres/artists
	var chosen []string
	seenSeed := map[string]bool{}
	for _, ak := range artistKeys {
		list := byArtist[ak]
		pick := list[rng.Intn(len(list))].vid
		if !seenSeed[pick] {
			seenSeed[pick] = true
			chosen = append(chosen, pick)
		}
		if len(chosen) >= 4 {
			break
		}
	}
	// If the library has fewer than 4 distinct artists, top up with additional tracks
	if len(chosen) < 4 {
		for _, ak := range artistKeys {
			for _, c := range byArtist[ak] {
				if !seenSeed[c.vid] {
					seenSeed[c.vid] = true
					chosen = append(chosen, c.vid)
					if len(chosen) >= 4 {
						break
					}
				}
			}
			if len(chosen) >= 4 {
				break
			}
		}
	}

	set := d.app.Settings()
	perSeed := make([][]DiscoverItem, len(chosen))
	var wg sync.WaitGroup

	for idx, vid := range chosen {
		wg.Add(1)
		go func(i int, v string) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 18*time.Second)
			defer cancel()
			mixURL := fmt.Sprintf("https://www.youtube.com/watch?v=%s&list=RD%s", v, v)
			args := []string{"--flat-playlist", "-J", "--playlist-end", "22", "--no-warnings"}
			if set.CookieBrowser != "" {
				args = append(args, "--cookies-from-browser", set.CookieBrowser)
			}
			args = append(args, mixURL)
			cmd := exec.CommandContext(ctx, path, args...)
			setCmdHidden(cmd)
			raw, err := cmd.Output()
			if err != nil || len(raw) == 0 {
				return
			}
			var pl ytFlatPlaylist
			if json.Unmarshal(raw, &pl) != nil || len(pl.Entries) == 0 {
				return
			}
			localRng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(i)*7919))
			entries := append([]struct {
				ID       string  `json:"id"`
				Title    string  `json:"title"`
				Uploader string  `json:"uploader"`
				Channel  string  `json:"channel"`
				Duration float64 `json:"duration"`
			}(nil), pl.Entries...)
			localRng.Shuffle(len(entries), func(a, b int) {
				entries[a], entries[b] = entries[b], entries[a]
			})

			var items []DiscoverItem
			for _, e := range entries {
				if len(e.ID) != 11 {
					continue
				}
				if _, owned := existingByID[e.ID]; owned {
					continue
				}
				title := strings.TrimSpace(e.Title)
				if title == "" || title == "[Deleted video]" || title == "[Private video]" {
					continue
				}
				if localTitles[strings.ToLower(title)] {
					continue
				}
				artist := strings.TrimSpace(e.Channel)
				if artist == "" {
					artist = strings.TrimSpace(e.Uploader)
				}
				artist = topicSuffixRe.ReplaceAllString(artist, "")
				items = append(items, DiscoverItem{
					VideoID:  e.ID,
					Title:    title,
					Artist:   artist,
					Duration: e.Duration,
					ThumbURL: fmt.Sprintf("https://i.ytimg.com/vi/%s/hqdefault.jpg", e.ID),
					URL:      fmt.Sprintf("https://www.youtube.com/watch?v=%s", e.ID),
				})
			}
			perSeed[i] = items
		}(idx, vid)
	}
	wg.Wait()

	// Interleave results across all seeds while capping at 2 tracks per channel/artist
	seenVid := map[string]bool{}
	seenTitle := map[string]bool{}
	artistCount := map[string]int{}
	var out []DiscoverItem

	for round := 0; round < 8 && len(out) < 16; round++ {
		for s := 0; s < len(perSeed) && len(out) < 16; s++ {
			if round >= len(perSeed[s]) {
				continue
			}
			it := perSeed[s][round]
			tKey := strings.ToLower(it.Title)
			aKey := strings.ToLower(it.Artist)
			if seenVid[it.VideoID] || seenTitle[tKey] {
				continue
			}
			if aKey != "" && artistCount[aKey] >= 2 {
				continue
			}
			seenVid[it.VideoID] = true
			seenTitle[tKey] = true
			if aKey != "" {
				artistCount[aKey]++
			}
			out = append(out, it)
		}
	}
	return out
}

func (d *Downloader) DiscoverForArtist(artistName string, seed int) []DiscoverItem {
	path := d.app.ytdlp.Path()
	if path == "" {
		return []DiscoverItem{}
	}
	artistName = strings.TrimSpace(artistName)
	if artistName == "" {
		return []DiscoverItem{}
	}
	existingByID := d.existingVideoIDMap()
	localTitles := map[string]bool{}
	targetNorm := strings.ToLower(artistName)

	var artistVids []string
	d.app.trMu.RLock()
	for _, t := range d.app.tracks {
		localTitles[strings.ToLower(strings.TrimSpace(t.Title))] = true
		if m := fileVidIDRe.FindStringSubmatch(t.Path); len(m) == 2 {
			vid := m[1]
			for _, a := range splitArtistsGo(t.Artist) {
				if strings.ToLower(strings.TrimSpace(a)) == targetNorm {
					artistVids = append(artistVids, vid)
					break
				}
			}
		}
	}
	d.app.trMu.RUnlock()

	set := d.app.Settings()
	rng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(seed)*7919))

	// Build queries specifically targeting this artist so we get plenty of songs by this artist + closely related songs
	searchQueries := []string{
		fmt.Sprintf("ytsearch25:%s", artistName),
		fmt.Sprintf("ytsearch25:%s official audio ost", artistName),
	}
	qIdx := ((seed % len(searchQueries)) + len(searchQueries)) % len(searchQueries)
	primarySearch := searchQueries[qIdx]

	var sources []string
	sources = append(sources, primarySearch)
	if len(artistVids) > 0 {
		rng.Shuffle(len(artistVids), func(i, j int) { artistVids[i], artistVids[j] = artistVids[j], artistVids[i] })
		vid := artistVids[0]
		sources = append(sources, fmt.Sprintf("https://www.youtube.com/watch?v=%s&list=RD%s", vid, vid))
	}

	perSource := make([][]DiscoverItem, len(sources))
	var wg sync.WaitGroup

	for idx, src := range sources {
		wg.Add(1)
		go func(i int, target string) {
			defer wg.Done()
			ctx, cancel := context.WithTimeout(context.Background(), 18*time.Second)
			defer cancel()
			args := []string{"--flat-playlist", "-J", "--playlist-end", "25", "--no-warnings"}
			if set.CookieBrowser != "" {
				args = append(args, "--cookies-from-browser", set.CookieBrowser)
			}
			args = append(args, target)
			cmd := exec.CommandContext(ctx, path, args...)
			setCmdHidden(cmd)
			raw, err := cmd.Output()
			if err != nil || len(raw) == 0 {
				return
			}
			var pl ytFlatPlaylist
			if json.Unmarshal(raw, &pl) != nil || len(pl.Entries) == 0 {
				return
			}
			entries := append([]struct {
				ID       string  `json:"id"`
				Title    string  `json:"title"`
				Uploader string  `json:"uploader"`
				Channel  string  `json:"channel"`
				Duration float64 `json:"duration"`
			}(nil), pl.Entries...)
			if seed > 0 {
				localRng := rand.New(rand.NewSource(time.Now().UnixNano() + int64(i)*3571 + int64(seed)*997))
				localRng.Shuffle(len(entries), func(a, b int) { entries[a], entries[b] = entries[b], entries[a] })
			}

			var items []DiscoverItem
			for _, e := range entries {
				if len(e.ID) != 11 {
					continue
				}
				if _, owned := existingByID[e.ID]; owned {
					continue
				}
				// Skip overly long compilations (> 18 min) or tiny clips (< 30s)
				if e.Duration > 0 && (e.Duration < 30 || e.Duration > 1080) {
					continue
				}
				title := strings.TrimSpace(e.Title)
				if title == "" || title == "[Deleted video]" || title == "[Private video]" {
					continue
				}
				if localTitles[strings.ToLower(title)] {
					continue
				}
				artist := strings.TrimSpace(e.Channel)
				if artist == "" {
					artist = strings.TrimSpace(e.Uploader)
				}
				artist = topicSuffixRe.ReplaceAllString(artist, "")
				items = append(items, DiscoverItem{
					VideoID:  e.ID,
					Title:    title,
					Artist:   artist,
					Duration: e.Duration,
					ThumbURL: fmt.Sprintf("https://i.ytimg.com/vi/%s/hqdefault.jpg", e.ID),
					URL:      fmt.Sprintf("https://www.youtube.com/watch?v=%s", e.ID),
				})
			}
			perSource[i] = items
		}(idx, src)
	}
	wg.Wait()

	seenVid := map[string]bool{}
	seenTitle := map[string]bool{}
	var directMatches []DiscoverItem
	var relatedMatches []DiscoverItem

	for s := 0; s < len(perSource); s++ {
		for _, it := range perSource[s] {
			tKey := strings.ToLower(it.Title)
			aKey := strings.ToLower(it.Artist)
			if seenVid[it.VideoID] || seenTitle[tKey] {
				continue
			}
			seenVid[it.VideoID] = true
			seenTitle[tKey] = true
			if strings.Contains(tKey, targetNorm) || strings.Contains(aKey, targetNorm) || s == 0 {
				directMatches = append(directMatches, it)
			} else {
				relatedMatches = append(relatedMatches, it)
			}
		}
	}

	var out []DiscoverItem
	for _, it := range directMatches {
		out = append(out, it)
		if len(out) >= 16 {
			return out
		}
	}
	for _, it := range relatedMatches {
		out = append(out, it)
		if len(out) >= 16 {
			return out
		}
	}
	return out
}
