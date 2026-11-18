package main

import (
	"database/sql"
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
