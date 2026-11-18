package main

import (
	"crypto/sha1"
	"crypto/sha256"
	"database/sql"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
	"sync"
	"time"

	"golang.org/x/text/unicode/norm"
	_ "modernc.org/sqlite"
)

// ---------- paths ----------

func dataDir() string {
	if runtime.GOOS == "windows" {
		if base, err := os.UserConfigDir(); err == nil && base != "" {
			d := filepath.Join(base, "vibemusic")
			_ = os.MkdirAll(d, 0o755)
			return d
		}
	}
	base := os.Getenv("XDG_DATA_HOME")
	if base == "" {
		home, _ := os.UserHomeDir()
		base = filepath.Join(home, ".local", "share")
	}
	d := filepath.Join(base, "vibemusic")
	_ = os.MkdirAll(d, 0o755)
	return d
}

func coverDir() string {
	d := filepath.Join(dataDir(), "covers")
	_ = os.MkdirAll(d, 0o755)
	return d
}

func binDir() string {
	d := filepath.Join(dataDir(), "bin")
	_ = os.MkdirAll(d, 0o755)
	return d
}

// ---------- models ----------

type Track struct {
	ID        string  `json:"id"`    // "yt:<video_id>" or "local:<content hash>"
	YtID      string  `json:"ytId"`  // 11-char YouTube video id, "" for non-YouTube files
	RootKey   string  `json:"-"`     // logical root alias (see local_roots)
	RelPath   string  `json:"-"`     // forward-slash, NFC-normalised path relative to RootKey
	Path      string  `json:"-"`     // runtime-only absolute OS path resolved from local_roots
	Title     string  `json:"title"`
	Artist    string  `json:"artist"`
	Album     string  `json:"album"`
	Duration  float64 `json:"duration"`
	Year      int     `json:"year"`
	AddedAt   int64   `json:"addedAt"`
	Liked     bool    `json:"liked"`
	PlayCount int     `json:"playCount"`
	HasCover  bool    `json:"hasCover"`
	Format    string  `json:"format"`
	Bitrate   int     `json:"bitrate"`
	LastPlay  int64   `json:"lastPlayed"`
	Size      int64   `json:"-"`
	MTime     int64   `json:"-"`
}

type Playlist struct {
	ID          int64   `json:"id"`
	Name        string  `json:"name"`
	CreatedAt   int64   `json:"createdAt"`
	LastPlayed  int64   `json:"lastPlayed"`
	TrackCount  int     `json:"trackCount"`
	Duration    float64 `json:"duration"`
	CoverTracks []string `json:"coverTracks"`
}

type Settings struct {
	MusicDirs       []string  `json:"musicDirs"`
	DownloadDir     string    `json:"downloadDir"`
	DownloadFormat  string    `json:"downloadFormat"` // best | m4a | opus | mp3
	Volume          int       `json:"volume"`
	Shuffle         bool      `json:"shuffle"`
	Repeat          string    `json:"repeat"` // off | all | one
	FadeSeconds     float64   `json:"fadeSeconds"`
	EQEnabled       bool      `json:"eqEnabled"`
	EQ              []float64 `json:"eq"`
	AutoUpdateYtdlp bool      `json:"autoUpdateYtdlp"`
	CloseToTray     bool      `json:"closeToTray"`
	Normalize       bool      `json:"normalize"`
	WindowWidth     int       `json:"windowWidth"`
	WindowHeight    int       `json:"windowHeight"`
	WindowMaximised bool      `json:"windowMaximised"`
	HomeOrder         []string  `json:"homeOrder"`
	HomeHidden        []string  `json:"homeHidden"`
	QuickPicksRows    string    `json:"quickPicksRows"` // "1" | "2" | "3" | "auto"
	CookieBrowser     string    `json:"cookieBrowser"`  // "" | "firefox" | "brave" | "chrome" | "chromium" | "edge" | "vivaldi"
	BrowserBackground bool      `json:"browserBackground"`
}

var defaultHomeOrder = []string{"recentPlaylists", "topTracks", "quickPicks", "artistRadios", "discover", "recentlyAdded", "allMusic"}

func defaultSettings() Settings {
	home, _ := os.UserHomeDir()
	m := filepath.Join(home, "Music")
	return Settings{
		MusicDirs:       []string{m},
		DownloadDir:     m,
		DownloadFormat:  "m4a",
		Volume:          80,
		Repeat:          "off",
		EQ:              make([]float64, 10),
		AutoUpdateYtdlp: true,
		CloseToTray:     true,
		WindowWidth:     1500,
		WindowHeight:    920,
		WindowMaximised: true,
		HomeOrder:       append([]string(nil), defaultHomeOrder...),
		HomeHidden:      []string{"discover"},
		QuickPicksRows:  "1",
		CookieBrowser:   "",
	}
}

// ---------- store ----------

type Store struct {
	db *sql.DB
	mu sync.Mutex // serialises writes

	rmu   sync.RWMutex
	roots map[string]string // root_key -> OS base directory (device-local)
}

func OpenStore() (*Store, error) {
	p := filepath.Join(dataDir(), "library.db")
	db, err := sql.Open("sqlite", "file:"+p+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	s := &Store{db: db, roots: map[string]string{}}
	if err := s.migrate(); err != nil {
		return nil, err
	}
	s.loadRoots()
	return s, nil
}

func (s *Store) migrate() error {
	var legacy int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM pragma_table_info('tracks') WHERE name='path'`).Scan(&legacy)
	if legacy > 0 {
		return s.migrateLegacy()
	}
	return s.createSchema(s.db)
}

type execer interface {
	Exec(query string, args ...any) (sql.Result, error)
}

func (s *Store) createSchema(x execer) error {
	_, err := x.Exec(`
CREATE TABLE IF NOT EXISTS local_roots(
  root_key TEXT PRIMARY KEY,
  base_path TEXT UNIQUE NOT NULL
);
CREATE TABLE IF NOT EXISTS tracks(
  id TEXT PRIMARY KEY,
  yt_id TEXT UNIQUE,
  root_key TEXT NOT NULL,
  rel_path TEXT NOT NULL,
  title TEXT NOT NULL DEFAULT '',
  artist TEXT NOT NULL DEFAULT '',
  album TEXT NOT NULL DEFAULT '',
  duration REAL NOT NULL DEFAULT 0,
  year INTEGER NOT NULL DEFAULT 0,
  added_at INTEGER NOT NULL DEFAULT 0,
  liked INTEGER NOT NULL DEFAULT 0,
  play_count INTEGER NOT NULL DEFAULT 0,
  last_played INTEGER NOT NULL DEFAULT 0,
  has_cover INTEGER NOT NULL DEFAULT 0,
  format TEXT NOT NULL DEFAULT '',
  bitrate INTEGER NOT NULL DEFAULT 0,
  file_size INTEGER NOT NULL DEFAULT 0,
  mtime_unix INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0,
  UNIQUE(root_key, rel_path)
);
CREATE TABLE IF NOT EXISTS playlists(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  created_at INTEGER NOT NULL DEFAULT 0,
  last_played INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS playlist_tracks(
  playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
  track_id TEXT NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  PRIMARY KEY(playlist_id, position)
);
CREATE INDEX IF NOT EXISTS idx_pt_track ON playlist_tracks(track_id);
CREATE TABLE IF NOT EXISTS play_history(
  track_id TEXT NOT NULL,
  playlist_id INTEGER NOT NULL DEFAULT 0,
  played_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
`)
	return err
}

// ---------- canonical keys & relative paths ----------

var (
	ytIDRe       = regexp.MustCompile(`^[A-Za-z0-9_-]{11}$`)
	ytPurlRe     = regexp.MustCompile(`(?:youtube\.com/watch\?(?:[^#\s]*&)?v=|youtu\.be/)([A-Za-z0-9_-]{11})`)
	ytCommentRe  = regexp.MustCompile(`(?m)^yt:([A-Za-z0-9_-]{11})\s*$`)
	uriSchemeRe  = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9+.-]*://`)
	driveLetter  = regexp.MustCompile(`^[A-Za-z]:`)
	unsafeIDChar = regexp.MustCompile(`[^A-Za-z0-9_-]`)
)

// ytIDFromTags recovers a YouTube video id from container tags written by yt-dlp / VibeMusic.
func ytIDFromTags(tags map[string]string) string {
	if m := ytCommentRe.FindStringSubmatch(tags["comment"]); len(m) == 2 {
		return m[1]
	}
	if m := ytPurlRe.FindStringSubmatch(tags["purl"]); len(m) == 2 {
		return m[1]
	}
	return ""
}

// ytIDFromName extracts the id from "<name> [<video_id>].<ext>" or "<video_id>.<ext>".
func ytIDFromName(rel string) string {
	if m := fileVidIDRe.FindStringSubmatch(rel); len(m) == 2 {
		return m[1]
	}
	base := path.Base(rel)
	base = strings.TrimSuffix(base, path.Ext(base))
	if ytIDRe.MatchString(base) && strings.HasPrefix(rel, "yt/") {
		return base
	}
	return ""
}

// localContentID derives "local:<sha256>" from file size plus the first and last 64 KB. It never
// depends on the file path.
func localContentID(osPath string, size int64) (string, error) {
	f, err := os.Open(osPath)
	if err != nil {
		return "", err
	}
	defer f.Close()
	const chunk = 64 * 1024
	h := sha256.New()
	var sz [8]byte
	binary.LittleEndian.PutUint64(sz[:], uint64(size))
	h.Write(sz[:])
	buf := make([]byte, chunk)
	n, err := io.ReadFull(f, buf)
	if err != nil && err != io.ErrUnexpectedEOF && err != io.EOF {
		return "", err
	}
	h.Write(buf[:n])
	if size > chunk {
		off := size - chunk
		if off < chunk {
			off = chunk
		}
		n, err = f.ReadAt(buf, off)
		if err != nil && err != io.EOF {
			return "", err
		}
		h.Write(buf[:n])
	}
	return "local:" + hex.EncodeToString(h.Sum(nil)), nil
}

// cleanRelPath validates and normalises a root-relative path: forward slashes, NFC, no drive
// letters, leading slashes, ".." segments or URI schemes.
func cleanRelPath(rel string) (string, bool) {
	rel = strings.ReplaceAll(rel, "\\", "/")
	rel = norm.NFC.String(rel)
	if rel == "" || strings.HasPrefix(rel, "/") || driveLetter.MatchString(rel) || uriSchemeRe.MatchString(rel) {
		return "", false
	}
	for _, seg := range strings.Split(rel, "/") {
		if seg == ".." {
			return "", false
		}
	}
	rel = path.Clean(rel)
	if rel == "." || rel == "" {
		return "", false
	}
	return rel, true
}

// coverKey makes a track id safe for use as a file name / URL segment.
func coverKey(id string) string { return unsafeIDChar.ReplaceAllString(id, "_") }

// ---------- local roots (device-local) ----------

func (s *Store) loadRoots() {
	rows, err := s.db.Query(`SELECT root_key, base_path FROM local_roots`)
	if err != nil {
		return
	}
	defer rows.Close()
	m := map[string]string{}
	for rows.Next() {
		var k, b string
		if rows.Scan(&k, &b) == nil {
			m[k] = b
		}
	}
	s.rmu.Lock()
	s.roots = m
	s.rmu.Unlock()
}

// EnsureRoot registers an OS directory as a root and returns its logical key. The first root is "main".
func (s *Store) EnsureRoot(base string) string {
	return s.ensureRootX(s.db, base)
}

func (s *Store) ensureRootX(x execer, base string) string {
	base = filepath.Clean(base)
	s.rmu.RLock()
	for k, b := range s.roots {
		if b == base {
			s.rmu.RUnlock()
			return k
		}
	}
	_, haveMain := s.roots["main"]
	s.rmu.RUnlock()
	key := "main"
	if haveMain {
		sum := sha1.Sum([]byte(base))
		key = "r" + hex.EncodeToString(sum[:])[:8]
	}
	if _, err := x.Exec(`INSERT OR IGNORE INTO local_roots(root_key, base_path) VALUES(?,?)`, key, base); err != nil {
		return key
	}
	s.rmu.Lock()
	s.roots[key] = base
	s.rmu.Unlock()
	return key
}

// SplitPath maps an absolute OS path to (root_key, rel_path) using the longest matching root.
func (s *Store) SplitPath(abs string) (rootKey, rel string, ok bool) {
	abs = filepath.Clean(abs)
	s.rmu.RLock()
	defer s.rmu.RUnlock()
	bestLen := -1
	for k, b := range s.roots {
		if strings.HasPrefix(abs, b+string(filepath.Separator)) && len(b) > bestLen {
			r, err := filepath.Rel(b, abs)
			if err != nil {
				continue
			}
			if c, good := cleanRelPath(filepath.ToSlash(r)); good {
				rootKey, rel, ok, bestLen = k, c, true, len(b)
			}
		}
	}
	return
}

// ResolvePath turns (root_key, rel_path) into an OS path; only call at file-open boundaries.
func (s *Store) ResolvePath(rootKey, rel string) string {
	s.rmu.RLock()
	base, ok := s.roots[rootKey]
	s.rmu.RUnlock()
	if !ok {
		return ""
	}
	return filepath.Join(base, filepath.FromSlash(rel))
}

// ---------- legacy migration ----------

// migrateLegacy converts the old integer-id / absolute-path schema in a single transaction.
func (s *Store) migrateLegacy() error {
	type old struct {
		id            int64
		path          string
		title, artist string
		album, format string
		dur           float64
		year, added   int64
		liked, plays  int64
		last, cover   int64
		bitrate       int64
		size, mtime   int64
	}
	rows, err := s.db.Query(`SELECT id,path,title,artist,album,duration,year,added_at,liked,play_count,last_played,has_cover,format,bitrate,size,mtime FROM tracks`)
	if err != nil {
		return err
	}
	var olds []old
	for rows.Next() {
		var o old
		if err := rows.Scan(&o.id, &o.path, &o.title, &o.artist, &o.album, &o.dur, &o.year, &o.added, &o.liked, &o.plays, &o.last, &o.cover, &o.format, &o.bitrate, &o.size, &o.mtime); err == nil {
			olds = append(olds, o)
		}
	}
	rows.Close()

	// candidate roots: configured dirs, plus the parent of any file not under one
	set := s.LoadSettings()
	dirs := append([]string(nil), set.MusicDirs...)
	if set.DownloadDir != "" {
		dirs = append(dirs, set.DownloadDir)
	}

	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, q := range []string{
		`DROP INDEX IF EXISTS idx_pt_track`,
		`ALTER TABLE tracks RENAME TO tracks_old`,
		`ALTER TABLE playlist_tracks RENAME TO playlist_tracks_old`,
		`ALTER TABLE play_history RENAME TO play_history_old`,
	} {
		if _, err := tx.Exec(q); err != nil {
			return err
		}
	}
	if err := s.createSchema(tx); err != nil {
		return err
	}
	for _, d := range dirs {
		s.ensureRootX(tx, d)
	}

	idMap := map[int64]string{}
	usedID := map[string]bool{}
	usedYT := map[string]bool{}
	covers := map[int64]string{}
	for _, o := range olds {
		abs := filepath.Clean(o.path)
		rootKey, rel, ok := s.SplitPath(abs)
		if !ok {
			rootKey = s.ensureRootX(tx, filepath.Dir(abs))
			rel, ok = cleanRelPath(filepath.Base(abs))
			if !ok {
				continue
			}
		}
		yt := ytIDFromName(rel)
		id := ""
		if yt != "" && !usedYT[yt] {
			id = "yt:" + yt
		} else {
			yt = ""
			if h, err := localContentID(abs, o.size); err == nil {
				id = h
			} else {
				sum := sha256.Sum256([]byte(fmt.Sprintf("%d|%s", o.size, rel)))
				id = "local:" + hex.EncodeToString(sum[:])
			}
		}
		if usedID[id] { // identical content/video already migrated: merge
			idMap[o.id] = id
			continue
		}
		usedID[id] = true
		if yt != "" {
			usedYT[yt] = true
		}
		idMap[o.id] = id
		covers[o.id] = id
		var ytv any
		if yt != "" {
			ytv = yt
		}
		if _, err := tx.Exec(`INSERT INTO tracks(id,yt_id,root_key,rel_path,title,artist,album,duration,year,added_at,liked,play_count,last_played,has_cover,format,bitrate,file_size,mtime_unix,updated_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
			id, ytv, rootKey, rel, o.title, o.artist, o.album, o.dur, o.year, o.added, o.liked, o.plays, o.last, o.cover, o.format, o.bitrate, o.size, o.mtime, time.Now().Unix()); err != nil {
			return err
		}
	}
	// playlists / history
	prow, err := tx.Query(`SELECT playlist_id, track_id, position FROM playlist_tracks_old ORDER BY playlist_id, position`)
	if err != nil {
		return err
	}
	type pt struct {
		pl  int64
		tid string
	}
	var pts []pt
	for prow.Next() {
		var pl, tid, pos int64
		if prow.Scan(&pl, &tid, &pos) == nil {
			if nid, ok := idMap[tid]; ok {
				pts = append(pts, pt{pl, nid})
			}
		}
	}
	prow.Close()
	counter := map[int64]int{}
	for _, p := range pts {
		if _, err := tx.Exec(`INSERT INTO playlist_tracks(playlist_id,track_id,position) VALUES(?,?,?)`, p.pl, p.tid, counter[p.pl]); err != nil {
			return err
		}
		counter[p.pl]++
	}
	hrow, err := tx.Query(`SELECT track_id, playlist_id, played_at FROM play_history_old`)
	if err != nil {
		return err
	}
	type ph struct {
		tid    string
		pl, at int64
	}
	var phs []ph
	for hrow.Next() {
		var tid, pl, at int64
		if hrow.Scan(&tid, &pl, &at) == nil {
			if nid, ok := idMap[tid]; ok {
				phs = append(phs, ph{nid, pl, at})
			}
		}
	}
	hrow.Close()
	for _, h := range phs {
		if _, err := tx.Exec(`INSERT INTO play_history(track_id,playlist_id,played_at) VALUES(?,?,?)`, h.tid, h.pl, h.at); err != nil {
			return err
		}
	}
	for _, q := range []string{`DROP TABLE playlist_tracks_old`, `DROP TABLE play_history_old`, `DROP TABLE tracks_old`} {
		if _, err := tx.Exec(q); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	// cover files were named by the old integer id
	for oldID, newID := range covers {
		from := filepath.Join(coverDir(), fmt.Sprintf("%d.jpg", oldID))
		_ = os.Rename(from, filepath.Join(coverDir(), coverKey(newID)+".jpg"))
	}
	return nil
}

// ----- settings -----

func (s *Store) LoadSettings() Settings {
	set := defaultSettings()
	var v string
	if err := s.db.QueryRow(`SELECT value FROM settings WHERE key='settings'`).Scan(&v); err == nil {
		_ = json.Unmarshal([]byte(v), &set)
	}
	if len(set.EQ) != 10 {
		set.EQ = make([]float64, 10)
	}
	if len(set.MusicDirs) == 0 {
		set.MusicDirs = defaultSettings().MusicDirs
	}
	if set.DownloadDir == "" {
		set.DownloadDir = set.MusicDirs[0]
	}
	if set.Repeat == "" {
		set.Repeat = "off"
	}
	if set.WindowWidth < 900 || set.WindowHeight < 600 {
		set.WindowWidth = 1500
		set.WindowHeight = 920
		set.WindowMaximised = true
	}
	if len(set.HomeOrder) == 0 {
		set.HomeOrder = append([]string(nil), defaultHomeOrder...)
		set.HomeHidden = []string{"discover"}
	} else {
		var withoutAll []string
		seen := map[string]bool{"allMusic": true}
		for _, k := range set.HomeOrder {
			if k != "allMusic" && !seen[k] {
				seen[k] = true
				withoutAll = append(withoutAll, k)
			}
		}
		for _, k := range defaultHomeOrder {
			if k != "allMusic" && !seen[k] {
				seen[k] = true
				withoutAll = append(withoutAll, k)
			}
		}
		set.HomeOrder = append(withoutAll, "allMusic")
	}
	if set.QuickPicksRows == "" {
		set.QuickPicksRows = "1"
	}
	return set
}

func (s *Store) NormalizeArtists() {
	rows, err := s.db.Query(`SELECT id, artist FROM tracks`)
	if err != nil {
		return
	}
	type pair struct {
		id     string
		artist string
	}
	var updates []pair
	for rows.Next() {
		var id string
		var a string
		if err := rows.Scan(&id, &a); err == nil {
			cleaned := cleanArtistString(a)
			if cleaned != a {
				updates = append(updates, pair{id, cleaned})
			}
		}
	}
	rows.Close()
	if len(updates) == 0 {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return
	}
	for _, u := range updates {
		_, _ = tx.Exec(`UPDATE tracks SET artist=? WHERE id=?`, u.artist, u.id)
	}
	_ = tx.Commit()
}

func (s *Store) SaveSettings(set Settings) error {
	b, _ := json.Marshal(set)
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`INSERT INTO settings(key,value) VALUES('settings',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value`, string(b))
	return err
}

// ----- tracks -----

const trackCols = `id,yt_id,root_key,rel_path,title,artist,album,duration,year,added_at,liked,play_count,last_played,has_cover,format,bitrate,file_size,mtime_unix`

func (s *Store) scanTrack(r interface{ Scan(...any) error }) (Track, error) {
	var t Track
	var liked, cover int
	var yt sql.NullString
	err := r.Scan(&t.ID, &yt, &t.RootKey, &t.RelPath, &t.Title, &t.Artist, &t.Album, &t.Duration, &t.Year, &t.AddedAt, &liked,
		&t.PlayCount, &t.LastPlay, &cover, &t.Format, &t.Bitrate, &t.Size, &t.MTime)
	t.YtID = yt.String
	t.Liked = liked != 0
	t.HasCover = cover != 0
	t.Path = s.ResolvePath(t.RootKey, t.RelPath)
	return t, err
}

func (s *Store) AllTracks() ([]Track, error) {
	rows, err := s.db.Query(`SELECT ` + trackCols + ` FROM tracks ORDER BY added_at DESC, id DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Track{}
	for rows.Next() {
		t, err := s.scanTrack(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// TrackByPath looks a track up by absolute OS path (translated to root_key + rel_path).
func (s *Store) TrackByPath(p string) (Track, bool) {
	root, rel, ok := s.SplitPath(p)
	if !ok {
		return Track{}, false
	}
	t, err := s.scanTrack(s.db.QueryRow(`SELECT `+trackCols+` FROM tracks WHERE root_key=? AND rel_path=?`, root, rel))
	return t, err == nil
}

// TrackByRel looks a track up by root_key + normalised rel_path.
func (s *Store) TrackByRel(root, rel string) (Track, bool) {
	t, err := s.scanTrack(s.db.QueryRow(`SELECT `+trackCols+` FROM tracks WHERE root_key=? AND rel_path=?`, root, rel))
	return t, err == nil
}

// TrackByID returns the track with the given canonical id.
func (s *Store) TrackByID(id string) (Track, bool) {
	t, err := s.scanTrack(s.db.QueryRow(`SELECT `+trackCols+` FROM tracks WHERE id=?`, id))
	return t, err == nil
}

// TrackByRelAnyRoot finds a track whose rel_path matches in any registered root.
func (s *Store) TrackByRelAnyRoot(rel string) (Track, bool) {
	t, err := s.scanTrack(s.db.QueryRow(`SELECT `+trackCols+` FROM tracks WHERE rel_path=? LIMIT 1`, rel))
	return t, err == nil
}

// UpsertTrack inserts or updates a track keyed by t.ID (with t.RootKey/t.RelPath set) and returns the
// stored id. If another live file already owns the same canonical id, the duplicate is ignored.
func (s *Store) UpsertTrack(t Track) (string, error) {
	rel, ok := cleanRelPath(t.RelPath)
	if !ok || t.ID == "" || t.RootKey == "" {
		return "", fmt.Errorf("invalid track key id=%q root=%q rel=%q", t.ID, t.RootKey, t.RelPath)
	}
	t.RelPath = rel
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().Unix()
	if t.AddedAt == 0 {
		t.AddedAt = now
	}
	cover := 0
	if t.HasCover {
		cover = 1
	}
	var yt any
	if t.YtID != "" {
		yt = t.YtID
	}

	// same canonical id already stored at a different location that still exists -> duplicate file
	var oRoot, oRel string
	if err := s.db.QueryRow(`SELECT root_key, rel_path FROM tracks WHERE id=?`, t.ID).Scan(&oRoot, &oRel); err == nil {
		if (oRoot != t.RootKey || oRel != t.RelPath) && fileExists(s.ResolvePath(oRoot, oRel)) {
			return t.ID, nil
		}
	}

	tx, err := s.db.Begin()
	if err != nil {
		return "", err
	}
	defer tx.Rollback()

	// a different id previously lived at this path (file content / tags changed): carry its state over
	var prevID string
	var pAdded, pLiked, pPlays, pLast int64
	err = tx.QueryRow(`SELECT id,added_at,liked,play_count,last_played FROM tracks WHERE root_key=? AND rel_path=? AND id<>?`,
		t.RootKey, t.RelPath, t.ID).Scan(&prevID, &pAdded, &pLiked, &pPlays, &pLast)
	liked, plays, last := int64(0), int64(0), int64(0)
	if err == nil {
		if _, err := tx.Exec(`UPDATE tracks SET rel_path=? WHERE id=?`, "//moved:"+prevID, prevID); err != nil {
			return "", err
		}
		t.AddedAt, liked, plays, last = pAdded, pLiked, pPlays, pLast
	}

	if _, err := tx.Exec(`INSERT INTO tracks(id,yt_id,root_key,rel_path,title,artist,album,duration,year,added_at,liked,play_count,last_played,has_cover,format,bitrate,file_size,mtime_unix,updated_at)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(id) DO UPDATE SET yt_id=excluded.yt_id, root_key=excluded.root_key, rel_path=excluded.rel_path,
  title=excluded.title, artist=excluded.artist, album=excluded.album, duration=excluded.duration, year=excluded.year,
  has_cover=excluded.has_cover, format=excluded.format, bitrate=excluded.bitrate, file_size=excluded.file_size,
  mtime_unix=excluded.mtime_unix, updated_at=excluded.updated_at`,
		t.ID, yt, t.RootKey, t.RelPath, t.Title, t.Artist, t.Album, t.Duration, t.Year, t.AddedAt, liked, plays, last,
		cover, t.Format, t.Bitrate, t.Size, t.MTime, now); err != nil {
		return "", err
	}
	if prevID != "" {
		if _, err := tx.Exec(`UPDATE playlist_tracks SET track_id=? WHERE track_id=?`, t.ID, prevID); err != nil {
			return "", err
		}
		_, _ = tx.Exec(`UPDATE play_history SET track_id=? WHERE track_id=?`, t.ID, prevID)
		if _, err := tx.Exec(`DELETE FROM tracks WHERE id=?`, prevID); err != nil {
			return "", err
		}
	}
	if err := tx.Commit(); err != nil {
		return "", err
	}
	return t.ID, nil
}

func (s *Store) DeleteTracks(ids []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	for _, id := range ids {
		if _, err := tx.Exec(`DELETE FROM tracks WHERE id=?`, id); err != nil {
			tx.Rollback()
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	s.compactAll()
	return nil
}

func (s *Store) SetLiked(id string, liked bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := 0
	if liked {
		v = 1
	}
	_, err := s.db.Exec(`UPDATE tracks SET liked=? WHERE id=?`, v, id)
	return err
}

func (s *Store) UpdateTrackAlbum(id, album string) error {
	album = strings.TrimSpace(album)
	if album == "" {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`UPDATE tracks SET album=? WHERE id=?`, album, id)
	return err
}

func (s *Store) LoadSyncedYtCollections() []string {
	var raw string
	if err := s.db.QueryRow(`SELECT value FROM settings WHERE key='synced_yt_collections'`).Scan(&raw); err != nil || strings.TrimSpace(raw) == "" {
		return []string{}
	}
	var ids []string
	if err := json.Unmarshal([]byte(raw), &ids); err != nil {
		return []string{}
	}
	seen := map[string]bool{}
	out := make([]string, 0, len(ids))
	for _, id := range ids {
		id = strings.TrimSpace(id)
		if id != "" && !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}

func (s *Store) SaveSyncedYtCollections(ids []string) error {
	seen := map[string]bool{}
	clean := make([]string, 0, len(ids))
	for _, id := range ids {
		id = strings.TrimSpace(id)
		if id != "" && !seen[id] {
			seen[id] = true
			clean = append(clean, id)
		}
	}
	b, _ := json.Marshal(clean)
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`INSERT INTO settings(key,value) VALUES('synced_yt_collections',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value`, string(b))
	return err
}

func (s *Store) loadRecommendedHistoryLocked() []string {
	var raw string
	if err := s.db.QueryRow(`SELECT value FROM settings WHERE key='recommended_discover_history'`).Scan(&raw); err != nil || strings.TrimSpace(raw) == "" {
		return []string{}
	}
	var keys []string
	if err := json.Unmarshal([]byte(raw), &keys); err != nil {
		return []string{}
	}
	seen := map[string]bool{}
	out := make([]string, 0, len(keys))
	for _, k := range keys {
		k = strings.TrimSpace(k)
		if k != "" && !seen[k] {
			seen[k] = true
			out = append(out, k)
		}
	}
	return out
}

func (s *Store) LoadRecommendedHistory() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.loadRecommendedHistoryLocked()
}

func (s *Store) AddRecommendedHistory(keys []string) error {
	if len(keys) == 0 {
		return nil
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	existing := s.loadRecommendedHistoryLocked()
	seen := make(map[string]bool, len(existing)+len(keys))
	merged := make([]string, 0, len(existing)+len(keys))
	for _, k := range existing {
		if !seen[k] {
			seen[k] = true
			merged = append(merged, k)
		}
	}
	added := false
	for _, k := range keys {
		k = strings.TrimSpace(k)
		if k != "" && !seen[k] {
			seen[k] = true
			merged = append(merged, k)
			added = true
		}
	}
	if !added {
		return nil
	}
	const maxHistory = 5000
	if len(merged) > maxHistory {
		merged = merged[len(merged)-maxHistory:]
	}
	b, _ := json.Marshal(merged)
	_, err := s.db.Exec(`INSERT INTO settings(key,value) VALUES('recommended_discover_history',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value`, string(b))
	return err
}

func (s *Store) RecordPlay(trackID string, playlistID int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().Unix()
	_, _ = s.db.Exec(`UPDATE tracks SET play_count=play_count+1, last_played=? WHERE id=?`, now, trackID)
	_, _ = s.db.Exec(`INSERT INTO play_history(track_id,playlist_id,played_at) VALUES(?,?,?)`, trackID, playlistID, now)
}

// ----- playlists -----

func (s *Store) CreatePlaylist(name string) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	name = strings.TrimSpace(name)
	if name == "" {
		name = "New playlist"
	}
	res, err := s.db.Exec(`INSERT INTO playlists(name,created_at) VALUES(?,?)`, name, time.Now().Unix())
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

func (s *Store) PlaylistByName(name string) (int64, bool) {
	var id int64
	err := s.db.QueryRow(`SELECT id FROM playlists WHERE name=? COLLATE NOCASE LIMIT 1`, name).Scan(&id)
	return id, err == nil
}

func (s *Store) GetOrCreatePlaylist(name string) (int64, error) {
	name = strings.TrimSpace(name)
	if name == "" {
		name = "New playlist"
	}
	if id, ok := s.PlaylistByName(name); ok {
		return id, nil
	}
	return s.CreatePlaylist(name)
}

func (s *Store) RenamePlaylist(id int64, name string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`UPDATE playlists SET name=? WHERE id=?`, strings.TrimSpace(name), id)
	return err
}

func (s *Store) DeletePlaylist(id int64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`DELETE FROM playlists WHERE id=?`, id)
	return err
}

func (s *Store) TouchPlaylist(id int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, _ = s.db.Exec(`UPDATE playlists SET last_played=? WHERE id=?`, time.Now().Unix(), id)
}

func (s *Store) Playlists() ([]Playlist, error) {
	rows, err := s.db.Query(`SELECT p.id,p.name,p.created_at,p.last_played,
 (SELECT COUNT(*) FROM playlist_tracks WHERE playlist_id=p.id),
 COALESCE((SELECT SUM(t.duration) FROM playlist_tracks pt JOIN tracks t ON t.id=pt.track_id WHERE pt.playlist_id=p.id),0)
 FROM playlists p ORDER BY p.created_at DESC, p.id DESC`)
	if err != nil {
		return nil, err
	}
	out := []Playlist{}
	for rows.Next() {
		var p Playlist
		if err := rows.Scan(&p.ID, &p.Name, &p.CreatedAt, &p.LastPlayed, &p.TrackCount, &p.Duration); err != nil {
			rows.Close()
			return nil, err
		}
		out = append(out, p)
	}
	rows.Close()
	for i := range out {
		r2, err := s.db.Query(`SELECT t.id FROM playlist_tracks pt JOIN tracks t ON t.id=pt.track_id
 WHERE pt.playlist_id=? AND t.has_cover=1 ORDER BY pt.position LIMIT 4`, out[i].ID)
		if err != nil {
			continue
		}
		out[i].CoverTracks = []string{}
		for r2.Next() {
			var id string
			_ = r2.Scan(&id)
			out[i].CoverTracks = append(out[i].CoverTracks, id)
		}
		r2.Close()
	}
	return out, nil
}

func (s *Store) PlaylistTrackIDs(id int64) ([]string, error) {
	rows, err := s.db.Query(`SELECT track_id FROM playlist_tracks WHERE playlist_id=? ORDER BY position`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var t string
		if err := rows.Scan(&t); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func (s *Store) AllPlaylistTracks() map[int64][]string {
	rows, err := s.db.Query(`SELECT playlist_id, track_id FROM playlist_tracks ORDER BY playlist_id, position`)
	if err != nil {
		return map[int64][]string{}
	}
	defer rows.Close()
	out := map[int64][]string{}
	for rows.Next() {
		var pid int64
		var tid string
		if err := rows.Scan(&pid, &tid); err == nil {
			out[pid] = append(out[pid], tid)
		}
	}
	return out
}

func (s *Store) SetPlaylistTracks(id int64, ids []string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM playlist_tracks WHERE playlist_id=?`, id); err != nil {
		tx.Rollback()
		return err
	}
	for i, t := range ids {
		if _, err := tx.Exec(`INSERT INTO playlist_tracks(playlist_id,track_id,position) VALUES(?,?,?)`, id, t, i); err != nil {
			tx.Rollback()
			return fmt.Errorf("insert: %w", err)
		}
	}
	return tx.Commit()
}

func (s *Store) AddToPlaylist(id int64, ids []string) (int, error) {
	cur, err := s.PlaylistTrackIDs(id)
	if err != nil {
		return 0, err
	}
	have := map[string]bool{}
	for _, t := range cur {
		have[t] = true
	}
	added := 0
	for _, t := range ids {
		if !have[t] {
			have[t] = true
			cur = append(cur, t)
			added++
		}
	}
	if added == 0 {
		return 0, nil
	}
	return added, s.SetPlaylistTracks(id, cur)
}

// compactAll closes position gaps left after tracks are deleted.
func (s *Store) compactAll() {
	rows, err := s.db.Query(`SELECT id FROM playlists`)
	if err != nil {
		return
	}
	var ids []int64
	for rows.Next() {
		var id int64
		_ = rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	for _, id := range ids {
		var n, mx int
		_ = s.db.QueryRow(`SELECT COUNT(*), COALESCE(MAX(position),-1) FROM playlist_tracks WHERE playlist_id=?`, id).Scan(&n, &mx)
		if n-1 != mx {
			tr, _ := s.PlaylistTrackIDs(id)
			tx, err := s.db.Begin()
			if err != nil {
				continue
			}
			_, _ = tx.Exec(`DELETE FROM playlist_tracks WHERE playlist_id=?`, id)
			for i, t := range tr {
				_, _ = tx.Exec(`INSERT INTO playlist_tracks(playlist_id,track_id,position) VALUES(?,?,?)`, id, t, i)
			}
			_ = tx.Commit()
		}
	}
}
