package main

import (
	"database/sql"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"net/http"
	"net/http/httptest"
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
	if got := normalizeThumbSize("360"); got != 480 {
		t.Fatalf("expected 480 bucket for 360, got %d", got)
	}
	if got := normalizeThumbSize("600"); got != 0 {
		t.Fatalf("expected 0 (full cover) for 600, got %d", got)
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

func TestRemoveFromPlaylist(t *testing.T) {
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
	ids := []string{"yt:remTrack001", "yt:remTrack002", "yt:remTrack003", "yt:remTrack004"}
	for i, tid := range ids {
		tr := Track{
			ID:       tid,
			YtID:     strings.TrimPrefix(tid, "yt:"),
			RootKey:  rk,
			RelPath:  fmt.Sprintf("track%d.m4a", i+1),
			Title:    fmt.Sprintf("Track %d", i+1),
			Artist:   "Tester",
			Duration: 100,
		}
		if _, err := s.UpsertTrack(tr); err != nil {
			t.Fatal(err)
		}
	}

	pl, err := s.CreatePlaylist("Remove Test")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.AddToPlaylist(pl, ids); err != nil {
		t.Fatal(err)
	}

	removed, err := s.RemoveFromPlaylist(pl, []string{"yt:remTrack002"})
	if err != nil || removed != 1 {
		t.Fatalf("expected 1 removed, got %d err=%v", removed, err)
	}
	got, err := s.PlaylistTrackIDs(pl)
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"yt:remTrack001", "yt:remTrack003", "yt:remTrack004"}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("after single removal got %v, want %v", got, want)
	}
	var maxPos int
	if err := s.db.QueryRow(`SELECT COALESCE(MAX(position),-1) FROM playlist_tracks WHERE playlist_id=?`, pl).Scan(&maxPos); err != nil || maxPos != 2 {
		t.Fatalf("expected compacted max position 2, got %d err=%v", maxPos, err)
	}

	removed, err = s.RemoveFromPlaylist(pl, []string{"yt:remTrack001", "yt:remTrack004", "yt:missing0000"})
	if err != nil || removed != 2 {
		t.Fatalf("expected 2 removed, got %d err=%v", removed, err)
	}
	got, err = s.PlaylistTrackIDs(pl)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 1 || got[0] != "yt:remTrack003" {
		t.Fatalf("after multi removal got %v, want [yt:remTrack003]", got)
	}

	removed, err = s.RemoveFromPlaylist(pl, []string{"yt:missing0000"})
	if err != nil || removed != 0 {
		t.Fatalf("expected 0 removed for absent track, got %d err=%v", removed, err)
	}
	got, _ = s.PlaylistTrackIDs(pl)
	if len(got) != 1 || got[0] != "yt:remTrack003" {
		t.Fatalf("expected unchanged playlist [yt:remTrack003], got %v", got)
	}
}

func TestQueueStatePersistenceAndRestore(t *testing.T) {
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
	tracks := map[string]Track{
		"yt:qTrack00001": {ID: "yt:qTrack00001", YtID: "qTrack00001", RootKey: rk, RelPath: "1.m4a", Title: "One", Artist: "A", Duration: 180},
		"yt:qTrack00002": {ID: "yt:qTrack00002", YtID: "qTrack00002", RootKey: rk, RelPath: "2.m4a", Title: "Two", Artist: "A", Duration: 210},
		"yt:qTrack00003": {ID: "yt:qTrack00003", YtID: "qTrack00003", RootKey: rk, RelPath: "3.m4a", Title: "Three", Artist: "A", Duration: 240},
	}
	for _, tr := range tracks {
		if _, err := s.UpsertTrack(tr); err != nil {
			t.Fatal(err)
		}
	}
	plID, err := s.CreatePlaylist("Saved Queue Playlist")
	if err != nil {
		t.Fatal(err)
	}

	// 1. Save queue state via Store directly (including one track that will be missing on restore)
	saved := SavedQueueState{
		Items: []QueueEntry{
			{QID: 10, TID: "yt:deleted0000"},
			{QID: 11, TID: "yt:qTrack00002"},
			{QID: 12, TID: "yt:qTrack00001"},
			{QID: 13, TID: "yt:qTrack00003"},
		},
		Orig:       []int64{12, 11, 10, 13},
		NextQID:    13,
		Index:      2, // points to yt:qTrack00001 (should become index 1 after deleted item is filtered)
		Position:   73.5,
		PlaylistID: plID,
		Shuffle:    true,
	}
	if err := s.SaveQueueState(saved); err != nil {
		t.Fatal(err)
	}
	loaded, ok := s.LoadQueueState()
	if !ok || loaded.Index != 2 || loaded.Position != 73.5 || loaded.PlaylistID != plID || !loaded.Shuffle {
		t.Fatalf("unexpected loaded queue state: ok=%v st=%+v", ok, loaded)
	}

	// 2. Restore into a fresh Player instance (without needing libmpv by invoking restoreStateLocked + eng helper test)
	lookup := func(id string) (Track, bool) {
		tr, ok := tracks[id]
		return tr, ok
	}
	p := NewPlayer(s, lookup, func(string, ...any) {})
	p.mu.Lock()
	p.restoreStateLocked()
	p.mu.Unlock()

	st := p.State()
	if st.QueueLen != 3 || st.QueueIndex != 1 || st.TrackID != "yt:qTrack00001" {
		t.Fatalf("unexpected restored player state: %+v", st)
	}
	if st.Position != 73.5 || st.Duration != 180 || st.PlaylistID != plID || !st.Shuffle || st.Playing || st.Loaded {
		t.Fatalf("expected paused restored state at 73.5s for playlist %d with shuffle=true, got %+v", plID, st)
	}
	if len(p.orig) != 3 || p.orig[0] != 12 || p.orig[1] != 11 || p.orig[2] != 13 {
		t.Fatalf("expected filtered orig [12 11 13], got %v", p.orig)
	}

	// 3. Verify resuming from restored state via Toggle() sets pendingSeek and applies it on OnAudioLoaded
	var loadedID string
	var seekCalled float64
	p.loadHook = func(id, _ string) error {
		loadedID = id
		return nil
	}
	p.seekHook = func(sec float64) {
		seekCalled = sec
	}
	p.Toggle()
	p.mu.Lock()
	pending := p.pendingSeek
	p.mu.Unlock()
	if loadedID != "yt:qTrack00001" || pending != 73.5 {
		t.Fatalf("expected Toggle() to load yt:qTrack00001 with pendingSeek=73.5, got id=%q pending=%v", loadedID, pending)
	}

	p.OnAudioTime(0, 180)
	if pos := p.State().Position; pos != 73.5 {
		t.Fatalf("expected 0 time report to be ignored while pendingSeek active, got %v", pos)
	}

	p.OnAudioLoaded(180)
	stAfterLoad := p.State()
	if !stAfterLoad.Loaded || stAfterLoad.Position != 73.5 || seekCalled != 73.5 {
		t.Fatalf("expected loaded=true, position=73.5, seekCalled=73.5 after OnAudioLoaded, got %+v seekCalled=%v", stAfterLoad, seekCalled)
	}
	p.mu.Lock()
	if p.pendingSeek != 0 {
		t.Fatalf("expected pendingSeek cleared after load, got %v", p.pendingSeek)
	}
	p.mu.Unlock()

	// 4. Verify Seek before load updates position and persists to SQLite
	p2 := NewPlayer(s, lookup, func(string, ...any) {})
	p2.mu.Lock()
	p2.restoreStateLocked()
	p2.mu.Unlock()
	p2.Seek(42.0)
	p2.SaveState()

	reloaded, ok := s.LoadQueueState()
	if !ok || reloaded.Position != 42.0 {
		t.Fatalf("expected persisted position 42.0 after seek+SaveState, got ok=%v st=%+v", ok, reloaded)
	}
}

func TestCropSquareCoverAndCachedYtThumb(t *testing.T) {
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	// Build a 480x360 image simulating YouTube hqdefault.jpg with 45px top/bottom black bars.
	src480 := image.NewRGBA(image.Rect(0, 0, 480, 360))
	cropped480 := cropSquareCover(src480)
	if cb := cropped480.Bounds(); cb.Dx() != 270 || cb.Dy() != 270 {
		t.Fatalf("expected 270x270 centered square crop for 480x360 image, got %dx%d", cb.Dx(), cb.Dy())
	}

	// Build a 640x480 image simulating YouTube sddefault.jpg with 60px top/bottom black bars.
	src640 := image.NewRGBA(image.Rect(0, 0, 640, 480))
	cropped640 := cropSquareCover(src640)
	if cb := cropped640.Bounds(); cb.Dx() != 360 || cb.Dy() != 360 {
		t.Fatalf("expected 360x360 centered square crop for 640x480 image, got %dx%d", cb.Dx(), cb.Dy())
	}

	// Build a 1280x720 16:9 HD thumbnail (green center square, blue sides).
	src := image.NewRGBA(image.Rect(0, 0, 1280, 720))
	for y := 0; y < 720; y++ {
		for x := 0; x < 1280; x++ {
			if x >= 280 && x < 1000 {
				src.SetRGBA(x, y, color.RGBA{R: 30, G: 215, B: 96, A: 255})
			} else {
				src.SetRGBA(x, y, color.RGBA{R: 10, G: 20, B: 200, A: 255})
			}
		}
	}

	var hits int
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits++
		w.Header().Set("Content-Type", "image/jpeg")
		_ = jpeg.Encode(w, src, &jpeg.Options{Quality: 95})
	}))
	defer srv.Close()

	vid := "testVid0001"
	p1 := cachedYtThumbPath(vid, srv.URL+"/maxresdefault.jpg")
	if p1 == "" {
		t.Fatalf("expected non-empty cached thumbnail path")
	}
	if !strings.Contains(p1, filepath.Join("thumbs", "v3", "yt")) {
		t.Fatalf("expected thumbs/v3/yt cache path, got %q", p1)
	}
	if hits != 1 {
		t.Fatalf("expected 1 upstream fetch on cold cache, got %d", hits)
	}

	f, err := os.Open(p1)
	if err != nil {
		t.Fatal(err)
	}
	decoded, _, err := image.Decode(f)
	_ = f.Close()
	if err != nil {
		t.Fatal(err)
	}
	db := decoded.Bounds()
	if db.Dx() != 480 || db.Dy() != 480 {
		t.Fatalf("expected cached thumb 480x480, got %dx%d", db.Dx(), db.Dy())
	}
	// Verify center/edge pixel inside the 1:1 square crop is green content rather than blue side pillar.
	r, g, b, _ := decoded.At(db.Min.X+10, db.Min.Y+240).RGBA()
	if g>>8 < 180 || r>>8 > 80 || b>>8 > 140 {
		t.Fatalf("expected cropped content color near green, got rgb(%d,%d,%d)", r>>8, g>>8, b>>8)
	}

	// Second call should hit disk cache without touching the server.
	p2 := cachedYtThumbPath(vid, srv.URL+"/maxresdefault.jpg")
	if p2 != p1 || hits != 1 {
		t.Fatalf("expected disk cache hit with hits=1, got path=%q hits=%d", p2, hits)
	}

	if invalid := cachedYtThumbPath("../bad", srv.URL+"/maxresdefault.jpg"); invalid != "" {
		t.Fatalf("expected invalid video ID to return empty path, got %q", invalid)
	}

	cands := ytThumbCandidateURLs(vid, "https://lh3.googleusercontent.com/abc=w600-h600-l90-rj")
	wantCands := []string{
		"https://lh3.googleusercontent.com/abc=w480-h480-l90-rj",
		"https://i.ytimg.com/vi/" + vid + "/maxresdefault.jpg",
		"https://i.ytimg.com/vi/" + vid + "/sddefault.jpg",
		"https://i.ytimg.com/vi/" + vid + "/hqdefault.jpg",
	}
	if len(cands) != len(wantCands) {
		t.Fatalf("unexpected candidate count: got %v, want %v", cands, wantCands)
	}
	for i := range wantCands {
		if cands[i] != wantCands[i] {
			t.Fatalf("candidate[%d]: got %q, want %q", i, cands[i], wantCands[i])
		}
	}
}
