package main

import (
	"sort"
	"strings"
	"time"
)

type SyncPlan struct {
	PullAudioFromRemote  []SyncTrack
	PullCoverFromRemote  []string
	PullLyricsFromRemote []string
	PushAudioToRemote    []SyncTrack
	PushCoverToRemote    []string
	PushLyricsToRemote   []string
	DeleteOnLocal        []string
	DeleteOnRemote       []string
	LocalCommit          SyncCommitPayload
	RemoteCommit         SyncCommitPayload
	MergedSnapshot       SyncSnapshot
	SummaryLocalAdded    int
	SummaryLocalDeleted  int
	SummaryRemoteAdded   int
	SummaryRemoteDeleted int
	SummaryUpdated       int
	SummaryPlaylists     int
}

func genreSourceRank(src string) int {
	switch src {
	case "user":
		return 6
	case "ai":
		return 5
	case "tag":
		return 4
	case "online":
		return 3
	case "inferred":
		return 2
	case "heuristic":
		return 1
	default:
		return 0
	}
}

func minPositive64(a, b int64) int64 {
	if a <= 0 {
		return b
	}
	if b <= 0 {
		return a
	}
	if a < b {
		return a
	}
	return b
}

func max64(a, b int64) int64 {
	if a > b {
		return a
	}
	return b
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func sliceEqual(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func dedupStrings(in []string) []string {
	seen := make(map[string]bool, len(in))
	out := make([]string, 0, len(in))
	for _, s := range in {
		if s != "" && !seen[s] {
			seen[s] = true
			out = append(out, s)
		}
	}
	return out
}

func filterPlaylistIDs(ids []string, surviving map[string]bool) []string {
	seen := make(map[string]bool, len(ids))
	out := make([]string, 0, len(ids))
	for _, id := range ids {
		if surviving[id] && !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}

func mergeThreeWayBool(hasBase bool, baseVal, localVal, remoteVal bool, localUpd, remoteUpd int64) bool {
	if !hasBase {
		return localVal || remoteVal
	}
	lCh := localVal != baseVal
	rCh := remoteVal != baseVal
	if lCh && !rCh {
		return localVal
	}
	if rCh && !lCh {
		return remoteVal
	}
	if lCh && rCh {
		if localVal == remoteVal {
			return localVal
		}
		if remoteUpd > localUpd {
			return remoteVal
		}
		return localVal
	}
	return baseVal
}

func mergeThreeWayString(hasBase bool, baseVal, localVal, remoteVal string, localUpd, remoteUpd int64) string {
	if !hasBase {
		if localVal != "" && remoteVal != "" {
			if remoteUpd > localUpd {
				return remoteVal
			}
			return localVal
		}
		if localVal != "" {
			return localVal
		}
		return remoteVal
	}
	lCh := localVal != baseVal
	rCh := remoteVal != baseVal
	if lCh && !rCh {
		return localVal
	}
	if rCh && !lCh {
		return remoteVal
	}
	if lCh && rCh {
		if remoteUpd > localUpd && remoteVal != "" {
			return remoteVal
		}
		if localVal != "" {
			return localVal
		}
		return remoteVal
	}
	return baseVal
}

func mergeThreeWayGenres(hasBase bool, baseG, baseSrc string, l, r SyncTrack) (string, string) {
	if !hasBase {
		lr, rr := genreSourceRank(l.GenreSource), genreSourceRank(r.GenreSource)
		if lr != rr {
			if rr > lr && (r.Genre != "" || r.GenreSource == "user") {
				return r.Genre, r.GenreSource
			}
			if l.Genre != "" || l.GenreSource == "user" {
				return l.Genre, l.GenreSource
			}
		}
		if r.Genre != "" && l.Genre == "" {
			return r.Genre, r.GenreSource
		}
		if l.Genre != "" && r.Genre == "" {
			return l.Genre, l.GenreSource
		}
		if r.UpdatedAt > l.UpdatedAt && r.Genre != "" {
			return r.Genre, r.GenreSource
		}
		return l.Genre, l.GenreSource
	}

	lCh := l.Genre != baseG || l.GenreSource != baseSrc
	rCh := r.Genre != baseG || r.GenreSource != baseSrc
	if lCh && !rCh {
		return l.Genre, l.GenreSource
	}
	if rCh && !lCh {
		return r.Genre, r.GenreSource
	}
	if lCh && rCh {
		lr, rr := genreSourceRank(l.GenreSource), genreSourceRank(r.GenreSource)
		if rr > lr {
			return r.Genre, r.GenreSource
		}
		if lr > rr {
			return l.Genre, l.GenreSource
		}
		if r.UpdatedAt > l.UpdatedAt {
			return r.Genre, r.GenreSource
		}
		return l.Genre, l.GenreSource
	}
	return baseG, baseSrc
}

func countHistoryPerTrack(events []SyncPlayEvent) map[string]int {
	m := make(map[string]int, len(events))
	for _, ev := range events {
		if ev.TrackID != "" {
			m[ev.TrackID]++
		}
	}
	return m
}

func ComputeSyncPlan(local, remote SyncManifest, base *SyncSnapshot, syncDeletions bool) SyncPlan {
	now := time.Now().Unix()
	plan := SyncPlan{
		MergedSnapshot: SyncSnapshot{
			SyncedAt:  now,
			Tracks:    map[string]SyncSnapshotTrack{},
			Playlists: map[string]SyncSnapshotPlaylist{},
		},
	}

	localTracks := make(map[string]SyncTrack, len(local.Tracks))
	for _, t := range local.Tracks {
		localTracks[t.ID] = t
	}
	remoteTracks := make(map[string]SyncTrack, len(remote.Tracks))
	for _, t := range remote.Tracks {
		remoteTracks[t.ID] = t
	}

	localHidden := make(map[string]bool, len(local.HiddenTrackIDs))
	for _, id := range local.HiddenTrackIDs {
		localHidden[id] = true
	}
	remoteHidden := make(map[string]bool, len(remote.HiddenTrackIDs))
	for _, id := range remote.HiddenTrackIDs {
		remoteHidden[id] = true
	}

	allTrackIDs := map[string]bool{}
	for id := range localTracks {
		allTrackIDs[id] = true
	}
	for id := range remoteTracks {
		allTrackIDs[id] = true
	}
	if base != nil {
		for id := range base.Tracks {
			allTrackIDs[id] = true
		}
	}

	// Merge play history first (union deduplicated by (trackId, playedAt))
	type histKey struct {
		id string
		at int64
	}
	histSeen := map[histKey]bool{}
	var mergedHist []SyncPlayEvent
	for _, ev := range local.PlayHistory {
		k := histKey{ev.TrackID, ev.PlayedAt}
		if ev.TrackID != "" && ev.PlayedAt > 0 && !histSeen[k] {
			histSeen[k] = true
			mergedHist = append(mergedHist, ev)
		}
	}
	for _, ev := range remote.PlayHistory {
		k := histKey{ev.TrackID, ev.PlayedAt}
		if ev.TrackID != "" && ev.PlayedAt > 0 && !histSeen[k] {
			histSeen[k] = true
			mergedHist = append(mergedHist, ev)
		}
	}

	localHistCnt := countHistoryPerTrack(local.PlayHistory)
	remoteHistCnt := countHistoryPerTrack(remote.PlayHistory)
	unionHistCnt := countHistoryPerTrack(mergedHist)

	sortedIDs := make([]string, 0, len(allTrackIDs))
	for id := range allTrackIDs {
		sortedIDs = append(sortedIDs, id)
	}
	sort.Strings(sortedIDs)

	surviving := map[string]bool{}
	var mergedTracks []SyncTrack
	var mergedHidden []string

	for _, id := range sortedIDs {
		lt, inL := localTracks[id]
		rt, inR := remoteTracks[id]
		var bt SyncSnapshotTrack
		inBase := false
		if base != nil {
			bt, inBase = base.Tracks[id]
		}

		if syncDeletions && inBase {
			if !inL && inR {
				if !local.LibraryUnmounted {
					plan.DeleteOnRemote = append(plan.DeleteOnRemote, id)
					continue
				}
			}
			if !inR && inL {
				if !remote.LibraryUnmounted {
					plan.DeleteOnLocal = append(plan.DeleteOnLocal, id)
					continue
				}
			}
			if !inL && !inR {
				continue
			}
		} else if !inL && !inR {
			continue
		}

		surviving[id] = true

		var merged SyncTrack
		if inL && !inR {
			merged = lt
			if _, ok := localHidden[id]; ok {
				merged.Hidden = true
			}
			plan.PushAudioToRemote = append(plan.PushAudioToRemote, merged)
			if lt.HasCoverFile {
				plan.PushCoverToRemote = append(plan.PushCoverToRemote, id)
			}
			if lt.HasLyricsFile {
				plan.PushLyricsToRemote = append(plan.PushLyricsToRemote, id)
			}
			plan.SummaryRemoteAdded++
		} else if inR && !inL {
			merged = rt
			if _, ok := remoteHidden[id]; ok {
				merged.Hidden = true
			}
			plan.PullAudioFromRemote = append(plan.PullAudioFromRemote, merged)
			if rt.HasCoverFile {
				plan.PullCoverFromRemote = append(plan.PullCoverFromRemote, id)
			}
			if rt.HasLyricsFile {
				plan.PullLyricsFromRemote = append(plan.PullLyricsFromRemote, id)
			}
			plan.SummaryLocalAdded++
		} else {
			// Present on both sides: merge metadata & check auxiliary cover/lyrics
			merged = lt
			if rt.YtID != "" && merged.YtID == "" {
				merged.YtID = rt.YtID
			}
			merged.Title = mergeThreeWayString(inBase, bt.Title, lt.Title, rt.Title, lt.UpdatedAt, rt.UpdatedAt)
			merged.Artist = mergeThreeWayString(inBase, bt.Artist, lt.Artist, rt.Artist, lt.UpdatedAt, rt.UpdatedAt)
			merged.Album = mergeThreeWayString(inBase, bt.Album, lt.Album, rt.Album, lt.UpdatedAt, rt.UpdatedAt)
			if inBase {
				if lt.Year != bt.Year && rt.Year == bt.Year {
					merged.Year = lt.Year
				} else if rt.Year != bt.Year && lt.Year == bt.Year {
					merged.Year = rt.Year
				} else if rt.Year > 0 && lt.Year == 0 {
					merged.Year = rt.Year
				}
			} else if lt.Year == 0 && rt.Year > 0 {
				merged.Year = rt.Year
			}

			merged.AddedAt = minPositive64(lt.AddedAt, rt.AddedAt)
			merged.LastPlayed = max64(lt.LastPlayed, rt.LastPlayed)
			if inBase {
				dL := maxInt(0, lt.PlayCount-bt.PlayCount)
				dR := maxInt(0, rt.PlayCount-bt.PlayCount)
				merged.PlayCount = bt.PlayCount + dL + dR
			} else {
				extraL := maxInt(0, lt.PlayCount-localHistCnt[id])
				extraR := maxInt(0, rt.PlayCount-remoteHistCnt[id])
				merged.PlayCount = maxInt(unionHistCnt[id]+extraL+extraR, maxInt(lt.PlayCount, rt.PlayCount))
			}

			merged.Liked = mergeThreeWayBool(inBase, bt.Liked, lt.Liked, rt.Liked, lt.UpdatedAt, rt.UpdatedAt)
			merged.Hidden = mergeThreeWayBool(inBase, bt.Hidden, lt.Hidden || localHidden[id], rt.Hidden || remoteHidden[id], lt.UpdatedAt, rt.UpdatedAt)
			merged.Genre, merged.GenreSource = mergeThreeWayGenres(inBase, bt.Genre, bt.GenreSource, lt, rt)
			merged.UpdatedAt = max64(lt.UpdatedAt, rt.UpdatedAt)
			if merged.UpdatedAt == 0 {
				merged.UpdatedAt = now
			}
			merged.HasCover = lt.HasCover || rt.HasCover || lt.HasCoverFile || rt.HasCoverFile

			if lt.HasCoverFile && !rt.HasCoverFile {
				plan.PushCoverToRemote = append(plan.PushCoverToRemote, id)
			} else if rt.HasCoverFile && !lt.HasCoverFile {
				plan.PullCoverFromRemote = append(plan.PullCoverFromRemote, id)
			}
			if lt.HasLyricsFile && !rt.HasLyricsFile {
				plan.PushLyricsToRemote = append(plan.PushLyricsToRemote, id)
			} else if rt.HasLyricsFile && !lt.HasLyricsFile {
				plan.PullLyricsFromRemote = append(plan.PullLyricsFromRemote, id)
			}
			if merged.Title != lt.Title || merged.Artist != lt.Artist || merged.Album != lt.Album ||
				merged.Liked != lt.Liked || merged.PlayCount != lt.PlayCount || merged.Genre != lt.Genre ||
				merged.GenreSource != lt.GenreSource || merged.Hidden != lt.Hidden {
				plan.SummaryUpdated++
			}
		}

		if merged.Hidden {
			mergedHidden = append(mergedHidden, id)
		}
		mergedTracks = append(mergedTracks, merged)
		plan.MergedSnapshot.Tracks[id] = SyncSnapshotTrack{
			ID:          id,
			Title:       merged.Title,
			Artist:      merged.Artist,
			Album:       merged.Album,
			Year:        merged.Year,
			Liked:       merged.Liked,
			PlayCount:   merged.PlayCount,
			LastPlayed:  merged.LastPlayed,
			Genre:       merged.Genre,
			GenreSource: merged.GenreSource,
			Hidden:      merged.Hidden,
			UpdatedAt:   merged.UpdatedAt,
		}
	}

	// Filter merged history to surviving tracks
	filteredHist := make([]SyncPlayEvent, 0, len(mergedHist))
	for _, ev := range mergedHist {
		if surviving[ev.TrackID] {
			filteredHist = append(filteredHist, ev)
		}
	}

	// Playlists: index by SyncUUID, then unify un-synced same-named playlists
	localPls := map[string]SyncPlaylist{}
	localByName := map[string]string{}
	for _, p := range local.Playlists {
		u := p.SyncUUID
		if u == "" {
			u = "name:" + strings.ToLower(strings.TrimSpace(p.Name))
			p.SyncUUID = u
		}
		localPls[u] = p
		nk := strings.ToLower(strings.TrimSpace(p.Name))
		if nk != "" {
			localByName[nk] = u
		}
	}
	remotePls := map[string]SyncPlaylist{}
	for _, p := range remote.Playlists {
		u := p.SyncUUID
		if u == "" {
			u = "name:" + strings.ToLower(strings.TrimSpace(p.Name))
		}
		if _, hasUUID := localPls[u]; !hasUUID {
			nk := strings.ToLower(strings.TrimSpace(p.Name))
			if lu, ok := localByName[nk]; ok {
				// First-time match by case-insensitive name unless that remote UUID was in base
				inBase := base != nil && base.Playlists[u].SyncUUID != ""
				if !inBase {
					u = lu
				}
			}
		}
		p.SyncUUID = u
		remotePls[u] = p
	}

	allPlUUIDs := map[string]bool{}
	for u := range localPls {
		allPlUUIDs[u] = true
	}
	for u := range remotePls {
		allPlUUIDs[u] = true
	}
	if base != nil {
		for u := range base.Playlists {
			allPlUUIDs[u] = true
		}
	}
	sortedPlUUIDs := make([]string, 0, len(allPlUUIDs))
	for u := range allPlUUIDs {
		sortedPlUUIDs = append(sortedPlUUIDs, u)
	}
	sort.Strings(sortedPlUUIDs)

	var mergedPlaylists []SyncPlaylist
	var delLocalPls []string
	var delRemotePls []string

	for _, u := range sortedPlUUIDs {
		lp, inL := localPls[u]
		rp, inR := remotePls[u]
		var bp SyncSnapshotPlaylist
		inBase := false
		if base != nil {
			bp, inBase = base.Playlists[u]
		}
		if syncDeletions && inBase {
			if !inL && inR && !local.LibraryUnmounted {
				delRemotePls = append(delRemotePls, u)
				continue
			}
			if !inR && inL && !remote.LibraryUnmounted {
				delLocalPls = append(delLocalPls, u)
				continue
			}
			if !inL && !inR {
				continue
			}
		} else if !inL && !inR {
			continue
		}

		var mpl SyncPlaylist
		mpl.SyncUUID = u
		if inL && !inR {
			mpl = lp
			mpl.TrackIDs = filterPlaylistIDs(lp.TrackIDs, surviving)
		} else if inR && !inL {
			mpl = rp
			mpl.TrackIDs = filterPlaylistIDs(rp.TrackIDs, surviving)
		} else {
			mpl.CreatedAt = minPositive64(lp.CreatedAt, rp.CreatedAt)
			mpl.LastPlayed = max64(lp.LastPlayed, rp.LastPlayed)
			mpl.Name = mergeThreeWayString(inBase, bp.Name, lp.Name, rp.Name, lp.LastPlayed, rp.LastPlayed)
			if !inBase {
				combined := append(append([]string{}, lp.TrackIDs...), rp.TrackIDs...)
				mpl.TrackIDs = filterPlaylistIDs(combined, surviving)
			} else {
				lCh := !sliceEqual(lp.TrackIDs, bp.TrackIDs)
				rCh := !sliceEqual(rp.TrackIDs, bp.TrackIDs)
				if lCh && !rCh {
					mpl.TrackIDs = filterPlaylistIDs(lp.TrackIDs, surviving)
				} else if rCh && !lCh {
					mpl.TrackIDs = filterPlaylistIDs(rp.TrackIDs, surviving)
				} else if !lCh && !rCh {
					mpl.TrackIDs = filterPlaylistIDs(lp.TrackIDs, surviving)
				} else {
					// Both modified: preserve local order, drop tracks removed by remote, append tracks added by remote
					inBaseSet := map[string]bool{}
					for _, tid := range bp.TrackIDs {
						inBaseSet[tid] = true
					}
					inRemoteSet := map[string]bool{}
					for _, tid := range rp.TrackIDs {
						inRemoteSet[tid] = true
					}
					var combined []string
					for _, tid := range lp.TrackIDs {
						if inBaseSet[tid] && !inRemoteSet[tid] {
							continue // removed by remote
						}
						combined = append(combined, tid)
					}
					for _, tid := range rp.TrackIDs {
						if !inBaseSet[tid] {
							combined = append(combined, tid)
						}
					}
					mpl.TrackIDs = filterPlaylistIDs(combined, surviving)
				}
			}
		}
		mergedPlaylists = append(mergedPlaylists, mpl)
		plan.MergedSnapshot.Playlists[u] = SyncSnapshotPlaylist{
			SyncUUID:   u,
			Name:       mpl.Name,
			LastPlayed: mpl.LastPlayed,
			TrackIDs:   mpl.TrackIDs,
		}
	}

	// Merge artist_genres
	agMap := map[string]SyncArtistGenre{}
	for _, ag := range local.ArtistGenres {
		agMap[ag.ArtistKey] = ag
	}
	for _, ag := range remote.ArtistGenres {
		if cur, ok := agMap[ag.ArtistKey]; !ok || ag.CheckedAt > cur.CheckedAt || (ag.CheckedAt == cur.CheckedAt && ag.Genre != "" && cur.Genre == "") {
			agMap[ag.ArtistKey] = ag
		}
	}
	mergedAG := make([]SyncArtistGenre, 0, len(agMap))
	for _, ag := range agMap {
		mergedAG = append(mergedAG, ag)
	}

	// Merge track_context
	tcMap := map[string]SyncTrackContext{}
	for _, tc := range local.TrackContexts {
		tcMap[tc.CacheKey] = tc
	}
	for _, tc := range remote.TrackContexts {
		if cur, ok := tcMap[tc.CacheKey]; !ok || tc.UpdatedAt > cur.UpdatedAt {
			tcMap[tc.CacheKey] = tc
		}
	}
	mergedTC := make([]SyncTrackContext, 0, len(tcMap))
	for _, tc := range tcMap {
		mergedTC = append(mergedTC, tc)
	}

	mergedYTM := dedupStrings(append(append([]string{}, local.SyncedYtCollections...), remote.SyncedYtCollections...))

	plan.SummaryLocalDeleted = len(plan.DeleteOnLocal)
	plan.SummaryRemoteDeleted = len(plan.DeleteOnRemote)
	plan.SummaryPlaylists = len(mergedPlaylists)

	plan.LocalCommit = SyncCommitPayload{
		SenderDeviceID:       remote.DeviceID,
		SenderDeviceName:     remote.DeviceName,
		SenderPlatform:       remote.Platform,
		TracksMeta:           mergedTracks,
		DeletedTrackIDs:      plan.DeleteOnLocal,
		Playlists:            mergedPlaylists,
		DeletedPlaylistUUIDs: delLocalPls,
		PlayHistory:          filteredHist,
		HiddenTrackIDs:       mergedHidden,
		ArtistGenres:         mergedAG,
		TrackContexts:        mergedTC,
		SyncedYtCollections:  mergedYTM,
		NewSnapshot:          plan.MergedSnapshot,
	}

	plan.RemoteCommit = SyncCommitPayload{
		SenderDeviceID:       local.DeviceID,
		SenderDeviceName:     local.DeviceName,
		SenderPlatform:       local.Platform,
		TracksMeta:           mergedTracks,
		DeletedTrackIDs:      plan.DeleteOnRemote,
		Playlists:            mergedPlaylists,
		DeletedPlaylistUUIDs: delRemotePls,
		PlayHistory:          filteredHist,
		HiddenTrackIDs:       mergedHidden,
		ArtistGenres:         mergedAG,
		TrackContexts:        mergedTC,
		SyncedYtCollections:  mergedYTM,
		NewSnapshot:          plan.MergedSnapshot,
	}

	return plan
}
