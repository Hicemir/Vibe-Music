package main

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
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

func TestParseInnerTubeNextEntries(t *testing.T) {
	if got := parseDurationText("3:45"); got != 225 {
		t.Fatalf("expected 225s for 3:45, got %v", got)
	}
	if got := parseDurationText("1:02:03"); got != 3723 {
		t.Fatalf("expected 3723s for 1:02:03, got %v", got)
	}
	if got := parseDurationText(""); got != 0 {
		t.Fatalf("expected 0 for empty duration, got %v", got)
	}
	if got := parseDurationText("bad"); got != 0 {
		t.Fatalf("expected 0 for invalid duration, got %v", got)
	}

	root := map[string]any{
		"contents": map[string]any{
			"twoColumnWatchNextResults": map[string]any{
				"playlist": map[string]any{
					"playlist": map[string]any{
						"contents": []any{
							map[string]any{
								"playlistPanelVideoRenderer": map[string]any{
									"videoId": "abc12345678",
									"title":   map[string]any{"simpleText": "First Song"},
									"shortBylineText": map[string]any{
										"runs": []any{map[string]any{"text": "Artist One"}},
									},
									"lengthText": map[string]any{"simpleText": "3:45"},
									"thumbnail": map[string]any{
										"thumbnails": []any{
											map[string]any{
												"url":    "https://i.ytimg.com/vi/abc12345678/sddefault.jpg",
												"width":  float64(640),
												"height": float64(480),
											},
										},
									},
								},
							},
							map[string]any{
								"playlistPanelVideoWrapperRenderer": map[string]any{
									"primaryRenderer": map[string]any{
										"playlistPanelVideoRenderer": map[string]any{
											"videoId": "def12345678",
											"title":   map[string]any{"runs": []any{map[string]any{"text": "Wrapped Song"}}},
											"longBylineText": map[string]any{
												"runs": []any{map[string]any{"text": "Artist Two - Topic"}},
											},
											"lengthText": map[string]any{"simpleText": "4:10"},
											"thumbnail": map[string]any{
												"thumbnails": []any{
													map[string]any{
														"url":    "//lh3.googleusercontent.com/test=w120-h120-l90-rj",
														"width":  float64(120),
														"height": float64(120),
													},
												},
											},
										},
									},
									"counterpart": []any{
										map[string]any{
											"counterpartRenderer": map[string]any{
												"playlistPanelVideoRenderer": map[string]any{
													"videoId": "ctr12345678",
													"title":   map[string]any{"simpleText": "Counterpart Duplicate"},
												},
											},
										},
									},
								},
							},
							map[string]any{
								"playlistPanelVideoRenderer": map[string]any{
									"videoId":        "bad12345678",
									"title":          map[string]any{"simpleText": "Unavailable Song"},
									"unplayableText": map[string]any{"simpleText": "Video unavailable"},
								},
							},
						},
					},
				},
			},
		},
	}

	entries := parseInnerTubeNextEntries(root, 40)
	if len(entries) != 2 {
		t.Fatalf("expected 2 entries (skipping unplayable and wrapper counterpart), got %d: %+v", len(entries), entries)
	}
	if entries[0].ID != "abc12345678" || entries[0].Title != "First Song" || entries[0].Channel != "Artist One" || entries[0].Duration != 225 {
		t.Fatalf("unexpected first entry: %+v", entries[0])
	}
	if entries[1].ID != "def12345678" || entries[1].Title != "Wrapped Song" || entries[1].Channel != "Artist Two - Topic" || entries[1].Duration != 250 {
		t.Fatalf("unexpected second entry: %+v", entries[1])
	}
	if len(entries[1].Thumbnails) != 1 || entries[1].Thumbnails[0].URL != "https://lh3.googleusercontent.com/test=w120-h120-l90-rj" {
		t.Fatalf("unexpected wrapped thumbnails: %+v", entries[1].Thumbnails)
	}

	compactRoot := map[string]any{
		"contents": map[string]any{
			"twoColumnWatchNextResults": map[string]any{
				"secondaryResults": map[string]any{
					"secondaryResults": map[string]any{
						"results": []any{
							map[string]any{
								"compactVideoRenderer": map[string]any{
									"videoId": "cmp12345678",
									"title":   map[string]any{"simpleText": "Compact Track"},
									"shortBylineText": map[string]any{
										"runs": []any{map[string]any{"text": "Artist Three"}},
									},
									"lengthText": map[string]any{"simpleText": "2:55"},
								},
							},
						},
					},
				},
			},
		},
	}
	compactEntries := parseInnerTubeNextEntries(compactRoot, 40)
	if len(compactEntries) != 0 {
		t.Fatalf("expected watch-page compactVideoRenderer-only response to return 0 entries, got %+v", compactEntries)
	}

	if !innerTubeNextContainsSeed(root, entries, "abc12345678") {
		t.Fatalf("expected innerTubeNextContainsSeed to accept seed present in entries")
	}
	if !innerTubeNextContainsSeed(root, entries, "ctr12345678") {
		t.Fatalf("expected innerTubeNextContainsSeed to accept seed present in wrapper counterpart")
	}
	if innerTubeNextContainsSeed(root, entries, "unrelated01") {
		t.Fatalf("expected innerTubeNextContainsSeed to reject unrelated generic playlist")
	}

	bylineRoot := map[string]any{
		"contents": map[string]any{
			"playlistPanelVideoRenderer": map[string]any{
				"videoId": "byl12345678",
				"title":   map[string]any{"simpleText": "Byline Test Song"},
				"longBylineText": map[string]any{
					"runs": []any{map[string]any{"text": "Artist Three • Album Name • 2024"}},
				},
				"lengthText": map[string]any{"simpleText": "3:10"},
			},
		},
	}
	bylineEntries := parseInnerTubeNextEntries(bylineRoot, 10)
	if len(bylineEntries) != 1 || bylineEntries[0].Channel != "Artist Three" {
		t.Fatalf("expected byline bullets to be stripped to 'Artist Three', got %+v", bylineEntries)
	}
	playlistIDRoot := map[string]any{
		"contents": map[string]any{
			"singleColumnMusicWatchNextResultsRenderer": map[string]any{
				"tabbedRenderer": map[string]any{
					"watchNextTabbedResultsRenderer": map[string]any{
						"tabs": []any{
							map[string]any{
								"tabRenderer": map[string]any{
									"content": map[string]any{
										"musicQueueRenderer": map[string]any{
											"content": map[string]any{
												"playlistPanelRenderer": map[string]any{
													"playlistId": "RDAMVMseed1234567",
												},
											},
										},
									},
								},
							},
						},
					},
				},
			},
		},
	}
	if !innerTubeNextContainsSeed(playlistIDRoot, entries, "seed1234567") {
		t.Fatalf("expected innerTubeNextContainsSeed to accept matching playlistId")
	}
}

func TestConfigureManagedCmdKillsProcessGroup(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("linux process group test")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()

	cmd := exec.CommandContext(ctx, "sh", "-c", "sleep 30 & wait")
	configureManagedCmd(cmd)

	start := time.Now()
	_, err := cmd.Output()
	elapsed := time.Since(start)
	if err == nil {
		t.Fatal("expected timeout error from managed command")
	}
	if elapsed >= 3*time.Second {
		t.Fatalf("managed command hung waiting on child process: took %v", elapsed)
	}
}

func TestParseInnerTubeSearchEntries(t *testing.T) {
	root := map[string]any{
		"contents": map[string]any{
			"twoColumnSearchResultsRenderer": map[string]any{
				"primaryContents": map[string]any{
					"sectionListRenderer": map[string]any{
						"contents": []any{
							map[string]any{
								"itemSectionRenderer": map[string]any{
									"contents": []any{
										map[string]any{
											"videoRenderer": map[string]any{
												"videoId": "sea12345678",
												"title":   map[string]any{"runs": []any{map[string]any{"text": "Search Hit One"}}},
												"ownerText": map[string]any{
													"runs": []any{map[string]any{"text": "Band Alpha"}},
												},
												"lengthText": map[string]any{"simpleText": "3:20"},
												"thumbnail": map[string]any{
													"thumbnails": []any{
														map[string]any{
															"url":    "//i.ytimg.com/vi/sea12345678/hqdefault.jpg",
															"width":  float64(480),
															"height": float64(360),
														},
													},
												},
											},
										},
										map[string]any{
											"videoRenderer": map[string]any{
												"videoId": "sea12345678",
												"title":   map[string]any{"simpleText": "Duplicate Video"},
											},
										},
										map[string]any{
											"videoRenderer": map[string]any{
												"videoId":        "bad12345678",
												"title":          map[string]any{"simpleText": "Unplayable Result"},
												"unplayableText": map[string]any{"simpleText": "Unavailable"},
											},
										},
										map[string]any{
											"videoRenderer": map[string]any{
												"videoId": "seb12345678",
												"title":   map[string]any{"simpleText": "Search Hit Two"},
												"longBylineText": map[string]any{
													"runs": []any{map[string]any{"text": "Band Beta - Topic"}},
												},
												"lengthText": map[string]any{"simpleText": "4:05"},
											},
										},
									},
								},
							},
						},
					},
				},
			},
		},
	}

	entries := parseInnerTubeSearchEntries(root, 40)
	if len(entries) != 2 {
		t.Fatalf("expected 2 entries, got %d: %+v", len(entries), entries)
	}
	if entries[0].ID != "sea12345678" || entries[0].Title != "Search Hit One" || entries[0].Channel != "Band Alpha" || entries[0].Duration != 200 {
		t.Fatalf("unexpected first search entry: %+v", entries[0])
	}
	if len(entries[0].Thumbnails) != 1 || entries[0].Thumbnails[0].URL != "https://i.ytimg.com/vi/sea12345678/hqdefault.jpg" {
		t.Fatalf("unexpected thumbnails on first search entry: %+v", entries[0].Thumbnails)
	}
	if entries[1].ID != "seb12345678" || entries[1].Title != "Search Hit Two" || entries[1].Channel != "Band Beta - Topic" || entries[1].Duration != 245 {
		t.Fatalf("unexpected second search entry: %+v", entries[1])
	}
	limited := parseInnerTubeSearchEntries(root, 1)
	if len(limited) != 1 || limited[0].ID != "sea12345678" {
		t.Fatalf("expected limit=1 to return 1 entry, got %+v", limited)
	}
}

func makeTestSeedInfo(vid, artist, title string) discoverySeedInfo {
	artists := normDiscoveryArtists(artist)
	artSet := map[string]bool{}
	var artWords []string
	for _, a := range artists {
		artSet[a] = true
		if fw := normFoldWords(a); fw != "" {
			artWords = append(artWords, fw)
		}
	}
	return discoverySeedInfo{
		vid:         vid,
		artist:      artist,
		artists:     artists,
		artistSet:   artSet,
		artistWords: artWords,
		tokens:      extractDistinctiveTokens(title, "", artists),
	}
}

func TestDiscoverCoherenceRejectsGenericPopFiller(t *testing.T) {
	lib := discoveryLibraryProfile{
		artists: map[string]bool{
			"linkin park": true,
			"hoyo-mix":    true,
			"roselia":     true,
		},
		genreWeight: map[string]float64{"Rock": 5, "Metal": 3},
		genreTotal:  8,
	}

	seed1 := makeTestSeedInfo("seedLp00001", "Linkin Park", "Numb")
	seed2 := makeTestSeedInfo("seedHy00001", "HOYO-MIX", "Moon Halo")
	seed3 := makeTestSeedInfo("seedRs00001", "Roselia", "Neo-Aspect")

	ytm1 := []ytFlatEntry{
		{ID: "seedLp00001", Title: "Numb", Channel: "Linkin Park", Duration: 185},
		{ID: "lpNew000001", Title: "Faint", Channel: "Linkin Park", Duration: 162},
		{ID: "bmth0000001", Title: "Throne", Channel: "Bring Me The Horizon", Duration: 191},
		{ID: "lpNew000002", Title: "Breaking the Habit", Channel: "Linkin Park", Duration: 196},
	}
	ytm2 := []ytFlatEntry{
		{ID: "seedHy00001", Title: "Moon Halo", Channel: "HOYO-MIX", Duration: 228},
		{ID: "hoyo0000001", Title: "Regression", Channel: "HOYO-MIX", Duration: 185},
		{ID: "yoko0000001", Title: "Starlight", Channel: "Yoko Shimomura", Duration: 210},
	}
	// Polluted generic YouTube RD mix containing seed followed by mainstream pop unrelated to Rock/Metal/OST library
	pollutedYT := []ytFlatEntry{
		{ID: "seedRs00001", Title: "Neo-Aspect", Channel: "Roselia", Duration: 260},
		{ID: "adele000001", Title: "Rolling in the Deep", Channel: "Adele", Duration: 228},
		{ID: "serra000001", Title: "Random Pop Song", Channel: "Serra", Duration: 200},
		{ID: "edsheeran01", Title: "Shape of You", Channel: "Ed Sheeran", Duration: 233},
		{ID: "semicenk001", Title: "Pisman Degilim", Channel: "Semicenk", Duration: 195},
	}

	coh1, sah1, lah1, th1, ah1 := evaluateRadioMixCoherence(seed1, ytm1, ytRadioSourceYTM, lib)
	if !coh1 {
		t.Fatalf("expected coherent YTM mix 1")
	}
	coh2, sah2, lah2, th2, ah2 := evaluateRadioMixCoherence(seed2, ytm2, ytRadioSourceYTM, lib)
	if !coh2 {
		t.Fatalf("expected coherent YTM mix 2")
	}
	coh3, sah3, lah3, th3, ah3 := evaluateRadioMixCoherence(seed3, pollutedYT, ytRadioSourceYT, lib)
	if coh3 {
		t.Fatalf("expected polluted YouTube RD mix to be rejected as incoherent (sah=%d lah=%d th=%d ah=%d)", sah3, lah3, th3, ah3)
	}

	outcomes := []radioFetchOutcome{
		{seed: seed1, source: ytRadioSourceYTM, entries: ytm1, coherent: coh1, seedArtistHits: sah1, libArtistHits: lah1, tokenHits: th1, anchorHits: ah1},
		{seed: seed2, source: ytRadioSourceYTM, entries: ytm2, coherent: coh2, seedArtistHits: sah2, libArtistHits: lah2, tokenHits: th2, anchorHits: ah2},
		{seed: seed3, source: ytRadioSourceYT, entries: pollutedYT, coherent: coh3, seedArtistHits: sah3, libArtistHits: lah3, tokenHits: th3, anchorHits: ah3},
	}

	matcher := newSongSet(nil)
	out := rankDiscoveryOutcomes(outcomes, matcher, lib, 42, 16, nil)
	if len(out) == 0 {
		t.Fatalf("expected relevant recommendations from coherent mixes")
	}
	for _, it := range out {
		a := strings.ToLower(it.Artist)
		if a == "adele" || a == "serra" || a == "ed sheeran" || a == "semicenk" {
			t.Fatalf("unexpected irrelevant pop filler in discovery output: %+v", it)
		}
	}
}

func TestDiscoverRejectsDriftedTailAndGenreClash(t *testing.T) {
	lib := discoveryLibraryProfile{
		artists: map[string]bool{
			"metallica": true,
			"megadeth":  true,
		},
		genreWeight: map[string]float64{"Metal": 10, "Thrash Metal": 6},
		genreTotal:  16,
		artistGenre: func(key string) []string {
			switch key {
			case "adele", "serra":
				return []string{"Soft Pop"}
			case "cocomelon":
				return []string{"Children's"}
			case "testament":
				return []string{"Thrash Metal"}
			default:
				return nil
			}
		},
	}

	seed1 := makeTestSeedInfo("seedMet0001", "Metallica", "Master of Puppets")
	seed2 := makeTestSeedInfo("seedMeg0001", "Megadeth", "Holy Wars")

	// Build mix1 where pos 0 is seed artist, pos 1 is an uncorroborated new artist, pos 3 is genre-clashing Soft Pop at top,
	// pos 10 is cross-seed corroborated new artist (Testament), and pos 11..13 are drifted uncorroborated Adele/Serra entries.
	entries1 := []ytFlatEntry{
		{ID: "seedMet0001", Title: "Master of Puppets", Channel: "Metallica", Duration: 515},
		{ID: "metNew00001", Title: "Battery", Channel: "Metallica", Duration: 312},             // pos 0 (seed artist)
		{ID: "slayer00001", Title: "Raining Blood", Channel: "Slayer", Duration: 254},         // pos 1 (new artist within pos < 8)
		{ID: "filler00002", Title: "filler track 2", Channel: "Exodus", Duration: 240},        // pos 2
		{ID: "popClash001", Title: "Easy on Me", Channel: "Adele", Duration: 224},             // pos 3 (clashes with Metal!)
		{ID: "filler00004", Title: "filler track 4", Channel: "Anthrax", Duration: 240},       // pos 4
		{ID: "filler00005", Title: "filler track 5", Channel: "Kreator", Duration: 240},       // pos 5
		{ID: "filler00006", Title: "filler track 6", Channel: "Sodom", Duration: 240},         // pos 6
		{ID: "filler00007", Title: "filler track 7", Channel: "Overkill", Duration: 240},      // pos 7
		{ID: "filler00008", Title: "filler track 8", Channel: "Sepultura", Duration: 240},     // pos 8
		{ID: "filler00009", Title: "filler track 9", Channel: "Pantera", Duration: 240},       // pos 9
		{ID: "testam00001", Title: "Practice What You Preach", Channel: "Testament", Duration: 294}, // pos 10 (cross-seed!)
		{ID: "drift000011", Title: "Someone Like You", Channel: "Adele", Duration: 285},       // pos 11 (drifted tail)
		{ID: "drift000012", Title: "Drifted Pop Song", Channel: "Serra", Duration: 210},       // pos 12 (drifted tail)
		{ID: "drift000013", Title: "Unknown Drifted Hit", Channel: "Pop Star", Duration: 205}, // pos 13 (drifted tail)
	}
	entries2 := []ytFlatEntry{
		{ID: "seedMeg0001", Title: "Holy Wars", Channel: "Megadeth", Duration: 392},
		{ID: "megNew00001", Title: "Tornado of Souls", Channel: "Megadeth", Duration: 319},
		{ID: "testam00002", Title: "Over the Wall", Channel: "Testament", Duration: 245}, // corroborates Testament across seeds
	}

	coh1, sah1, lah1, th1, ah1 := evaluateRadioMixCoherence(seed1, entries1, ytRadioSourceYTM, lib)
	coh2, sah2, lah2, th2, ah2 := evaluateRadioMixCoherence(seed2, entries2, ytRadioSourceYTM, lib)
	if !coh1 || !coh2 {
		t.Fatalf("expected both Metal mixes to be coherent")
	}
	outcomes := []radioFetchOutcome{
		{seed: seed1, source: ytRadioSourceYTM, entries: entries1, coherent: true, seedArtistHits: sah1, libArtistHits: lah1, tokenHits: th1, anchorHits: ah1},
		{seed: seed2, source: ytRadioSourceYTM, entries: entries2, coherent: true, seedArtistHits: sah2, libArtistHits: lah2, tokenHits: th2, anchorHits: ah2},
	}

	out := rankDiscoveryOutcomes(outcomes, newSongSet(nil), lib, 99, 16, nil)
	ids := map[string]bool{}
	for _, it := range out {
		ids[it.VideoID] = true
		if it.VideoID == "popClash001" || it.VideoID == "drift000011" || it.VideoID == "drift000012" || it.VideoID == "drift000013" {
			t.Fatalf("unexpected genre-clashing or drifted-tail item returned: %+v", it)
		}
	}
	if !ids["slayer00001"] {
		t.Fatalf("expected uncorroborated top-ranked new artist Slayer (pos 1) to be kept, got %+v", out)
	}
	if !ids["testam00001"] {
		t.Fatalf("expected cross-seed corroborated artist Testament (pos 10) to be kept, got %+v", out)
	}
}
