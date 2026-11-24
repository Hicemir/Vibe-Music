package main

import (
	"database/sql"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"time"
)

func (s *Store) GetSyncSetting(key string) string {
	var v string
	_ = s.db.QueryRow(`SELECT value FROM settings WHERE key=?`, key).Scan(&v)
	return v
}

func (s *Store) SetSyncSetting(key, value string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value`, key, value)
	return err
}

func (s *Store) GetSyncDeviceName() string {
	if v := strings.TrimSpace(s.GetSyncSetting("sync_device_name")); v != "" {
		return v
	}
	if h, err := os.Hostname(); err == nil && strings.TrimSpace(h) != "" {
		return strings.TrimSpace(h)
	}
	return "VibeMusic PC"
}

func (s *Store) SetSyncDeviceName(name string) error {
	return s.SetSyncSetting("sync_device_name", strings.TrimSpace(name))
}

func (s *Store) ListTrustedSyncPeers() []TrustedSyncPeer {
	rows, err := s.db.Query(`SELECT device_id,device_name,platform,pub_key_spki,paired_at,last_sync_at FROM trusted_sync_peers ORDER BY last_sync_at DESC, paired_at DESC`)
	if err != nil {
		return []TrustedSyncPeer{}
	}
	defer rows.Close()
	out := []TrustedSyncPeer{}
	for rows.Next() {
		var p TrustedSyncPeer
		if rows.Scan(&p.DeviceID, &p.DeviceName, &p.Platform, &p.PubKeySpkiB64, &p.PairedAt, &p.LastSyncAt) == nil {
			out = append(out, p)
		}
	}
	return out
}

func (s *Store) GetTrustedSyncPeer(deviceID string) (TrustedSyncPeer, bool) {
	var p TrustedSyncPeer
	err := s.db.QueryRow(`SELECT device_id,device_name,platform,pub_key_spki,paired_at,last_sync_at FROM trusted_sync_peers WHERE device_id=?`, deviceID).
		Scan(&p.DeviceID, &p.DeviceName, &p.Platform, &p.PubKeySpkiB64, &p.PairedAt, &p.LastSyncAt)
	return p, err == nil
}

func (s *Store) UpsertTrustedSyncPeer(p TrustedSyncPeer) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if p.PairedAt == 0 {
		p.PairedAt = time.Now().Unix()
	}
	_, err := s.db.Exec(`INSERT INTO trusted_sync_peers(device_id,device_name,platform,pub_key_spki,paired_at,last_sync_at)
VALUES(?,?,?,?,?,?)
ON CONFLICT(device_id) DO UPDATE SET device_name=excluded.device_name, platform=excluded.platform, pub_key_spki=excluded.pub_key_spki,
last_sync_at=CASE WHEN excluded.last_sync_at > trusted_sync_peers.last_sync_at THEN excluded.last_sync_at ELSE trusted_sync_peers.last_sync_at END`,
		p.DeviceID, p.DeviceName, p.Platform, p.PubKeySpkiB64, p.PairedAt, p.LastSyncAt)
	return err
}

func (s *Store) DeleteTrustedSyncPeer(deviceID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, err := s.db.Exec(`DELETE FROM trusted_sync_peers WHERE device_id=?`, deviceID)
	return err
}

func (s *Store) LoadSyncSnapshot(peerID string) (*SyncSnapshot, error) {
	var raw string
	err := s.db.QueryRow(`SELECT snapshot_json FROM sync_snapshots WHERE peer_device_id=?`, peerID).Scan(&raw)
	if err == sql.ErrNoRows || strings.TrimSpace(raw) == "" {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var snap SyncSnapshot
	if err := json.Unmarshal([]byte(raw), &snap); err != nil {
		return nil, nil
	}
	if snap.Tracks == nil {
		snap.Tracks = map[string]SyncSnapshotTrack{}
	}
	if snap.Playlists == nil {
		snap.Playlists = map[string]SyncSnapshotPlaylist{}
	}
	return &snap, nil
}

func (s *Store) HiddenTrackIDs() []string {
	rows, err := s.db.Query(`SELECT track_id FROM hidden_tracks`)
	if err != nil {
		return []string{}
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var id string
		if rows.Scan(&id) == nil && id != "" {
			out = append(out, id)
		}
	}
	return out
}

func sidecarLrcPath(audioPath string) string {
	if audioPath == "" {
		return ""
	}
	base := strings.TrimSuffix(audioPath, filepath.Ext(audioPath))
	for _, ext := range []string{".lrc", ".LRC"} {
		p := base + ext
		if fileExists(p) {
			return p
		}
	}
	return ""
}

func (s *Store) BuildSyncManifest(deviceID, deviceName, platform string, dirs []string) (SyncManifest, error) {
	m := SyncManifest{
		DeviceID:            deviceID,
		DeviceName:          deviceName,
		Platform:            platform,
		GeneratedAt:         time.Now().Unix(),
		Tracks:              []SyncTrack{},
		Playlists:           []SyncPlaylist{},
		PlayHistory:         []SyncPlayEvent{},
		HiddenTrackIDs:      []string{},
		ArtistGenres:        []SyncArtistGenre{},
		TrackContexts:       []SyncTrackContext{},
		SyncedYtCollections: s.LoadSyncedYtCollections(),
	}

	rootMounted := map[string]bool{}
	s.rmu.RLock()
	for k, base := range s.roots {
		st, err := os.Stat(base)
		rootMounted[k] = err == nil && st.IsDir()
	}
	s.rmu.RUnlock()

	if len(dirs) > 0 {
		anyDirMounted := false
		for _, d := range dirs {
			if st, err := os.Stat(d); err == nil && st.IsDir() {
				anyDirMounted = true
				break
			}
		}
		if !anyDirMounted {
			m.LibraryUnmounted = true
		}
	}

	hiddenMap := map[string]bool{}
	for _, hid := range s.HiddenTrackIDs() {
		hiddenMap[hid] = true
		m.HiddenTrackIDs = append(m.HiddenTrackIDs, hid)
	}

	allTracks, err := s.AllTracksIncludeHidden()
	if err != nil {
		return m, err
	}
	liveIDs := make(map[string]bool, len(allTracks))
	for _, t := range allTracks {
		if !rootMounted[t.RootKey] {
			m.LibraryUnmounted = true
		}
		if t.Path == "" || !fileExists(t.Path) {
			continue
		}
		liveIDs[t.ID] = true
		hasCoverFile := fileExists(coverPath(t.ID))
		hasLyrics := sidecarLrcPath(t.Path) != ""
		m.Tracks = append(m.Tracks, SyncTrack{
			ID:            t.ID,
			YtID:          t.YtID,
			RelPath:       t.RelPath,
			Title:         t.Title,
			Artist:        t.Artist,
			Album:         t.Album,
			Duration:      t.Duration,
			Year:          t.Year,
			AddedAt:       t.AddedAt,
			Liked:         t.Liked,
			PlayCount:     t.PlayCount,
			LastPlayed:    t.LastPlay,
			HasCover:      t.HasCover || hasCoverFile,
			HasCoverFile:  hasCoverFile,
			HasLyricsFile: hasLyrics,
			Format:        t.Format,
			Bitrate:       t.Bitrate,
			FileSize:      t.Size,
			MTimeUnix:     t.MTime,
			UpdatedAt:     t.UpdatedAt,
			Genre:         t.Genre,
			GenreSource:   t.GenreSource,
			Hidden:        hiddenMap[t.ID],
		})
	}

	pls, err := s.Playlists()
	if err == nil {
		plMap := s.AllPlaylistTracks()
		for _, p := range pls {
			rawIDs := plMap[p.ID]
			cleanIDs := make([]string, 0, len(rawIDs))
			for _, tid := range rawIDs {
				if liveIDs[tid] {
					cleanIDs = append(cleanIDs, tid)
				}
			}
			m.Playlists = append(m.Playlists, SyncPlaylist{
				SyncUUID:   p.SyncUUID,
				Name:       p.Name,
				CreatedAt:  p.CreatedAt,
				LastPlayed: p.LastPlayed,
				TrackIDs:   cleanIDs,
			})
		}
	}

	if rows, err := s.db.Query(`SELECT track_id, played_at FROM play_history ORDER BY played_at ASC`); err == nil {
		type key struct {
			id string
			at int64
		}
		seen := map[key]bool{}
		for rows.Next() {
			var tid string
			var at int64
			if rows.Scan(&tid, &at) == nil && tid != "" {
				k := key{tid, at}
				if !seen[k] {
					seen[k] = true
					m.PlayHistory = append(m.PlayHistory, SyncPlayEvent{TrackID: tid, PlayedAt: at})
				}
			}
		}
		rows.Close()
	}

	if rows, err := s.db.Query(`SELECT artist_key, genre, source, checked_at FROM artist_genres`); err == nil {
		for rows.Next() {
			var ag SyncArtistGenre
			if rows.Scan(&ag.ArtistKey, &ag.Genre, &ag.Source, &ag.CheckedAt) == nil {
				m.ArtistGenres = append(m.ArtistGenres, ag)
			}
		}
		rows.Close()
	}

	if rows, err := s.db.Query(`SELECT cache_key, hints, fetched_at FROM track_context`); err == nil {
		for rows.Next() {
			var tc SyncTrackContext
			if rows.Scan(&tc.CacheKey, &tc.Hints, &tc.UpdatedAt) == nil {
				m.TrackContexts = append(m.TrackContexts, tc)
			}
		}
		rows.Close()
	}

	return m, nil
}

func removeCoverAndThumbs(id string) {
	_ = os.Remove(coverPath(id))
	key := coverKey(id) + ".jpg"
	for _, sub := range []string{"128", "300", "480"} {
		_ = os.Remove(filepath.Join(coverDir(), "thumbs", "v2", sub, key))
	}
}

func (s *Store) ApplySyncCommit(payload SyncCommitPayload, peerID string, snap *SyncSnapshot) error {
	type diskKill struct {
		id    string
		audio string
		lrc   string
	}
	var toRemove []diskKill
	for _, id := range payload.DeletedTrackIDs {
		if t, ok := s.TrackByID(id); ok {
			toRemove = append(toRemove, diskKill{
				id:    id,
				audio: t.Path,
				lrc:   sidecarLrcPath(t.Path),
			})
		} else {
			toRemove = append(toRemove, diskKill{id: id})
		}
	}

	snapJSON := ""
	if snap != nil {
		if b, err := json.Marshal(snap); err == nil {
			snapJSON = string(b)
		}
	}
	ytJSON, _ := json.Marshal(payload.SyncedYtCollections)

	s.mu.Lock()
	tx, err := s.db.Begin()
	if err != nil {
		s.mu.Unlock()
		return err
	}
	defer tx.Rollback()

	for _, id := range payload.DeletedTrackIDs {
		if _, err := tx.Exec(`DELETE FROM tracks WHERE id=?`, id); err != nil {
			s.mu.Unlock()
			return err
		}
		_, _ = tx.Exec(`DELETE FROM hidden_tracks WHERE track_id=?`, id)
	}

	for _, t := range payload.TracksMeta {
		liked := 0
		if t.Liked {
			liked = 1
		}
		hasCover := 0
		if t.HasCover {
			hasCover = 1
		}
		if _, err := tx.Exec(`UPDATE tracks SET
  title=?, artist=?, album=?, duration=?, year=?, added_at=?,
  liked=?, play_count=?, last_played=?,
  has_cover=CASE WHEN has_cover=1 OR ?=1 THEN 1 ELSE 0 END,
  genre=?, genre_source=?, updated_at=?
  WHERE id=?`,
			t.Title, t.Artist, t.Album, t.Duration, t.Year, t.AddedAt,
			liked, t.PlayCount, t.LastPlayed, hasCover,
			t.Genre, t.GenreSource, t.UpdatedAt, t.ID); err != nil {
			s.mu.Unlock()
			return err
		}
	}

	// Deduplicated play_history insertion
	type histKey struct {
		id string
		at int64
	}
	existingHist := map[histKey]bool{}
	if hRows, err := tx.Query(`SELECT track_id, played_at FROM play_history`); err == nil {
		for hRows.Next() {
			var tid string
			var at int64
			if hRows.Scan(&tid, &at) == nil {
				existingHist[histKey{tid, at}] = true
			}
		}
		hRows.Close()
	}
	for _, ev := range payload.PlayHistory {
		if ev.TrackID == "" || ev.PlayedAt <= 0 {
			continue
		}
		k := histKey{ev.TrackID, ev.PlayedAt}
		if !existingHist[k] {
			existingHist[k] = true
			if _, err := tx.Exec(`INSERT INTO play_history(track_id,playlist_id,played_at) VALUES(?,0,?)`, ev.TrackID, ev.PlayedAt); err != nil {
				s.mu.Unlock()
				return err
			}
		}
	}

	// Track existence set inside tx
	liveTracks := map[string]bool{}
	if tRows, err := tx.Query(`SELECT id FROM tracks`); err == nil {
		for tRows.Next() {
			var id string
			if tRows.Scan(&id) == nil {
				liveTracks[id] = true
			}
		}
		tRows.Close()
	}

	// Sync hidden_tracks
	if _, err := tx.Exec(`DELETE FROM hidden_tracks`); err != nil {
		s.mu.Unlock()
		return err
	}
	for _, hid := range payload.HiddenTrackIDs {
		if liveTracks[hid] {
			_, _ = tx.Exec(`INSERT OR IGNORE INTO hidden_tracks(track_id) VALUES(?)`, hid)
		}
	}

	// Sync artist_genres
	for _, ag := range payload.ArtistGenres {
		if ag.ArtistKey == "" {
			continue
		}
		_, _ = tx.Exec(`INSERT INTO artist_genres(artist_key,genre,source,checked_at) VALUES(?,?,?,?)
ON CONFLICT(artist_key) DO UPDATE SET
  genre=CASE WHEN excluded.checked_at>=artist_genres.checked_at THEN excluded.genre ELSE artist_genres.genre END,
  source=CASE WHEN excluded.checked_at>=artist_genres.checked_at THEN excluded.source ELSE artist_genres.source END,
  checked_at=MAX(artist_genres.checked_at, excluded.checked_at)`,
			ag.ArtistKey, ag.Genre, ag.Source, ag.CheckedAt)
	}

	// Sync track_context
	for _, tc := range payload.TrackContexts {
		if tc.CacheKey == "" {
			continue
		}
		_, _ = tx.Exec(`INSERT INTO track_context(cache_key,hints,fetched_at) VALUES(?,?,?)
ON CONFLICT(cache_key) DO UPDATE SET
  hints=CASE WHEN excluded.fetched_at>=track_context.fetched_at THEN excluded.hints ELSE track_context.hints END,
  fetched_at=MAX(track_context.fetched_at, excluded.fetched_at)`,
			tc.CacheKey, tc.Hints, tc.UpdatedAt)
	}

	// Delete removed playlists
	for _, uuid := range payload.DeletedPlaylistUUIDs {
		if uuid != "" {
			_, _ = tx.Exec(`DELETE FROM playlists WHERE sync_uuid=?`, uuid)
		}
	}

	// Upsert playlists & ordered tracks
	for _, pl := range payload.Playlists {
		if strings.TrimSpace(pl.Name) == "" && len(pl.TrackIDs) == 0 {
			continue
		}
		var localID int64
		if pl.SyncUUID != "" {
			_ = tx.QueryRow(`SELECT id FROM playlists WHERE sync_uuid=? LIMIT 1`, pl.SyncUUID).Scan(&localID)
		}
		if localID == 0 && strings.TrimSpace(pl.Name) != "" {
			_ = tx.QueryRow(`SELECT id FROM playlists WHERE name=? COLLATE NOCASE LIMIT 1`, strings.TrimSpace(pl.Name)).Scan(&localID)
		}
		if localID == 0 {
			res, err := tx.Exec(`INSERT INTO playlists(sync_uuid,name,created_at,last_played) VALUES(?,?,?,?)`,
				pl.SyncUUID, strings.TrimSpace(pl.Name), pl.CreatedAt, pl.LastPlayed)
			if err != nil {
				s.mu.Unlock()
				return err
			}
			localID, _ = res.LastInsertId()
		} else {
			if _, err := tx.Exec(`UPDATE playlists SET sync_uuid=?, name=?, last_played=? WHERE id=?`,
				pl.SyncUUID, strings.TrimSpace(pl.Name), pl.LastPlayed, localID); err != nil {
				s.mu.Unlock()
				return err
			}
		}
		if _, err := tx.Exec(`DELETE FROM playlist_tracks WHERE playlist_id=?`, localID); err != nil {
			s.mu.Unlock()
			return err
		}
		pos := 0
		seenInPl := map[string]bool{}
		for _, tid := range pl.TrackIDs {
			if !liveTracks[tid] || seenInPl[tid] {
				continue
			}
			seenInPl[tid] = true
			if _, err := tx.Exec(`INSERT INTO playlist_tracks(playlist_id,track_id,position) VALUES(?,?,?)`, localID, tid, pos); err != nil {
				s.mu.Unlock()
				return err
			}
			pos++
		}
	}

	if _, err := tx.Exec(`INSERT INTO settings(key,value) VALUES('synced_yt_collections',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value`, string(ytJSON)); err != nil {
		s.mu.Unlock()
		return err
	}

	now := time.Now().Unix()
	if peerID != "" && snapJSON != "" {
		if _, err := tx.Exec(`INSERT INTO sync_snapshots(peer_device_id,snapshot_json,synced_at) VALUES(?,?,?)
ON CONFLICT(peer_device_id) DO UPDATE SET snapshot_json=excluded.snapshot_json, synced_at=excluded.synced_at`,
			peerID, snapJSON, now); err != nil {
			s.mu.Unlock()
			return err
		}
		_, _ = tx.Exec(`UPDATE trusted_sync_peers SET last_sync_at=? WHERE device_id=?`, now, peerID)
	}

	if err := tx.Commit(); err != nil {
		s.mu.Unlock()
		return err
	}
	s.mu.Unlock()

	s.compactAll()
	for _, k := range toRemove {
		if k.audio != "" {
			_ = os.Remove(k.audio)
		}
		if k.lrc != "" {
			_ = os.Remove(k.lrc)
		}
		removeCoverAndThumbs(k.id)
	}
	return nil
}
