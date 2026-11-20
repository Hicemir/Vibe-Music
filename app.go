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
	"strings"
	"sync"
	"time"

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
	localAI *LocalAIManager
	genres  *GenreDetector

	setMu sync.RWMutex
	set   Settings

	trMu   sync.RWMutex
	tracks map[string]Track

	ytmMu      sync.Mutex
	ytmSyncing bool
	ytmMessage string

	quitting bool
	mini     bool
	prevW    int
	prevH    int
}

var globalApp *App

func NewApp() *App {
	a := &App{tracks: map[string]Track{}}
	globalApp = a
	return a
}

func (a *App) emit(event string, data ...any) {
	if a.ctx == nil {
		return
	}
	wr.EventsEmit(a.ctx, event, data...)
}

func (a *App) lookup(id string) (Track, bool) {
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
	m := make(map[string]Track, len(list))
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
	a.localAI = NewLocalAIManager(a.emit)
	a.genres = NewGenreDetector(a)
	a.scanner.onChanged = func() { a.reloadTracks() }
	a.scanner.onDone = a.genres.Kick
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
	if a.genres != nil {
		a.genres.Cancel()
	}
	if a.localAI != nil {
		a.localAI.Shutdown()
	}
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
	return "0.6.2"
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

// ingestFiles indexes just the given files (e.g. fresh downloads), refreshes the in-memory track map,
// notifies the UI and kicks off genre detection. Falls back to a full scan if any file cannot be indexed.
func (a *App) ingestFiles(files []string) {
	dirs := a.scanDirs()
	failed := false
	for _, f := range files {
		if t, ok := a.store.TrackByPath(f); ok {
			if st, err := os.Stat(f); err == nil && st.ModTime().Unix() == t.MTime && st.Size() == t.Size {
				continue
			}
		}
		if !a.scanner.IngestFile(f, dirs) {
			failed = true
		}
	}
	if failed {
		a.scanner.Scan(dirs)
	}
	a.reloadTracks()
	a.emit("library:changed")
	if a.genres != nil {
		a.genres.Kick()
	}
}

func (a *App) Rescan() {
	go a.scanner.Scan(a.scanDirs())
}

func (a *App) ToggleLike(id string) bool {
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

func (a *App) ShowInFolder(id string) {
	if t, ok := a.lookup(id); ok {
		if runtime.GOOS == "windows" {
			_ = exec.Command("explorer.exe", "/select,", t.Path).Start()
		} else {
			_ = exec.Command("xdg-open", filepath.Dir(t.Path)).Start()
		}
	}
}

// TrashTrack moves the file to the trash and removes it from the library.
func (a *App) TrashTrack(id string) string {
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
	_ = a.store.DeleteTracks([]string{id})
	_ = os.Remove(coverPath(id))
	a.reloadTracks()
	a.emit("library:changed")
	a.emit("playlists:changed")
	return ""
}

func (a *App) GetLyrics(id string) Lyrics {
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

func (a *App) GetPlaylistTrackIDs(id int64) []string {
	ids, err := a.store.PlaylistTrackIDs(id)
	if err != nil {
		return []string{}
	}
	return ids
}

func (a *App) GetAllPlaylistTracks() map[int64][]string {
	return a.store.AllPlaylistTracks()
}

func (a *App) CreatePlaylist(name string, trackIDs []string) int64 {
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

func (a *App) SetPlaylistTracks(id int64, ids []string) {
	_ = a.store.SetPlaylistTracks(id, ids)
	a.emit("playlists:changed")
}

func (a *App) AddToPlaylist(id int64, ids []string) int {
	n, _ := a.store.AddToPlaylist(id, ids)
	a.emit("playlists:changed")
	return n
}

func (a *App) addFilesToPlaylist(name string, files []string) {
	var ids []string
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

// m3uEntry is one parsed playlist line with its optional #EXTVIBE hints.
type m3uEntry struct {
	id, ytID, path string
}

// parseVibeTag parses "ID=<id>;YTID=<yt>" from an #EXTVIBE line.
func parseVibeTag(line string) (id, ytID string) {
	for _, kv := range strings.Split(strings.TrimPrefix(line, "#EXTVIBE:"), ";") {
		k, v, ok := strings.Cut(strings.TrimSpace(kv), "=")
		if !ok {
			continue
		}
		switch strings.ToUpper(k) {
		case "ID":
			id = v
		case "YTID":
			ytID = v
		}
	}
	return
}

// resolveM3U maps an entry to a library track: by ID / YTID first, then by normalised rel_path.
func (a *App) resolveM3U(e m3uEntry, fileDir string) (Track, bool) {
	if e.id != "" {
		if t, ok := a.store.TrackByID(e.id); ok {
			return t, true
		}
	}
	if e.ytID != "" && ytIDRe.MatchString(e.ytID) {
		if t, ok := a.store.TrackByID("yt:" + e.ytID); ok {
			return t, true
		}
	}
	p := strings.TrimPrefix(e.path, "file://")
	if p == "" {
		return Track{}, false
	}
	// absolute entries from other tools: try as a local file inside a registered root
	if filepath.IsAbs(p) || driveLetter.MatchString(p) {
		if t, ok := a.store.TrackByPath(filepath.Clean(p)); ok {
			return t, true
		}
		return Track{}, false
	}
	rel, ok := cleanRelPath(p)
	if !ok {
		return Track{}, false
	}
	if t, ok := a.store.TrackByRelAnyRoot(rel); ok {
		return t, true
	}
	// relative to the playlist file's own folder
	if t, ok := a.store.TrackByPath(filepath.Join(fileDir, filepath.FromSlash(rel))); ok {
		return t, true
	}
	return Track{}, false
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
	var entries []m3uEntry
	var pending m3uEntry
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		l := strings.TrimSpace(strings.TrimPrefix(sc.Text(), "\ufeff"))
		if l == "" {
			continue
		}
		if strings.HasPrefix(l, "#EXTVIBE:") {
			pending.id, pending.ytID = parseVibeTag(l)
			continue
		}
		if strings.HasPrefix(l, "#") {
			continue
		}
		pending.path = strings.ReplaceAll(l, "\\", "/")
		entries = append(entries, pending)
		pending = m3uEntry{}
	}
	name := strings.TrimSuffix(filepath.Base(file), filepath.Ext(file))
	a.scanner.Scan(a.scanDirs())
	a.reloadTracks()
	var ids []string
	for _, e := range entries {
		if t, ok := a.resolveM3U(e, dir); ok {
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

// ExportPlaylist saves a playlist as a portable .m3u8 (forward-slash rel_paths, never absolute).
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
			fmt.Fprintf(&b, "#EXTVIBE:ID=%s;YTID=%s\n", t.ID, t.YtID)
			fmt.Fprintf(&b, "#EXTINF:%d,%s - %s\n%s\n", int(t.Duration), t.Artist, t.Title, t.RelPath)
		}
	}
	if err := os.WriteFile(file, []byte(b.String()), 0o644); err != nil {
		return err.Error()
	}
	return ""
}

// ---------- player ----------

func (a *App) PlayTracks(ids []string, start int, shuffle bool, playlistID int64) {
	a.player.PlayTracks(ids, start, shuffle, playlistID)
	a.persistPlayerPrefs()
}
func (a *App) PlayInstant(id string)        { a.player.PlayInstant(id) }
func (a *App) PlayQueueIndex(i int)        { a.player.PlayQueueIndex(i) }
func (a *App) Pause()                      { a.player.Pause() }
func (a *App) Resume()                     { a.player.Play() }
func (a *App) Next()                       { a.player.Next() }
func (a *App) Previous()                   { a.player.Prev() }
func (a *App) Seek(sec float64)            { a.player.Seek(sec) }
func (a *App) SeekRelative(delta float64)  { a.player.SeekRelative(delta) }
func (a *App) PlayNext(ids []string)        { a.player.PlayNext(ids) }
func (a *App) AddToQueue(ids []string)      { a.player.AddToQueue(ids) }
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
	if st.QueueLen == 0 && st.TrackID == "" {
		list := a.reloadTracks()
		ids := make([]string, 0, len(list))
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
	// The first music folder is the primary one and is where new downloads are stored.
	s.DownloadDir = s.MusicDirs[0]
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
	s.AI.Provider = strings.TrimSpace(s.AI.Provider)
	if s.AI.Provider == "" {
		s.AI.Provider = "openai"
	}
	s.AI.BaseURL = strings.TrimSpace(s.AI.BaseURL)
	s.AI.APIKey = strings.TrimSpace(s.AI.APIKey)
	s.AI.Model = strings.TrimSpace(s.AI.Model)
	s.AI.LocalModelID = strings.TrimSpace(s.AI.LocalModelID)
	if s.AI.LocalModelID == "" {
		s.AI.LocalModelID = "qwen2.5-3b"
	}
	s.AI.LocalCustomGGUF = strings.TrimSpace(s.AI.LocalCustomGGUF)
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

// ---------- genres ----------

// GetGenreTaxonomy returns the genre tree (with parents and cousin links) used for similarity.
func (a *App) GetGenreTaxonomy() GenreTaxonomy { return getGenreTaxonomy() }

// SetTracksGenre sets (or clears, when empty) the genres of the given tracks by hand.
// genres is a list of taxonomy names or custom text.
func (a *App) SetTracksGenre(ids []string, genres []string) {
	clean := cleanUserGenres(genres)
	src := "user"
	if len(clean) == 0 {
		src = ""
	}
	if err := a.store.SetTracksGenre(ids, joinGenres(clean), src); err != nil {
		return
	}
	a.reloadTracks()
	a.emit("library:changed")
	if len(clean) == 0 {
		a.genres.Kick()
	}
}

// RedetectGenres forgets all automatically detected genres (hand-set ones stay) and detects again.
func (a *App) RedetectGenres() {
	_ = a.store.ClearPendingAITracks()
	a.store.ResetGenres()
	a.reloadTracks()
	a.emit("library:changed")
	a.genres.KickClassic()
}

func (a *App) GetGenreStatus() GenreStatus { return a.genres.Status() }

func (a *App) GetGenreTargets() GenreTargets { return a.genres.Targets() }

// DetectGenres runs classic genre detection for tracks that have none (tags, same-artist, online lookup).
func (a *App) DetectGenres() { a.genres.KickClassic() }

func (a *App) DetectGenresAI(mode string) string { return a.genres.StartAI(mode) }

func (a *App) CancelGenreDetection() {
	if a.genres != nil {
		a.genres.Cancel()
	}
}

func (a *App) ClearPendingAI() {
	if a.store != nil {
		_ = a.store.ClearPendingAITracks()
	}
	if a.genres != nil {
		a.emit("genres:status", a.genres.Status())
	}
}

func (a *App) GetAIProviders() []AIProviderPreset { return GetAIProviders() }

func (a *App) GetLocalAIModels() []LocalAIModel { return GetLocalAIModels() }

func (a *App) GetLocalAIStatus() LocalAIStatus {
	if a.localAI == nil {
		return LocalAIStatus{InstalledModels: []string{}, State: "idle"}
	}
	return a.localAI.Status()
}

func (a *App) DownloadLocalAI(modelID string, useGPU bool) string {
	if a.localAI == nil {
		return "local AI manager is not initialized"
	}
	return a.localAI.Download(modelID, useGPU)
}

func (a *App) CancelLocalAIDownload() {
	if a.localAI != nil {
		a.localAI.CancelDownload()
	}
}

func (a *App) DeleteLocalModel(modelID string) string {
	if a.localAI == nil {
		return "local AI manager is not initialized"
	}
	return a.localAI.DeleteModel(modelID)
}

func (a *App) DeleteAllLocalAI() string {
	if a.localAI == nil {
		return "local AI manager is not initialized"
	}
	return a.localAI.DeleteAll()
}

func (a *App) PickGGUFFile() string {
	f, err := wr.OpenFileDialog(a.ctx, wr.OpenDialogOptions{
		Title:   "Choose a GGUF model file",
		Filters: []wr.FileFilter{{DisplayName: "GGUF Models (*.gguf)", Pattern: "*.gguf"}},
	})
	if err != nil {
		return ""
	}
	return f
}

func (a *App) TestAIConnection(cfg AISettings) AITestResult {
	if cfg.Provider == "local" {
		if a.genres != nil && a.genres.Status().Running {
			return AITestResult{OK: false, Message: "Wait for active genre detection to finish before testing local AI"}
		}
		if a.localAI == nil {
			return AITestResult{OK: false, Message: "Local AI manager is not initialized"}
		}
		modelPath, err := a.localAI.ModelPath(cfg)
		if err != nil {
			return AITestResult{OK: false, Message: err.Error()}
		}
		ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
		defer cancel()
		baseURL, err := a.localAI.Start(ctx, modelPath, cfg.LocalGPU)
		if err != nil {
			return AITestResult{OK: false, Message: err.Error()}
		}
		defer a.localAI.Stop()
		p := newHTTPAIClient(resolvedAIConfig{
			providerID: "local",
			kind:       "openai",
			baseURL:    baseURL,
			model:      "local",
		})
		return testAIProvider(ctx, p)
	}
	rcfg, err := resolveAIConfig(cfg)
	if err != nil {
		return AITestResult{OK: false, Message: err.Error()}
	}
	return testAIProvider(context.Background(), newHTTPAIClient(rcfg))
}

// ---------- move music ----------

// MoveMusic moves every track stored under the source folders into dest (keeping sub-folder layout),
// updating the library in place so likes, play counts and playlists are preserved.
func (a *App) MoveMusic(sources []string, dest string) {
	go a.moveMusic(sources, dest)
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

func (a *App) FetchYtMusicLibrary() YtMusicLibraryResponse {
	cols, err := a.fetchYtMusicLibraryInternal()
	if err != nil {
		return YtMusicLibraryResponse{
			Collections: []YtMusicCollection{},
			SyncedIDs:   a.store.LoadSyncedYtCollections(),
			Error:       err.Error(),
		}
	}
	return YtMusicLibraryResponse{
		Collections: cols,
		SyncedIDs:   a.store.LoadSyncedYtCollections(),
	}
}

// ---------- cover art asset handler ----------

type coverHandler struct{}

func (coverHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if strings.HasPrefix(r.URL.Path, "/cover/") {
		id := strings.TrimPrefix(r.URL.Path, "/cover/")
		if id != "" {
			maxDim := normalizeThumbSize(r.URL.Query().Get("s"))
			p := resizedCoverPath(id, maxDim)
			if p != "" {
				w.Header().Set("Cache-Control", "public, max-age=86400")
				http.ServeFile(w, r, p)
				return
			}
		}
	}
	if strings.HasPrefix(r.URL.Path, "/audio/") {
		id := strings.TrimPrefix(r.URL.Path, "/audio/")
		if id != "" && globalApp != nil {
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
