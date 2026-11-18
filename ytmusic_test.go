package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestYtMusicCookiesAndSapisidHash(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("XDG_DATA_HOME", tmp)

	app := NewApp()
	rawNoAuth := ".youtube.com\t/\t0\tPREF\tabc123\n"
	if app.HandleWebViewCookies(rawNoAuth, false) {
		t.Fatal("expected HandleWebViewCookies to reject cookies without auth cookie")
	}

	raw := strings.Join([]string{
		".youtube.com\t/\t1\tSAPISID\tmysapisid/123",
		".youtube.com\t/\t1\t__Secure-1PAPISID\tp1val",
		".youtube.com\t/\t1\t__Secure-3PAPISID\tp3val",
		"music.youtube.com\t/\t1\t__Host-TEST\thostval",
		".google.com\t/\t1\tSID\tgooglesid",
	}, "\n")
	if !app.HandleWebViewCookies(raw, true) {
		t.Fatal("expected HandleWebViewCookies to succeed with auth cookies")
	}

	if !hasValidYtCookiesFile() {
		t.Fatal("expected yt_cookies.txt to be written and valid")
	}
	cookiesTxt, err := os.ReadFile(ytCookiesPath())
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(cookiesTxt), ".youtube.com\tTRUE\t/\tTRUE\t2147483647\tSAPISID\tmysapisid/123") {
		t.Fatalf("unexpected Netscape cookie line:\n%s", string(cookiesTxt))
	}
	if !strings.Contains(string(cookiesTxt), "music.youtube.com\tFALSE\t/\tTRUE\t2147483647\t__Host-TEST\thostval") {
		t.Fatalf("expected non-dot domain flag FALSE for __Host- cookie:\n%s", string(cookiesTxt))
	}

	auth := app.BuildSapisidHashHeader("https://music.youtube.com")
	for _, prefix := range []string{"SAPISIDHASH ", "SAPISID1PHASH ", "SAPISID3PHASH "} {
		if !strings.Contains(auth, prefix) {
			t.Fatalf("missing %q in auth header: %q", prefix, auth)
		}
	}
}

func TestYtMusicInnerTubeParsers(t *testing.T) {
	twoRow := map[string]any{
		"navigationEndpoint": map[string]any{
			"browseEndpoint": map[string]any{"browseId": "VLPLxyz123"},
		},
		"title":    map[string]any{"runs": []any{map[string]any{"text": "Chill Mix"}}},
		"subtitle": map[string]any{"runs": []any{map[string]any{"text": "Playlist • 42 songs"}}},
		"thumbnailRenderer": map[string]any{
			"musicThumbnailRenderer": map[string]any{
				"thumbnail": map[string]any{
					"thumbnails": []any{map[string]any{"url": "https://img.example/1.jpg"}},
				},
			},
		},
	}
	col, ok := parseTwoRowCollection(twoRow, "PLAYLIST")
	if !ok {
		t.Fatal("expected parseTwoRowCollection to succeed")
	}
	if col.ID != "PLxyz123" || col.BrowseID != "VLPLxyz123" || col.TrackCountHint != 42 || col.ThumbnailURL != "https://img.example/1.jpg" {
		t.Fatalf("unexpected parsed collection: %+v", col)
	}

	trackRenderer := map[string]any{
		"playlistItemData": map[string]any{"videoId": "dQw4w9WgXcQ"},
		"flexColumns": []any{
			map[string]any{
				"musicResponsiveListItemFlexColumnRenderer": map[string]any{
					"text": map[string]any{"runs": []any{map[string]any{"text": "Never Gonna Give You Up"}}},
				},
			},
			map[string]any{
				"musicResponsiveListItemFlexColumnRenderer": map[string]any{
					"text": map[string]any{
						"runs": []any{
							map[string]any{
								"text": "Rick Astley",
								"navigationEndpoint": map[string]any{
									"browseEndpoint": map[string]any{
										"browseEndpointContextSupportedConfigs": map[string]any{
											"browseEndpointContextMusicConfig": map[string]any{
												"pageType": "MUSIC_PAGE_TYPE_ARTIST",
											},
										},
									},
								},
							},
						},
					},
				},
			},
			map[string]any{
				"musicResponsiveListItemFlexColumnRenderer": map[string]any{
					"text": map[string]any{"runs": []any{map[string]any{"text": "Whenever You Need Somebody"}}},
				},
			},
		},
		"fixedColumns": []any{
			map[string]any{
				"musicResponsiveListItemFixedColumnRenderer": map[string]any{
					"text": map[string]any{"runs": []any{map[string]any{"text": "3:33"}}},
				},
			},
		},
	}
	item, ok := parseTrackFromListRenderer(trackRenderer, col)
	if !ok {
		t.Fatal("expected parseTrackFromListRenderer to succeed")
	}
	if item.YtID != "dQw4w9WgXcQ" || item.Title != "Never Gonna Give You Up" || item.Artist != "Rick Astley" || item.Album != "Whenever You Need Somebody" || item.DurationSec != 213 {
		t.Fatalf("unexpected parsed track: %+v", item)
	}
}

func TestStoreYtMusicHelpersAndDownloadDedup(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("XDG_DATA_HOME", tmp)

	st, err := OpenStore()
	if err != nil {
		t.Fatal(err)
	}
	defer st.db.Close()

	rootKey := st.EnsureRoot(filepath.Join(tmp, "Music"))
	id, err := st.UpsertTrack(Track{
		ID:      "yt:dQw4w9WgXcQ",
		YtID:    "dQw4w9WgXcQ",
		RootKey: rootKey,
		RelPath: "Song [dQw4w9WgXcQ].m4a",
		Title:   "Song",
		Artist:  "Artist",
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := st.UpdateTrackAlbum(id, "New Album"); err != nil {
		t.Fatal(err)
	}
	tr, ok := st.TrackByID(id)
	if !ok || tr.Album != "New Album" {
		t.Fatalf("expected updated album, got %+v ok=%v", tr, ok)
	}

	pl1, err := st.GetOrCreatePlaylist("Roadtrip")
	if err != nil || pl1 <= 0 {
		t.Fatalf("GetOrCreatePlaylist failed: %d %v", pl1, err)
	}
	pl2, err := st.GetOrCreatePlaylist("roadtrip")
	if err != nil || pl2 != pl1 {
		t.Fatalf("expected case-insensitive match %d, got %d (%v)", pl1, pl2, err)
	}

	if err := st.SaveSyncedYtCollections([]string{"LM", "PL123", "LM"}); err != nil {
		t.Fatal(err)
	}
	loaded := st.LoadSyncedYtCollections()
	if len(loaded) != 2 || loaded[0] != "LM" || loaded[1] != "PL123" {
		t.Fatalf("unexpected synced collections: %v", loaded)
	}

	// Test EnqueueYtMusicTrack deduplication across multiple collections without spawning worker downloads
	app := NewApp()
	app.store = st
	app.ytdlp = NewYtdlp(func(string, ...any) {})
	dl := &Downloader{app: app, notify: make(chan struct{}, 1)}
	// Point ytdlp path check using system or dummy executable if needed; test direct addInternal when yt-dlp exists
	dummyBin := filepath.Join(tmp, "yt-dlp")
	if err := os.WriteFile(dummyBin, []byte("#!/bin/sh\nexit 0\n"), 0o755); err == nil {
		t.Setenv("PATH", tmp+string(os.PathListSeparator)+os.Getenv("PATH"))
		id1, err := dl.EnqueueYtMusicTrack("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", pl1, false, "")
		if err != nil {
			t.Fatal(err)
		}
		id2, err := dl.EnqueueYtMusicTrack("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", pl1+1, true, "Album X")
		if err != nil {
			t.Fatal(err)
		}
		if id1 != id2 {
			t.Fatalf("expected deduplicated job ID %d == %d", id1, id2)
		}
		if len(dl.jobs) != 1 || len(dl.jobs[0].targetPlaylistIDs) != 2 || !dl.jobs[0].markAsLiked || dl.jobs[0].albumOverride != "Album X" {
			t.Fatalf("unexpected merged job state: %+v", dl.jobs[0])
		}

		// Test merging onto an already-completed ("done") job when its file still exists on disk
		doneFile := filepath.Join(tmp, "Never Gonna Give You Up [dQw4w9WgXcQ].m4a")
		if err := os.WriteFile(doneFile, []byte("audio"), 0o644); err != nil {
			t.Fatal(err)
		}
		dl.jobs[0].Status = "done"
		dl.jobs[0].files = []string{doneFile}
		id3, err := dl.EnqueueYtMusicTrack("dQw4w9WgXcQ", "Rick Astley - Never Gonna Give You Up", pl1+2, false, "")
		if err != nil {
			t.Fatal(err)
		}
		if id3 != id1 {
			t.Fatalf("expected done job reuse %d == %d", id3, id1)
		}
		if len(dl.jobs) != 1 || len(dl.jobs[0].targetPlaylistIDs) != 3 {
			t.Fatalf("expected merged playlist targets on done job, got %+v", dl.jobs[0].targetPlaylistIDs)
		}

		// Test canonical title deduplication across different video IDs for active jobs
		id4, err := dl.EnqueueYtMusicTrackItem(YtMusicTrackItem{
			YtID:        "aaaaaaaaaaa",
			Title:       "Moon Halo",
			Artist:      "HOYO-MIX",
			DurationSec: 228,
		}, "HOYO-MIX - Moon Halo", pl1, false, "")
		if err != nil {
			t.Fatal(err)
		}
		id5, err := dl.EnqueueYtMusicTrackItem(YtMusicTrackItem{
			YtID:        "bbbbbbbbbbb",
			Title:       "Moon Halo - Honkai Impact 3rd Valkyrie Theme",
			Artist:      "HOYO-MIX",
			DurationSec: 228,
		}, "HOYO-MIX - Moon Halo - Honkai Impact 3rd Valkyrie Theme", pl1+1, true, "")
		if err != nil {
			t.Fatal(err)
		}
		if id4 != id5 {
			t.Fatalf("expected canonical song job dedup %d == %d", id4, id5)
		}

		// Distinct songs by the same artist must NOT merge
		id6, err := dl.EnqueueYtMusicTrackItem(YtMusicTrackItem{
			YtID:        "ccccccccccc",
			Title:       "Idol",
			Artist:      "YOASOBI",
			DurationSec: 213,
		}, "YOASOBI - Idol", pl1, false, "")
		if err != nil {
			t.Fatal(err)
		}
		id7, err := dl.EnqueueYtMusicTrackItem(YtMusicTrackItem{
			YtID:        "ddddddddddd",
			Title:       "Gunjo",
			Artist:      "YOASOBI",
			DurationSec: 248,
		}, "YOASOBI - Gunjo", pl1, false, "")
		if err != nil {
			t.Fatal(err)
		}
		if id6 == id7 {
			t.Fatalf("expected distinct YOASOBI songs to have different jobs, got same ID %d", id6)
		}
	}
}

func TestSongNormalizationAndDiscoverDedup(t *testing.T) {
	known := map[string]bool{
		"hoyo mix": true,
		"roselia":  true,
		"yoasobi":  true,
	}

	// 1. Da Capo — Honkai Impact 3rd vs Da Capo
	s := newSongSet(known)
	s.add("vidDaCapo11", "Da Capo — Honkai Impact 3rd", "Honkai Impact 3rd")
	if !s.matches("vidOther111", "Da Capo", "HOYO-MIX") {
		t.Fatal("expected Da Capo to match Da Capo — Honkai Impact 3rd")
	}

	// 2. Moon Halo - Honkai Impact 3rd Valkyrie Theme vs Moon Halo
	s2 := newSongSet(known)
	s2.add("vidMoon1111", "Moon Halo", "HOYO-MIX")
	if !s2.matches("vidMoon2222", "Moon Halo - Honkai Impact 3rd Valkyrie Theme", "HOYO-MIX") {
		t.Fatal("expected Moon Halo - Honkai Impact 3rd Valkyrie Theme to match Moon Halo")
	}

	// 3. Phoenix EP - "1% Battery" vs 1% Low Battery Girl
	s3 := newSongSet(known)
	s3.add("vidBat11111", `Phoenix EP - "1% Battery"`, "Zenless Zone Zero")
	if !s3.matches("vidBat22222", "1% Low Battery Girl", "Sae-Z") {
		t.Fatal(`expected 1% Low Battery Girl to match Phoenix EP - "1% Battery"`)
	}

	// 4. 【公式ライブ映像】Roselia「Neo-Aspect」 vs Roselia「Neo-Aspect」Chronicle Movie
	s4 := newSongSet(known)
	s4.add("vidNeo11111", "【公式ライブ映像】Roselia「Neo-Aspect」（2021.2.21 BanG Dream! 7th☆LIVE）", "バンドリちゃんねる☆")
	if !s4.matches("vidNeo22222", "Roselia「Neo-Aspect」Chronicle Movie", "BanG Dream! Girls Band Party!") {
		t.Fatal("expected Roselia「Neo-Aspect」Chronicle Movie to match 【公式ライブ映像】Roselia「Neo-Aspect」")
	}

	// 5. YOASOBI - Gunjo / THE FIRST TAKE vs Gunjo, while Idol remains distinct
	s5 := newSongSet(known)
	s5.add("vidGunjo111", "YOASOBI - Gunjo / THE FIRST TAKE", "THE FIRST TAKE")
	if !s5.matches("vidGunjo222", "Gunjo", "YOASOBI") {
		t.Fatal("expected Gunjo to match YOASOBI - Gunjo / THE FIRST TAKE")
	}
	if s5.matches("vidIdol1111", "YOASOBI - Idol", "YOASOBI") {
		t.Fatal("did not expect YOASOBI - Idol to match YOASOBI - Gunjo")
	}

	// Verify Survive[Arknights Soundtrack]
	s6 := newSongSet(known)
	s6.add("vidSurv1111", "Survive[Arknights Soundtrack]", "Arknights Official")
	if !s6.matches("vidSurv2222", "Survive", "Monster Siren Records") {
		t.Fatal("expected Survive to match Survive[Arknights Soundtrack]")
	}
}

func TestRecommendedHistoryPersistenceAndExistingTrackMatching(t *testing.T) {
	tmp := t.TempDir()
	t.Setenv("XDG_DATA_HOME", tmp)

	st, err := OpenStore()
	if err != nil {
		t.Fatal(err)
	}
	defer st.db.Close()

	if err := st.AddRecommendedHistory([]string{"vid:abc12345678", "song:moon halo", "vid:abc12345678"}); err != nil {
		t.Fatal(err)
	}
	if err := st.AddRecommendedHistory([]string{"song:1% battery", "song:moon halo"}); err != nil {
		t.Fatal(err)
	}
	hist := st.LoadRecommendedHistory()
	if len(hist) != 3 || hist[0] != "vid:abc12345678" || hist[1] != "song:moon halo" || hist[2] != "song:1% battery" {
		t.Fatalf("unexpected persisted history: %v", hist)
	}

	app := NewApp()
	app.store = st
	app.dl = &Downloader{app: app, notify: make(chan struct{}, 1)}

	// Simulate an existing local track without YtID and an existing YT track with a different video ID
	app.tracks = map[string]Track{
		"local:hash1": {
			ID:       "local:hash1",
			Title:    "Da Capo",
			Artist:   "HOYO-MIX",
			Duration: 147,
		},
		"yt:mvVideo1111": {
			ID:       "yt:mvVideo1111",
			YtID:     "mvVideo1111",
			Title:    "Moon Halo - Honkai Impact 3rd Valkyrie Theme",
			Artist:   "HOYO-MIX",
			Duration: 228,
		},
	}

	// buildDiscoveryMatcher must exclude both owned tracks and persisted history (including 1% Low Battery Girl via token match)
	matcher := app.dl.buildDiscoveryMatcher()
	if !matcher.matches("newVid99999", "Da Capo — Honkai Impact 3rd", "Honkai Impact 3rd") {
		t.Fatal("expected matcher to exclude already-owned local track Da Capo")
	}
	if !matcher.matches("newVid88888", "1% Low Battery Girl", "Sae-Z") {
		t.Fatal("expected matcher to exclude previously recommended 1% battery variant from persisted history")
	}

	// findExistingTrackForYtmItem must match owned local track and different-YtID YT track
	tracksSlice := []Track{app.tracks["local:hash1"], app.tracks["yt:mvVideo1111"]}
	known := collectKnownLibraryNames(tracksSlice)

	if m, ok := findExistingTrackForYtmItem(YtMusicTrackItem{
		YtID:        "ytmAudio111",
		Title:       "Da Capo",
		Artist:      "HOYO-MIX",
		DurationSec: 148,
	}, tracksSlice, known); !ok || m.ID != "local:hash1" {
		t.Fatalf("expected match against local:hash1, got ok=%v track=%+v", ok, m)
	}

	if m, ok := findExistingTrackForYtmItem(YtMusicTrackItem{
		YtID:        "ytmAudio222",
		Title:       "Moon Halo",
		Artist:      "HOYO-MIX",
		DurationSec: 229,
	}, tracksSlice, known); !ok || m.ID != "yt:mvVideo1111" {
		t.Fatalf("expected match against yt:mvVideo1111, got ok=%v track=%+v", ok, m)
	}

	// Must NOT match a completely different song by the same artist
	if m, ok := findExistingTrackForYtmItem(YtMusicTrackItem{
		YtID:        "ytmAudio333",
		Title:       "Regression",
		Artist:      "HOYO-MIX",
		DurationSec: 185,
	}, tracksSlice, known); ok {
		t.Fatalf("expected no match for Regression, got %+v", m)
	}
}
