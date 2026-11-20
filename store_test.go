package main

import (
	"database/sql"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestCleanRelPath(t *testing.T) {
	bad := []string{"", "/abs/x.mp3", "C:\\Music\\x.mp3", "C:/x.mp3", "../x.mp3", "a/../../x.mp3", "content://media/1", "file:///x.mp3"}
	for _, b := range bad {
		if _, ok := cleanRelPath(b); ok {
			t.Errorf("cleanRelPath(%q) should be rejected", b)
		}
	}
	if got, ok := cleanRelPath("Daft Punk\\Discovery/01.flac"); !ok || got != "Daft Punk/Discovery/01.flac" {
		t.Errorf("got %q %v", got, ok)
	}
}

func TestLegacyMigration(t *testing.T) {
	data := t.TempDir()
	music := filepath.Join(data, "Music")
	_ = os.MkdirAll(music, 0o755)
	t.Setenv("XDG_DATA_HOME", data)
	yt := filepath.Join(music, "Song [dQw4w9WgXcQ].m4a")
	loc := filepath.Join(music, "A", "b.mp3")
	_ = os.MkdirAll(filepath.Dir(loc), 0o755)
	_ = os.WriteFile(yt, []byte("yt"), 0o644)
	_ = os.WriteFile(loc, []byte("local audio"), 0o644)

	db, err := sql.Open("sqlite", "file:"+filepath.Join(dataDir(), "library.db"))
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`
CREATE TABLE tracks(id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT UNIQUE NOT NULL, title TEXT NOT NULL DEFAULT '', artist TEXT NOT NULL DEFAULT '', album TEXT NOT NULL DEFAULT '', duration REAL NOT NULL DEFAULT 0, year INTEGER NOT NULL DEFAULT 0, added_at INTEGER NOT NULL DEFAULT 0, liked INTEGER NOT NULL DEFAULT 0, play_count INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0, has_cover INTEGER NOT NULL DEFAULT 0, format TEXT NOT NULL DEFAULT '', bitrate INTEGER NOT NULL DEFAULT 0, size INTEGER NOT NULL DEFAULT 0, mtime INTEGER NOT NULL DEFAULT 0);
CREATE TABLE playlists(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, created_at INTEGER NOT NULL DEFAULT 0, last_played INTEGER NOT NULL DEFAULT 0);
CREATE TABLE playlist_tracks(playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE, track_id INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE, position INTEGER NOT NULL, PRIMARY KEY(playlist_id, position));
CREATE INDEX idx_pt_track ON playlist_tracks(track_id);
CREATE TABLE play_history(track_id INTEGER NOT NULL, playlist_id INTEGER NOT NULL DEFAULT 0, played_at INTEGER NOT NULL);
CREATE TABLE settings(key TEXT PRIMARY KEY, value TEXT NOT NULL);
INSERT INTO settings VALUES('settings','{"musicDirs":["` + music + `"],"downloadDir":"` + music + `"}');
INSERT INTO tracks(id,path,title,size) VALUES(7,'` + yt + `','Song',2),(9,'` + loc + `','B',11);
INSERT INTO playlists(id,name) VALUES(1,'p');
INSERT INTO playlist_tracks VALUES(1,9,0),(1,7,1);
INSERT INTO play_history VALUES(7,1,100);`)
	if err != nil {
		t.Fatal(err)
	}
	db.Close()

	s, err := OpenStore()
	if err != nil {
		t.Fatal(err)
	}
	all, err := s.AllTracks()
	if err != nil || len(all) != 2 {
		t.Fatalf("tracks=%v err=%v", all, err)
	}
	byTitle := map[string]Track{}
	for _, tr := range all {
		byTitle[tr.Title] = tr
		if strings.Contains(tr.RelPath, music) || filepath.IsAbs(tr.RelPath) {
			t.Errorf("absolute rel_path %q", tr.RelPath)
		}
		if tr.Path == "" || !fileExists(tr.Path) {
			t.Errorf("unresolved path for %q", tr.Title)
		}
	}
	if y := byTitle["Song"]; y.ID != "yt:dQw4w9WgXcQ" || y.YtID != "dQw4w9WgXcQ" || y.RootKey != "main" {
		t.Errorf("yt track: %+v", y)
	}
	if l := byTitle["B"]; !strings.HasPrefix(l.ID, "local:") || l.YtID != "" || l.RelPath != "A/b.mp3" {
		t.Errorf("local track: %+v", l)
	}
	ids, _ := s.PlaylistTrackIDs(1)
	if len(ids) != 2 || ids[0] != byTitle["B"].ID || ids[1] != "yt:dQw4w9WgXcQ" {
		t.Errorf("playlist ids %v", ids)
	}
	var n int
	_ = s.db.QueryRow(`SELECT COUNT(*) FROM play_history WHERE track_id='yt:dQw4w9WgXcQ'`).Scan(&n)
	if n != 1 {
		t.Errorf("history not migrated")
	}
	// reopening is a no-op
	s.db.Close()
	if _, err := OpenStore(); err != nil {
		t.Fatal(err)
	}
}

func TestRecommendedHistoryCap80(t *testing.T) {
	data := t.TempDir()
	t.Setenv("XDG_DATA_HOME", data)

	s, err := OpenStore()
	if err != nil {
		t.Fatal(err)
	}
	defer s.db.Close()

	var batch []string
	for i := 1; i <= 95; i++ {
		batch = append(batch, fmt.Sprintf("vid:vid%03d", i), fmt.Sprintf("song:title%03d", i))
	}
	if err := s.AddRecommendedHistory(batch); err != nil {
		t.Fatal(err)
	}

	hist := s.LoadRecommendedHistory()
	vids := 0
	has := map[string]bool{}
	for _, k := range hist {
		has[k] = true
		if strings.HasPrefix(k, "vid:") {
			vids++
		}
	}
	if vids != maxRecommendedHistorySongs {
		t.Fatalf("expected %d video entries, got %d (total keys=%d)", maxRecommendedHistorySongs, vids, len(hist))
	}
	if has["vid:vid001"] || has["song:title001"] || has["vid:vid015"] || has["song:title015"] {
		t.Fatalf("expected oldest 15 songs to be evicted, got hist[0..3]=%v", hist[:4])
	}
	if !has["vid:vid016"] || !has["song:title016"] || !has["vid:vid095"] || !has["song:title095"] {
		t.Fatalf("expected latest 80 songs (16..95) to remain in history")
	}
}

func TestResizedCoverPath(t *testing.T) {
	data := t.TempDir()
	t.Setenv("XDG_DATA_HOME", data)

	id := "yt:thumbTest01"
	srcPath := coverPath(id)
	img := image.NewRGBA(image.Rect(0, 0, 600, 300))
	for y := 0; y < 300; y++ {
		for x := 0; x < 600; x++ {
			img.SetRGBA(x, y, color.RGBA{R: uint8(x % 256), G: uint8(y % 256), B: 180, A: 255})
		}
	}
	f, err := os.Create(srcPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := jpeg.Encode(f, img, &jpeg.Options{Quality: 90}); err != nil {
		f.Close()
		t.Fatal(err)
	}
	f.Close()

	if got := resizedCoverPath(id, 0); got != srcPath {
		t.Fatalf("expected original path for dim=0, got %q", got)
	}
	if got := normalizeThumbSize("96"); got != 128 {
		t.Fatalf("expected 128 bucket for 96, got %d", got)
	}
	if got := normalizeThumbSize("240"); got != 300 {
		t.Fatalf("expected 300 bucket for 240, got %d", got)
	}
	if got := normalizeThumbSize("360"); got != 0 {
		t.Fatalf("expected 0 (full cover) for 360, got %d", got)
	}

	thumbPath := resizedCoverPath(id, 128)
	if thumbPath == "" || thumbPath == srcPath || !strings.Contains(thumbPath, filepath.Join("thumbs", "v2", "128")) {
		t.Fatalf("expected v2 cached thumb path distinct from %q, got %q", srcPath, thumbPath)
	}
	tf, err := os.Open(thumbPath)
	if err != nil {
		t.Fatal(err)
	}
	cfg, _, err := image.DecodeConfig(tf)
	tf.Close()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Width != 128 || cfg.Height != 64 {
		t.Fatalf("expected 128x64 resized thumb, got %dx%d", cfg.Width, cfg.Height)
	}

	// Second call hits cache and returns same path
	if again := resizedCoverPath(id, 128); again != thumbPath {
		t.Fatalf("expected cached path %q on reuse, got %q", thumbPath, again)
	}
}

func TestBestDiscoverThumbURL(t *testing.T) {
	got := bestDiscoverThumbURL("abc12345678", []ytFlatThumb{
		{URL: "https://lh3.googleusercontent.com/xyz=w120-h120-l90-rj", Width: 120, Height: 120},
	})
	if got != "https://lh3.googleusercontent.com/xyz=w600-h600-l90-rj" {
		t.Fatalf("expected upgraded googleusercontent url, got %q", got)
	}

	gotLarge := bestDiscoverThumbURL("abc12345678", []ytFlatThumb{
		{URL: "https://i.ytimg.com/vi/abc12345678/hqdefault.jpg", Width: 480, Height: 360},
		{URL: "https://i.ytimg.com/vi/abc12345678/sddefault.jpg", Width: 640, Height: 480},
	})
	if gotLarge != "https://i.ytimg.com/vi/abc12345678/sddefault.jpg" {
		t.Fatalf("expected largest >=640 thumb, got %q", gotLarge)
	}

	gotFallback := bestDiscoverThumbURL("abc12345678", nil)
	if gotFallback != "https://i.ytimg.com/vi/abc12345678/maxresdefault.jpg" {
		t.Fatalf("expected maxresdefault fallback, got %q", gotFallback)
	}
}

func TestPlaylistsBatchedCoversAndNormalizeArtists(t *testing.T) {
	data := t.TempDir()
	music := filepath.Join(data, "Music")
	_ = os.MkdirAll(music, 0o755)
	t.Setenv("XDG_DATA_HOME", data)

	s, err := OpenStore()
	if err != nil {
		t.Fatal(err)
	}
	defer s.db.Close()

	rk := s.EnsureRoot(music)
	for i := 1; i <= 6; i++ {
		tid := fmt.Sprintf("yt:track0000%02d", i)
		tr := Track{
			ID:       tid,
			YtID:     fmt.Sprintf("track0000%02d", i),
			RootKey:  rk,
			RelPath:  fmt.Sprintf("song%d.m4a", i),
			Title:    fmt.Sprintf("Song %d", i),
			Artist:   "Artist A feat. Artist B - Topic",
			HasCover: i != 2, // track 2 has no cover
			Duration: 120,
		}
		if _, err := s.UpsertTrack(tr); err != nil {
			t.Fatal(err)
		}
	}
	// Bypass clean artist during raw insert to verify NormalizeArtists works once and sets flag.
	if _, err := s.db.Exec(`UPDATE tracks SET artist='DJ Test feat. Guest - Topic' WHERE id='yt:track000001'`); err != nil {
		t.Fatal(err)
	}

	s.NormalizeArtists()
	var normFlag string
	if err := s.db.QueryRow(`SELECT value FROM settings WHERE key='artists_normalized_v1'`).Scan(&normFlag); err != nil || normFlag != "1" {
		t.Fatalf("expected artists_normalized_v1=1, got %q err=%v", normFlag, err)
	}
	tr1, ok := s.TrackByID("yt:track000001")
	if !ok || tr1.Artist != "DJ Test, Guest" {
		t.Fatalf("expected normalized artist 'DJ Test, Guest', got %q (ok=%v)", tr1.Artist, ok)
	}
	// Subsequent calls should be a no-op once flag is set.
	if _, err := s.db.Exec(`UPDATE tracks SET artist='Raw - Topic' WHERE id='yt:track000001'`); err != nil {
		t.Fatal(err)
	}
	s.NormalizeArtists()
	tr1After, _ := s.TrackByID("yt:track000001")
	if tr1After.Artist != "Raw - Topic" {
		t.Fatalf("expected NormalizeArtists to skip on second run, got %q", tr1After.Artist)
	}

	pl1, err := s.CreatePlaylist("With Covers")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.AddToPlaylist(pl1, []string{
		"yt:track000001", "yt:track000002", "yt:track000003", "yt:track000004", "yt:track000005", "yt:track000006",
	}); err != nil {
		t.Fatal(err)
	}
	pl2, err := s.CreatePlaylist("Empty Playlist")
	if err != nil {
		t.Fatal(err)
	}

	pls, err := s.Playlists()
	if err != nil {
		t.Fatal(err)
	}
	if len(pls) != 2 {
		t.Fatalf("expected 2 playlists, got %d", len(pls))
	}
	byID := map[int64]Playlist{}
	for _, p := range pls {
		byID[p.ID] = p
	}
	gotCovers := byID[pl1].CoverTracks
	wantCovers := []string{"yt:track000001", "yt:track000003", "yt:track000004", "yt:track000005"}
	if len(gotCovers) != len(wantCovers) {
		t.Fatalf("expected %v covers, got %v", wantCovers, gotCovers)
	}
	for i := range wantCovers {
		if gotCovers[i] != wantCovers[i] {
			t.Fatalf("cover[%d] = %q, want %q", i, gotCovers[i], wantCovers[i])
		}
	}
	if len(byID[pl2].CoverTracks) != 0 {
		t.Fatalf("expected 0 covers for empty playlist, got %v", byID[pl2].CoverTracks)
	}
}
