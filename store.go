package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"

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
	ID        int64   `json:"id"`
	Path      string  `json:"path"`
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
	CoverTracks []int64 `json:"coverTracks"`
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
	HomeOrder       []string  `json:"homeOrder"`
	HomeHidden      []string  `json:"homeHidden"`
	QuickPicksRows  string    `json:"quickPicksRows"` // "1" | "2" | "3" | "auto"
	CookieBrowser   string    `json:"cookieBrowser"`  // "" | "firefox" | "brave" | "chrome" | "chromium" | "edge" | "vivaldi"
}

var defaultHomeOrder = []string{"recentPlaylists", "topTracks", "quickPicks", "discover", "recentlyAdded", "allMusic"}

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
}

func OpenStore() (*Store, error) {
	p := filepath.Join(dataDir(), "library.db")
	db, err := sql.Open("sqlite", "file:"+p+"?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=foreign_keys(1)")
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		return nil, err
	}
	return s, nil
}

func (s *Store) migrate() error {
	_, err := s.db.Exec(`
CREATE TABLE IF NOT EXISTS tracks(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  path TEXT UNIQUE NOT NULL,
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
  size INTEGER NOT NULL DEFAULT 0,
  mtime INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS playlists(
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  created_at INTEGER NOT NULL DEFAULT 0,
  last_played INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS playlist_tracks(
  playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
  track_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,
  position INTEGER NOT NULL,
  PRIMARY KEY(playlist_id, position)
);
CREATE INDEX IF NOT EXISTS idx_pt_track ON playlist_tracks(track_id);
CREATE TABLE IF NOT EXISTS play_history(
  track_id INTEGER NOT NULL,
  playlist_id INTEGER NOT NULL DEFAULT 0,
  played_at INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
`)
	return err
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
		seen := map[string]bool{}
		for _, k := range set.HomeOrder {
			seen[k] = true
		}
		for _, k := range defaultHomeOrder {
			if !seen[k] {
				set.HomeOrder = append(set.HomeOrder, k)
			}
		}
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
		id     int64
		artist string
	}
	var updates []pair
	for rows.Next() {
		var id int64
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

const trackCols = `id,path,title,artist,album,duration,year,added_at,liked,play_count,last_played,has_cover,format,bitrate,size,mtime`

func scanTrack(r interface{ Scan(...any) error }) (Track, error) {
	var t Track
	var liked, cover int
	err := r.Scan(&t.ID, &t.Path, &t.Title, &t.Artist, &t.Album, &t.Duration, &t.Year, &t.AddedAt, &liked,
		&t.PlayCount, &t.LastPlay, &cover, &t.Format, &t.Bitrate, &t.Size, &t.MTime)
	t.Liked = liked != 0
	t.HasCover = cover != 0
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
		t, err := scanTrack(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func (s *Store) TrackByPath(p string) (Track, bool) {
	t, err := scanTrack(s.db.QueryRow(`SELECT `+trackCols+` FROM tracks WHERE path=?`, p))
	return t, err == nil
}

// UpsertTrack inserts or updates a track by path and returns its id.
func (s *Store) UpsertTrack(t Track) (int64, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if t.AddedAt == 0 {
		t.AddedAt = time.Now().Unix()
	}
	cover := 0
	if t.HasCover {
		cover = 1
	}
	_, err := s.db.Exec(`INSERT INTO tracks(path,title,artist,album,duration,year,added_at,has_cover,format,bitrate,size,mtime)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
ON CONFLICT(path) DO UPDATE SET title=excluded.title, artist=excluded.artist, album=excluded.album,
 duration=excluded.duration, year=excluded.year, has_cover=excluded.has_cover, format=excluded.format,
 bitrate=excluded.bitrate, size=excluded.size, mtime=excluded.mtime`,
		t.Path, t.Title, t.Artist, t.Album, t.Duration, t.Year, t.AddedAt, cover, t.Format, t.Bitrate, t.Size, t.MTime)
	if err != nil {
		return 0, err
	}
	var id int64
	err = s.db.QueryRow(`SELECT id FROM tracks WHERE path=?`, t.Path).Scan(&id)
	return id, err
}

func (s *Store) DeleteTracks(ids []int64) error {
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

func (s *Store) SetLiked(id int64, liked bool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	v := 0
	if liked {
		v = 1
	}
	_, err := s.db.Exec(`UPDATE tracks SET liked=? WHERE id=?`, v, id)
	return err
}

func (s *Store) RecordPlay(trackID, playlistID int64) {
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
		out[i].CoverTracks = []int64{}
		for r2.Next() {
			var id int64
			_ = r2.Scan(&id)
			out[i].CoverTracks = append(out[i].CoverTracks, id)
		}
		r2.Close()
	}
	return out, nil
}

func (s *Store) PlaylistTrackIDs(id int64) ([]int64, error) {
	rows, err := s.db.Query(`SELECT track_id FROM playlist_tracks WHERE playlist_id=? ORDER BY position`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []int64{}
	for rows.Next() {
		var t int64
		if err := rows.Scan(&t); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

func (s *Store) AllPlaylistTracks() map[int64][]int64 {
	rows, err := s.db.Query(`SELECT playlist_id, track_id FROM playlist_tracks ORDER BY playlist_id, position`)
	if err != nil {
		return map[int64][]int64{}
	}
	defer rows.Close()
	out := map[int64][]int64{}
	for rows.Next() {
		var pid, tid int64
		if err := rows.Scan(&pid, &tid); err == nil {
			out[pid] = append(out[pid], tid)
		}
	}
	return out
}

func (s *Store) SetPlaylistTracks(id int64, ids []int64) error {
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

func (s *Store) AddToPlaylist(id int64, ids []int64) (int, error) {
	cur, err := s.PlaylistTrackIDs(id)
	if err != nil {
		return 0, err
	}
	have := map[int64]bool{}
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
