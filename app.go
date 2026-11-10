package main

import (
	"bufio"
	"context"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"

	wr "github.com/wailsapp/wails/v2/pkg/runtime"
)

type App struct {
	ctx context.Context

	store   *Store
	scanner *Scanner
	player  *Player
	ytdlp   *Ytdlp
	dl      *Downloader
	mpris   *Mpris
	tray    *Tray

	setMu sync.RWMutex
	set   Settings

	trMu   sync.RWMutex
	tracks map[int64]Track

	quitting bool
	mini     bool
	prevW    int
	prevH    int
}

var globalApp *App

func NewApp() *App {
	a := &App{tracks: map[int64]Track{}}
	globalApp = a
	return a
}

func (a *App) emit(event string, data ...any) {
	if a.ctx == nil {
		return
	}
	wr.EventsEmit(a.ctx, event, data...)
}

func (a *App) lookup(id int64) (Track, bool) {
	a.trMu.RLock()
	defer a.trMu.RUnlock()
	t, ok := a.tracks[id]
	return t, ok
}

func (a *App) reloadTracks() []Track {
	list, err := a.store.AllTracks()
	if err != nil {
		return nil
	}
	m := make(map[int64]Track, len(list))
	for _, t := range list {
		m[t.ID] = t
	}
	a.trMu.Lock()
	a.tracks = m
	a.trMu.Unlock()
	return list
}

func (a *App) Settings() Settings {
	a.setMu.RLock()
	defer a.setMu.RUnlock()
	return a.set
}

func (a *App) scanDirs() []string {
	s := a.Settings()
	dirs := append([]string(nil), s.MusicDirs...)
	seen := map[string]bool{}
	for _, d := range dirs {
		seen[d] = true
	}
	if s.DownloadDir != "" && !seen[s.DownloadDir] {
		dirs = append(dirs, s.DownloadDir)
	}
	return dirs
}

func (a *App) startup(ctx context.Context) {
	a.ctx = ctx
	st, err := OpenStore()
	if err != nil {
		wr.LogErrorf(ctx, "store: %v", err)
		wr.MessageDialog(ctx, wr.MessageDialogOptions{Type: wr.ErrorDialog, Title: "Vibe Music", Message: "Cannot open database: " + err.Error()})
		return
	}
	a.store = st
	a.set = st.LoadSettings()
	a.initNativeWindow()
	for _, d := range a.set.MusicDirs {
		_ = os.MkdirAll(d, 0o755)
	}
	_ = os.MkdirAll(a.set.DownloadDir, 0o755)
	st.NormalizeArtists()
	a.reloadTracks()

	a.scanner = NewScanner(st, a.emit)
	a.ytdlp = NewYtdlp(a.emit)
	a.dl = NewDownloader(a)

	a.player = NewPlayer(st, a.lookup, a.emit)
	if err := a.player.Init(a.set); err != nil {
		wr.LogErrorf(ctx, "%v", err)
		wr.MessageDialog(ctx, wr.MessageDialogOptions{Type: wr.ErrorDialog, Title: "Vibe Music",
			Message: "Could not start the audio engine (libmpv): " + err.Error()})
	}

	a.mpris = NewMpris(a)
	a.tray = NewTray(a)
	a.player.notify = func(s PlayerState, t Track, ok bool) {
		if a.mpris != nil {
			a.mpris.Update(s, t, ok)
		}
		if a.tray != nil {
			a.tray.Update(s, t, ok)
		}
	}
	a.mpris.Start()
	a.tray.Start()

	// library
	go func() {
		a.scanner.Scan(a.scanDirs())
		a.reloadTracks()
		a.emit("library:changed")
		a.scanner.Watch(a.scanDirs())
	}()
	// yt-dlp auto update on every launch
	if a.set.AutoUpdateYtdlp {
		go a.ytdlp.EnsureUpToDate()
	} else {
		go func() {
			p := a.ytdlp.Path()
			a.ytdlp.refreshFlags(p)
			a.ytdlp.set(YtdlpStatus{State: "idle", Path: p, Version: a.ytdlp.version(orEmpty(p))})
		}()
	}
}

func (a *App) beforeClose(ctx context.Context) bool {
	if a.quitting {
		return false
	}
	if a.Settings().CloseToTray && a.tray != nil && a.tray.Ready() {
		wr.WindowHide(ctx)
		return true
	}
	return false
}

func (a *App) shutdown(ctx context.Context) {
	if a.player != nil {
		a.player.Close()
	}
	if a.mpris != nil {
		a.mpris.Close()
	}
	if a.tray != nil {
		a.tray.Close()
	}
}

// ---------- window ----------

func (a *App) ShowWindow() {
	nativeShowWindow()
}

func (a *App) MinimiseWindow() {
	nativeMinimiseWindow()
}

func (a *App) ToggleMaximiseWindow() {
	nativeToggleMaximiseWindow()
}

func (a *App) ArmWindowDrag() {
	nativeArmWindowDrag()
}

func (a *App) CloseWindow() {
	if a.Settings().CloseToTray && a.tray != nil && a.tray.Ready() {
		wr.WindowHide(a.ctx)
		return
	}
	a.QuitApp()
}

func (a *App) GetVersion() string {
	return "0.4.1"
}

func (a *App) OpenInBrowser(url string) {
	url = strings.TrimSpace(url)
	if !strings.HasPrefix(url, "http://") && !strings.HasPrefix(url, "https://") {
		return
	}
	nativeOpenBrowser(url, a.Settings().BrowserBackground)
}

func (a *App) QuitApp() {
	a.quitting = true
	wr.Quit(a.ctx)
}

func (a *App) SetMiniMode(on bool) {
	if on == a.mini {
		return
	}
	a.mini = on
	nativeSetMini(on)
}

// ---------- library ----------

func (a *App) GetTracks() []Track {
	list := a.reloadTracks()
	if list == nil {
		return []Track{}
	}
	return list
}

func (a *App) Rescan() {
	go func() {
		a.scanner.Scan(a.scanDirs())
		a.reloadTracks()
		a.emit("library:changed")
	}()
}

func (a *App) ToggleLike(id int64) bool {
	t, ok := a.lookup(id)
	if !ok {
		return false
	}
	nv := !t.Liked
	if err := a.store.SetLiked(id, nv); err != nil {
		return t.Liked
	}
	t.Liked = nv
	a.trMu.Lock()
	a.tracks[id] = t
	a.trMu.Unlock()
	a.emit("track:liked", id, nv)
	return nv
}

func (a *App) ShowInFolder(id int64) {
	if t, ok := a.lookup(id); ok {
		if runtime.GOOS == "windows" {
			_ = exec.Command("explorer.exe", "/select,", t.Path).Start()
		} else {
			_ = exec.Command("xdg-open", filepath.Dir(t.Path)).Start()
		}
	}
}

// TrashTrack moves the file to the trash and removes it from the library.
func (a *App) TrashTrack(id int64) string {
	t, ok := a.lookup(id)
	if !ok {
		return "track not found"
	}
	if runtime.GOOS == "windows" {
		if err := os.Remove(t.Path); err != nil {
			return "could not remove file: " + err.Error()
		}
	} else {
		if err := exec.Command("gio", "trash", t.Path).Run(); err != nil {
			return "could not move to trash (is gio installed?): " + err.Error()
		}
	}
	_ = a.store.DeleteTracks([]int64{id})
	_ = os.Remove(coverPath(id))
	a.reloadTracks()
	a.emit("library:changed")
	a.emit("playlists:changed")
	return ""
}

func (a *App) GetLyrics(id int64) Lyrics {
	t, ok := a.lookup(id)
	if !ok {
		return Lyrics{}
	}
	return ParseLyrics(ReadLyrics(t.Path))
}

// ---------- playlists ----------

func (a *App) GetPlaylists() []Playlist {
	p, err := a.store.Playlists()
	if err != nil {
		return []Playlist{}
	}
	return p
}

func (a *App) GetPlaylistTrackIDs(id int64) []int64 {
	ids, err := a.store.PlaylistTrackIDs(id)
	if err != nil {
		return []int64{}
	}
	return ids
}

func (a *App) GetAllPlaylistTracks() map[int64][]int64 {
	return a.store.AllPlaylistTracks()
}

func (a *App) CreatePlaylist(name string, trackIDs []int64) int64 {
	id, err := a.store.CreatePlaylist(name)
	if err != nil {
		return 0
	}
	if len(trackIDs) > 0 {
		_, _ = a.store.AddToPlaylist(id, trackIDs)
	}
	a.emit("playlists:changed")
	return id
}

func (a *App) RenamePlaylist(id int64, name string) {
	_ = a.store.RenamePlaylist(id, name)
	a.emit("playlists:changed")
}

func (a *App) DeletePlaylist(id int64) {
	_ = a.store.DeletePlaylist(id)
	a.emit("playlists:changed")
}

func (a *App) SetPlaylistTracks(id int64, ids []int64) {
	_ = a.store.SetPlaylistTracks(id, ids)
	a.emit("playlists:changed")
}

func (a *App) AddToPlaylist(id int64, ids []int64) int {
	n, _ := a.store.AddToPlaylist(id, ids)
	a.emit("playlists:changed")
	return n
}

func (a *App) addFilesToPlaylist(name string, files []string) {
	var ids []int64
	for _, f := range files {
		if t, ok := a.store.TrackByPath(f); ok {
			ids = append(ids, t.ID)
		}
	}
	if len(ids) == 0 {
		return
	}
	id, ok := a.store.PlaylistByName(name)
	if !ok {
		var err error
		id, err = a.store.CreatePlaylist(name)
		if err != nil {
			return
		}
	}
	_, _ = a.store.AddToPlaylist(id, ids)
	a.emit("playlists:changed")
}

func (a *App) likeFiles(files []string) {
	changed := false
	for _, f := range files {
		if t, ok := a.store.TrackByPath(f); ok && !t.Liked {
			_ = a.store.SetLiked(t.ID, true)
			a.trMu.Lock()
			if cur, exists := a.tracks[t.ID]; exists {
				cur.Liked = true
				a.tracks[t.ID] = cur
			}
			a.trMu.Unlock()
			a.emit("track:liked", t.ID, true)
			changed = true
		}
	}
	if changed {
		a.emit("library:changed")
	}
}

// ImportM3U opens a file picker and creates a playlist from an .m3u/.m3u8 file.
func (a *App) ImportM3U() string {
	file, err := wr.OpenFileDialog(a.ctx, wr.OpenDialogOptions{
		Title:   "Import playlist",
		Filters: []wr.FileFilter{{DisplayName: "Playlists (*.m3u, *.m3u8)", Pattern: "*.m3u;*.m3u8"}},
	})
	if err != nil || file == "" {
		return ""
	}
	f, err := os.Open(file)
	if err != nil {
		return err.Error()
	}
	defer f.Close()
	dir := filepath.Dir(file)
	var files []string
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		l := strings.TrimSpace(sc.Text())
		if l == "" || strings.HasPrefix(l, "#") {
			continue
		}
		l = strings.TrimPrefix(l, "file://")
		if !filepath.IsAbs(l) {
			l = filepath.Join(dir, l)
		}
		files = append(files, filepath.Clean(l))
	}
	name := strings.TrimSuffix(filepath.Base(file), filepath.Ext(file))
	a.scanner.Scan(a.scanDirs())
	a.reloadTracks()
	var ids []int64
	for _, p := range files {
		if t, ok := a.store.TrackByPath(p); ok {
			ids = append(ids, t.ID)
		}
	}
	if len(ids) == 0 {
		return "No tracks from that playlist were found in your library."
	}
	a.CreatePlaylist(name, ids)
	a.emit("library:changed")
	return ""
}

// ExportPlaylist saves a playlist as .m3u8.
func (a *App) ExportPlaylist(id int64, name string) string {
	file, err := wr.SaveFileDialog(a.ctx, wr.SaveDialogOptions{
		Title: "Export playlist", DefaultFilename: name + ".m3u8",
		Filters: []wr.FileFilter{{DisplayName: "M3U8 playlist", Pattern: "*.m3u8"}},
	})
	if err != nil || file == "" {
		return ""
	}
	var b strings.Builder
	b.WriteString("#EXTM3U\n")
	for _, tid := range a.GetPlaylistTrackIDs(id) {
		if t, ok := a.lookup(tid); ok {
			fmt.Fprintf(&b, "#EXTINF:%d,%s - %s\n%s\n", int(t.Duration), t.Artist, t.Title, t.Path)
		}
	}
	if err := os.WriteFile(file, []byte(b.String()), 0o644); err != nil {
		return err.Error()
	}
	return ""
}

// ---------- player ----------

func (a *App) PlayTracks(ids []int64, start int, shuffle bool, playlistID int64) {
	a.player.PlayTracks(ids, start, shuffle, playlistID)
	a.persistPlayerPrefs()
}
func (a *App) PlayInstant(id int64)        { a.player.PlayInstant(id) }
func (a *App) PlayQueueIndex(i int)        { a.player.PlayQueueIndex(i) }
func (a *App) Pause()                      { a.player.Pause() }
func (a *App) Resume()                     { a.player.Play() }
func (a *App) Next()                       { a.player.Next() }
func (a *App) Previous()                   { a.player.Prev() }
func (a *App) Seek(sec float64)            { a.player.Seek(sec) }
func (a *App) SeekRelative(delta float64)  { a.player.SeekRelative(delta) }
func (a *App) PlayNext(ids []int64)        { a.player.PlayNext(ids) }
func (a *App) AddToQueue(ids []int64)      { a.player.AddToQueue(ids) }
func (a *App) RemoveFromQueue(qid int64)   { a.player.RemoveFromQueue(qid) }
func (a *App) MoveInQueue(from, to int)    { a.player.MoveInQueue(from, to) }
func (a *App) ClearQueue()                 { a.player.ClearQueue() }
func (a *App) SetSleepTimer(minutes int)   { a.player.SetSleep(minutes) }
func (a *App) GetPlayerState() PlayerState { return a.player.State() }
func (a *App) GetQueue() map[string]any    { return a.player.Queue() }
func (a *App) OnAudioLoaded(dur float64)   { a.player.OnAudioLoaded(dur) }
func (a *App) OnAudioTime(pos, dur float64) { a.player.OnAudioTime(pos, dur) }
func (a *App) OnAudioPause(paused bool)    { a.player.OnAudioPause(paused) }
func (a *App) OnAudioEOF()                 { a.player.OnAudioEOF() }
func (a *App) OnAudioError(msg string)     { a.player.OnAudioError(msg) }

// TogglePlayPause is also used by media keys / tray; with an empty queue it starts the whole library.
func (a *App) TogglePlayPause() {
	st := a.player.State()
	if st.QueueLen == 0 && st.TrackID == 0 {
		list := a.reloadTracks()
		ids := make([]int64, 0, len(list))
		for _, t := range list {
			ids = append(ids, t.ID)
		}
		sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
		a.player.PlayTracks(ids, -1, a.Settings().Shuffle, 0)
		return
	}
	a.player.Toggle()
}

func (a *App) SetVolume(v int) {
	a.player.SetVolume(v)
	a.updateSettings(func(s *Settings) { s.Volume = v })
}

func (a *App) ToggleMute() { a.player.ToggleMute() }

func (a *App) SetShuffle(on bool) {
	a.player.SetShuffle(on)
	a.updateSettings(func(s *Settings) { s.Shuffle = on })
}

func (a *App) SetRepeat(mode string) {
	a.player.SetRepeat(mode)
	a.updateSettings(func(s *Settings) { s.Repeat = mode })
}

func (a *App) persistPlayerPrefs() {
	st := a.player.State()
	a.updateSettings(func(s *Settings) { s.Shuffle = st.Shuffle })
}

func (a *App) updateSettings(f func(*Settings)) {
	a.setMu.Lock()
	f(&a.set)
	cur := a.set
	a.setMu.Unlock()
	_ = a.store.SaveSettings(cur)
}

// ---------- settings ----------

func (a *App) GetSettings() Settings { return a.Settings() }

func (a *App) SaveSettings(s Settings) Settings {
	old := a.Settings()
	if len(s.EQ) != 10 {
		s.EQ = make([]float64, 10)
	}
	var clean []string
	for _, d := range s.MusicDirs {
		if d = strings.TrimSpace(d); d != "" {
			clean = append(clean, d)
		}
	}
	s.MusicDirs = clean
	if len(s.MusicDirs) == 0 {
		s.MusicDirs = defaultSettings().MusicDirs
	}
	if s.DownloadDir == "" {
		s.DownloadDir = s.MusicDirs[0]
	}
	if len(s.HomeOrder) == 0 {
		s.HomeOrder = old.HomeOrder
	}
	if s.QuickPicksRows == "" {
		s.QuickPicksRows = old.QuickPicksRows
		if s.QuickPicksRows == "" {
			s.QuickPicksRows = "1"
		}
	}
	s.WindowWidth = old.WindowWidth
	s.WindowHeight = old.WindowHeight
	s.WindowMaximised = old.WindowMaximised
	a.setMu.Lock()
	a.set = s
	a.setMu.Unlock()
	_ = a.store.SaveSettings(s)
	a.emit("settings:changed", s)
	a.player.ApplySettings(s)
	if strings.Join(old.MusicDirs, "|") != strings.Join(s.MusicDirs, "|") || old.DownloadDir != s.DownloadDir {
		for _, d := range s.MusicDirs {
			_ = os.MkdirAll(d, 0o755)
		}
		_ = os.MkdirAll(s.DownloadDir, 0o755)
		go func() {
			a.scanner.Scan(a.scanDirs())
			a.reloadTracks()
			a.emit("library:changed")
			a.scanner.Watch(a.scanDirs())
		}()
	}
	return s
}

func (a *App) PickFolder() string {
	d, err := wr.OpenDirectoryDialog(a.ctx, wr.OpenDialogOptions{Title: "Choose a folder"})
	if err != nil {
		return ""
	}
	return d
}

// ---------- downloads ----------

func (a *App) StartDownload(url, mode string) string {
	if _, err := a.dl.Add(url, mode); err != nil {
		return err.Error()
	}
	return ""
}
func (a *App) StartDownloadToPlaylist(url string, playlistID int64) string {
	if _, err := a.dl.AddToPlaylist(url, playlistID); err != nil {
		return err.Error()
	}
	return ""
}
func (a *App) CancelDownload(id int64)     { a.dl.Cancel(id) }
func (a *App) GetDownloads() []DownloadJob { return a.dl.List() }
func (a *App) ClearDownloads()             { a.dl.ClearFinished() }
func (a *App) GetYtdlpStatus() YtdlpStatus { return a.ytdlp.Status() }
func (a *App) UpdateYtdlp()                { go a.ytdlp.EnsureUpToDate() }
func (a *App) GetOnlineDiscovery(seed int) []DiscoverItem {
	return a.dl.DiscoverOnline(seed)
}
func (a *App) GetArtistOnlineDiscovery(artist string, seed int) []DiscoverItem {
	return a.dl.DiscoverForArtist(artist, seed)
}

// ---------- cover art asset handler ----------

type coverHandler struct{}

func (coverHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if strings.HasPrefix(r.URL.Path, "/cover/") {
		id, err := strconv.ParseInt(strings.TrimPrefix(r.URL.Path, "/cover/"), 10, 64)
		if err == nil {
			p := coverPath(id)
			if _, err := os.Stat(p); err == nil {
				w.Header().Set("Cache-Control", "public, max-age=86400")
				http.ServeFile(w, r, p)
				return
			}
		}
	}
	if strings.HasPrefix(r.URL.Path, "/audio/") {
		id, err := strconv.ParseInt(strings.TrimPrefix(r.URL.Path, "/audio/"), 10, 64)
		if err == nil && globalApp != nil {
			if t, ok := globalApp.lookup(id); ok {
				if _, err := os.Stat(t.Path); err == nil {
					http.ServeFile(w, r, t.Path)
					return
				}
			}
		}
	}
	http.NotFound(w, r)
}

var _ = context.Background
