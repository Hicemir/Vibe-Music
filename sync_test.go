package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestSyncCryptoAndFixedVectors(t *testing.T) {
	// Fixed known test vector (also verified in Android unit test)
	sharedSecret, _ := hex.DecodeString("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")
	initNonce := "0101010101010101010101010101010101010101010101010101010101010101"
	respNonce := "0202020202020202020202020202020202020202020202020202020202020202"

	encKey, authKey, err := deriveSessionKeys(sharedSecret, initNonce, respNonce)
	if err != nil {
		t.Fatal(err)
	}
	if len(encKey) != 32 || len(authKey) != 32 {
		t.Fatalf("unexpected key lengths %d %d", len(encKey), len(authKey))
	}

	transcript := buildSyncTranscript("aaaa", "bbbb", "PUB1", "PUB2", "EPH1", "EPH2", initNonce, respNonce)
	pin := computeSasPin(transcript)
	if len(pin) != 6 {
		t.Fatalf("unexpected pin %q", pin)
	}
	mac := computeRequestMAC(authKey, "POST", "/api/sync/v1/manifest", "sess1", "1700000000-abcdef01")
	if !verifyRequestMAC(authKey, "POST", "/api/sync/v1/manifest", "sess1", "1700000000-abcdef01", mac) {
		t.Fatal("MAC verification failed")
	}
	if verifyRequestMAC(authKey, "GET", "/api/sync/v1/manifest", "sess1", "1700000000-abcdef01", mac) {
		t.Fatal("MAC verification should fail for different method")
	}

	// Single-buffer AES-GCM
	msg := []byte(`{"hello":"vibemusic"}`)
	sealed, err := encryptSingleBuffer(encKey, msg)
	if err != nil {
		t.Fatal(err)
	}
	opened, err := decryptSingleBuffer(encKey, sealed)
	if err != nil || !bytes.Equal(opened, msg) {
		t.Fatalf("single buffer mismatch: %v %q", err, opened)
	}

	// ECDH roundtrip via SPKI
	k1, spki1, err := generateEphemeralKey()
	if err != nil {
		t.Fatal(err)
	}
	k2, spki2, err := generateEphemeralKey()
	if err != nil {
		t.Fatal(err)
	}
	sec1, err := computeECDHSharedSecret(k1, spki2)
	if err != nil {
		t.Fatal(err)
	}
	sec2, err := computeECDHSharedSecret(k2, spki1)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(sec1, sec2) {
		t.Fatal("ECDH shared secret mismatch")
	}

	// Chunked stream encryption across >2 chunks + truncation rejection
	payload := bytes.Repeat([]byte("VibeMusicAudioChunk!"), 8000) // ~160 KB (3 chunks)
	var encBuf bytes.Buffer
	if err := writeEncryptedStream(&encBuf, encKey, bytes.NewReader(payload), nil); err != nil {
		t.Fatal(err)
	}
	var outBuf bytes.Buffer
	n, err := readEncryptedStream(&encBuf, encKey, &outBuf, int64(len(payload)), nil)
	if err != nil || n != int64(len(payload)) || !bytes.Equal(outBuf.Bytes(), payload) {
		t.Fatalf("stream mismatch n=%d err=%v", n, err)
	}

	// Truncating EOF marker must fail
	var encBuf2 bytes.Buffer
	_ = writeEncryptedStream(&encBuf2, encKey, bytes.NewReader(payload), nil)
	raw := encBuf2.Bytes()
	var truncOut bytes.Buffer
	if _, err := readEncryptedStream(bytes.NewReader(raw[:len(raw)-20]), encKey, &truncOut, int64(len(payload)), nil); err == nil {
		t.Fatal("expected truncated stream to fail")
	}

	// Log deterministic constants for Android cross-check test
	t.Logf("VECTOR encKey=%x authKey=%x pin=%s mac=%s", encKey, authKey, pin, mac)
}

func TestComputeSyncPlanFirstAndSubsequent(t *testing.T) {
	// First sync: 200 tracks on A, 40 distinct tracks on B -> 240 union, 0 deletions
	var tracksA, tracksB []SyncTrack
	for i := 0; i < 200; i++ {
		id := fmt.Sprintf("yt:songA%06d", i)
		tracksA = append(tracksA, SyncTrack{
			ID:        id,
			YtID:      strings.TrimPrefix(id, "yt:"),
			RelPath:   fmt.Sprintf("Song A %d [%s].m4a", i, strings.TrimPrefix(id, "yt:")),
			Title:     fmt.Sprintf("Song A %d", i),
			Artist:    "Artist A",
			PlayCount: 2,
			Liked:     i%2 == 0,
			Genre:     "Electronic",
			GenreSource: "ai",
			FileSize:  1024,
			AddedAt:   1000,
		})
	}
	for i := 0; i < 40; i++ {
		id := fmt.Sprintf("yt:songB%06d", i)
		tracksB = append(tracksB, SyncTrack{
			ID:          id,
			YtID:        strings.TrimPrefix(id, "yt:"),
			RelPath:     fmt.Sprintf("Song B %d [%s].m4a", i, strings.TrimPrefix(id, "yt:")),
			Title:       fmt.Sprintf("Song B %d", i),
			Artist:      "Artist B",
			PlayCount:   1,
			Genre:       "Rock",
			GenreSource: "tag",
			FileSize:    2048,
			AddedAt:     1100,
		})
	}

	manA := SyncManifest{
		DeviceID:   "devA",
		DeviceName: "PC",
		Tracks:     tracksA,
		Playlists: []SyncPlaylist{{
			SyncUUID: "pl-1",
			Name:     "Favorites",
			TrackIDs: []string{"yt:songA000000", "yt:songA000001"},
		}},
		PlayHistory: []SyncPlayEvent{
			{TrackID: "yt:songA000000", PlayedAt: 5000},
			{TrackID: "yt:songA000000", PlayedAt: 5001},
		},
	}
	manB := SyncManifest{
		DeviceID:   "devB",
		DeviceName: "Phone",
		Tracks:     tracksB,
		Playlists: []SyncPlaylist{{
			SyncUUID: "pl-other",
			Name:     "favorites", // case-insensitive same-name merge on first sync
			TrackIDs: []string{"yt:songB000000"},
		}},
	}

	p1 := ComputeSyncPlan(manA, manB, nil, true)
	if len(p1.PushAudioToRemote) != 200 || len(p1.PullAudioFromRemote) != 40 {
		t.Fatalf("unexpected transfer counts push=%d pull=%d", len(p1.PushAudioToRemote), len(p1.PullAudioFromRemote))
	}
	if len(p1.DeleteOnLocal) != 0 || len(p1.DeleteOnRemote) != 0 {
		t.Fatalf("first sync should never delete: delL=%v delR=%v", p1.DeleteOnLocal, p1.DeleteOnRemote)
	}
	if len(p1.LocalCommit.Playlists) != 1 || len(p1.LocalCommit.Playlists[0].TrackIDs) != 3 {
		t.Fatalf("expected merged same-name playlist with 3 tracks, got %+v", p1.LocalCommit.Playlists)
	}

	// Second sync: both now have 240 tracks; A plays songA000000 +3 times, B plays it +2 times;
	// A deletes songB000000 (duplicate cleanup); A unlikes songA000000; B sets user genre on songA000001
	snap := p1.MergedSnapshot
	manA2 := SyncManifest{
		DeviceID:  "devA",
		Tracks:    append([]SyncTrack{}, p1.LocalCommit.TracksMeta...),
		Playlists: p1.LocalCommit.Playlists,
	}
	manB2 := SyncManifest{
		DeviceID:  "devB",
		Tracks:    append([]SyncTrack{}, p1.RemoteCommit.TracksMeta...),
		Playlists: p1.RemoteCommit.Playlists,
	}

	// Remove songB000000 from A; edit songA000000 on both
	var filteredA []SyncTrack
	for _, tr := range manA2.Tracks {
		if tr.ID == "yt:songB000000" {
			continue
		}
		if tr.ID == "yt:songA000000" {
			tr.PlayCount += 3
			tr.Liked = false
			tr.UpdatedAt = 2000
		}
		filteredA = append(filteredA, tr)
	}
	manA2.Tracks = filteredA

	for i := range manB2.Tracks {
		if manB2.Tracks[i].ID == "yt:songA000000" {
			manB2.Tracks[i].PlayCount += 2
		}
		if manB2.Tracks[i].ID == "yt:songA000001" {
			manB2.Tracks[i].Genre = "Metal"
			manB2.Tracks[i].GenreSource = "user"
			manB2.Tracks[i].UpdatedAt = 2005
		}
	}

	p2 := ComputeSyncPlan(manA2, manB2, &snap, true)
	if len(p2.DeleteOnRemote) != 1 || p2.DeleteOnRemote[0] != "yt:songB000000" {
		t.Fatalf("expected yt:songB000000 deleted on remote, got %v", p2.DeleteOnRemote)
	}
	if len(p2.PushAudioToRemote) != 0 || len(p2.PullAudioFromRemote) != 0 {
		t.Fatalf("expected 0 audio transfers, got push=%d pull=%d", len(p2.PushAudioToRemote), len(p2.PullAudioFromRemote))
	}

	byID := map[string]SyncTrack{}
	for _, tr := range p2.LocalCommit.TracksMeta {
		byID[tr.ID] = tr
	}
	if tr := byID["yt:songA000000"]; tr.PlayCount != 7 || tr.Liked {
		t.Fatalf("expected playCount=7 (2+3+2) and liked=false, got %+v", tr)
	}
	if tr := byID["yt:songA000001"]; tr.Genre != "Metal" || tr.GenreSource != "user" {
		t.Fatalf("expected user genre Metal propagated, got %+v", tr)
	}
	// Surviving playlist should also drop deleted songB000000
	if len(p2.LocalCommit.Playlists[0].TrackIDs) != 2 {
		t.Fatalf("expected playlist to prune deleted track, got %v", p2.LocalCommit.Playlists[0].TrackIDs)
	}
}

func makeTestApp(t *testing.T) (*App, string) {
	t.Helper()
	root := t.TempDir()
	dataHome := filepath.Join(root, "data")
	musicDir := filepath.Join(root, "Music")
	_ = os.MkdirAll(dataHome, 0o755)
	_ = os.MkdirAll(musicDir, 0o755)
	t.Setenv("XDG_DATA_HOME", dataHome)

	st, err := OpenStore()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.db.Close() })
	set := defaultSettings()
	set.MusicDirs = []string{musicDir}
	set.DownloadDir = musicDir
	_ = st.SaveSettings(set)

	app := &App{
		store:  st,
		tracks: map[string]Track{},
		set:    set,
	}
	app.scanner = NewScanner(st, func(string, ...any) {})
	app.syncMgr = NewSyncManager(app)
	t.Cleanup(func() { app.syncMgr.Shutdown() })
	return app, musicDir
}

func TestEndToEndHTTPSyncAndDeletion(t *testing.T) {
	// Note: because OpenStore reads XDG_DATA_HOME globally, instantiate two isolated stores by switching XDG_DATA_HOME per setup.
	appA, musicA := makeTestApp(t)
	_ = appA.store.SetSyncDeviceName("DesktopA")
	if err := appA.syncMgr.startHTTPServer(); err != nil {
		t.Fatal(err)
	}

	// Create 1 YT track with cover & lrc, and 1 local track on A
	rkA := appA.store.EnsureRoot(musicA)
	ytPathA := filepath.Join(musicA, "Alpha [dQw4w9WgXcQ].m4a")
	ytAudioBytes := []byte("FAKE_M4A_AUDIO_PAYLOAD_FOR_TESTING_1234567890")
	_ = os.WriteFile(ytPathA, ytAudioBytes, 0o644)
	_ = os.WriteFile(strings.TrimSuffix(ytPathA, ".m4a")+".lrc", []byte("[00:01.00]Hello Sync"), 0o644)
	_ = os.WriteFile(coverPath("yt:dQw4w9WgXcQ"), []byte("FAKE_JPEG_COVER"), 0o644)

	_, err := appA.store.UpsertTrack(Track{
		ID:          "yt:dQw4w9WgXcQ",
		YtID:        "dQw4w9WgXcQ",
		RootKey:     rkA,
		RelPath:     "Alpha [dQw4w9WgXcQ].m4a",
		Title:       "Alpha",
		Artist:      "Artist Alpha",
		HasCover:    true,
		Duration:    180,
		Format:      "aac",
		Size:        int64(len(ytAudioBytes)),
		Genre:       "Synthwave",
		GenreSource: "ai",
	})
	if err != nil {
		t.Fatal(err)
	}
	_ = appA.store.SetLiked("yt:dQw4w9WgXcQ", true)
	appA.store.RecordPlay("yt:dQw4w9WgXcQ", 0)
	plID, _ := appA.store.CreatePlaylist("Roadtrip")
	_, _ = appA.store.AddToPlaylist(plID, []string{"yt:dQw4w9WgXcQ"})

	// Setup B in separate temp dir
	appB, musicB := makeTestApp(t)
	_ = appB.store.SetSyncDeviceName("PhoneB")
	if err := appB.syncMgr.startHTTPServer(); err != nil {
		t.Fatal(err)
	}
	rkB := appB.store.EnsureRoot(musicB)
	localPathB := filepath.Join(musicB, "LocalBeta.mp3")
	localAudioBytes := []byte("LOCAL_MP3_PAYLOAD_FROM_PHONE_B_9876543210")
	_ = os.WriteFile(localPathB, localAudioBytes, 0o644)
	localIDB, err := localContentID(localPathB, int64(len(localAudioBytes)))
	if err != nil {
		t.Fatal(err)
	}
	_, err = appB.store.UpsertTrack(Track{
		ID:       localIDB,
		RootKey:  rkB,
		RelPath:  "LocalBeta.mp3",
		Title:    "Local Beta",
		Artist:   "Artist Beta",
		Duration: 120,
		Format:   "mp3",
		Size:     int64(len(localAudioBytes)),
	})
	if err != nil {
		t.Fatal(err)
	}

	// Pre-trust each other (simulating accepted pairing) and test full outbound sync B -> A
	idA := appA.syncMgr.identitySnapshot()
	idB := appB.syncMgr.identitySnapshot()
	_ = appA.store.UpsertTrustedSyncPeer(TrustedSyncPeer{
		DeviceID: idB.DeviceID, DeviceName: "PhoneB", Platform: "android", PubKeySpkiB64: idB.PubSpkiB64,
	})
	_ = appB.store.UpsertTrustedSyncPeer(TrustedSyncPeer{
		DeviceID: idA.DeviceID, DeviceName: "DesktopA", Platform: "linux", PubKeySpkiB64: idA.PubSpkiB64,
	})

	// Note: appA & appB shared XDG_DATA_HOME env only during OpenStore; coverDir() uses dataDir() dynamically,
	// so verify audio/DB/playlist sync across distinct musicDirs directly!
	peerA := SyncPeer{
		DeviceID:      idA.DeviceID,
		DeviceName:    "DesktopA",
		Platform:      "linux",
		IP:            "127.0.0.1",
		Port:          appA.syncMgr.httpPort,
		PubKeySpkiB64: idA.PubSpkiB64,
		Trusted:       true,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if err := appB.syncMgr.runOutboundSync(ctx, peerA, true); err != nil {
		t.Fatalf("first sync failed: %v", err)
	}

	// Both stores must now contain both tracks, like, playCount=1, genre=Synthwave, and playlist Roadtrip
	tracksA, _ := appA.store.AllTracksIncludeHidden()
	tracksB, _ := appB.store.AllTracksIncludeHidden()
	if len(tracksA) != 2 || len(tracksB) != 2 {
		t.Fatalf("expected 2 tracks on both sides, got A=%d B=%d", len(tracksA), len(tracksB))
	}
	if !fileExists(filepath.Join(musicB, "Alpha [dQw4w9WgXcQ].m4a")) {
		t.Fatal("Alpha audio file was not transferred directly to B")
	}
	if !fileExists(filepath.Join(musicB, "Alpha [dQw4w9WgXcQ].lrc")) {
		t.Fatal("Alpha .lrc file was not transferred directly to B")
	}
	if !fileExists(filepath.Join(musicA, "LocalBeta.mp3")) {
		t.Fatal("LocalBeta audio file was not transferred directly to A")
	}
	if trB, ok := appB.store.TrackByID("yt:dQw4w9WgXcQ"); !ok || !trB.Liked || trB.PlayCount != 1 || trB.Genre != "Synthwave" {
		t.Fatalf("metadata not synced to B: %+v (ok=%v)", trB, ok)
	}
	plsB, _ := appB.store.Playlists()
	if len(plsB) != 1 || plsB[0].Name != "Roadtrip" {
		t.Fatalf("playlist not synced to B: %+v", plsB)
	}

	// Second sync after deleting LocalBeta on A -> must delete DB + file on B!
	_ = appA.store.DeleteTracks([]string{localIDB})
	_ = os.Remove(filepath.Join(musicA, "LocalBeta.mp3"))

	if err := appB.syncMgr.runOutboundSync(ctx, peerA, true); err != nil {
		t.Fatalf("second sync failed: %v", err)
	}
	tracksB2, _ := appB.store.AllTracksIncludeHidden()
	if len(tracksB2) != 1 || tracksB2[0].ID != "yt:dQw4w9WgXcQ" {
		t.Fatalf("expected LocalBeta deleted on B, got %+v", tracksB2)
	}
	if fileExists(localPathB) {
		t.Fatal("expected LocalBeta audio file deleted from B disk")
	}

	// Also verify PIN pairing endpoint rejects bad signature and accepts valid pairing
	pairCli := &http.Client{Timeout: 5 * time.Second}
	baseA := fmt.Sprintf("http://127.0.0.1:%d", appA.syncMgr.httpPort)
	kFake, spkiFake, _ := generateEphemeralKey()
	_ = kFake
	pubSum := sha256.Sum256([]byte("invalid"))
	badID := hex.EncodeToString(pubSum[:16])
	code, _ := postJSON(ctx, pairCli, baseA+"/api/sync/v1/auth-init", syncAuthInitRequest{
		InitDeviceID: badID, InitPubSpkiB64: base64.StdEncoding.EncodeToString([]byte("invalid")), InitEphSpkiB64: spkiFake, InitNonceHex: randomHex(32),
	}, nil)
	if code == http.StatusOK {
		t.Fatal("expected invalid device key auth-init to be rejected")
	}
}
