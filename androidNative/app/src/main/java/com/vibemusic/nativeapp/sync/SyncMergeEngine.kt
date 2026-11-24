package com.vibemusic.nativeapp.sync

data class SyncPlan(
    val pullAudioFromRemote: List<SyncTrack>,
    val pullCoverFromRemote: List<String>,
    val pullLyricsFromRemote: List<String>,
    val pushAudioToRemote: List<SyncTrack>,
    val pushCoverToRemote: List<String>,
    val pushLyricsToRemote: List<String>,
    val deleteOnLocal: List<String>,
    val deleteOnRemote: List<String>,
    val localCommit: SyncCommitPayload,
    val remoteCommit: SyncCommitPayload,
    val mergedSnapshot: SyncSnapshot,
    val summaryLocalAdded: Int,
    val summaryLocalDeleted: Int,
    val summaryRemoteAdded: Int,
    val summaryRemoteDeleted: Int,
    val summaryUpdated: Int,
    val summaryPlaylists: Int
)

object SyncMergeEngine {

    private fun genreSourceRank(src: String): Int = when (src) {
        "user" -> 6
        "ai" -> 5
        "tag" -> 4
        "online" -> 3
        "inferred" -> 2
        "heuristic" -> 1
        else -> 0
    }

    private fun minPositive(a: Long, b: Long): Long = when {
        a <= 0L -> b
        b <= 0L -> a
        a < b -> a
        else -> b
    }

    private fun filterPlaylistIds(ids: List<String>, surviving: Set<String>): List<String> {
        val seen = HashSet<String>(ids.size)
        val out = ArrayList<String>(ids.size)
        for (id in ids) {
            if (surviving.contains(id) && seen.add(id)) {
                out.add(id)
            }
        }
        return out
    }

    private fun mergeThreeWayBool(
        hasBase: Boolean,
        baseVal: Boolean,
        localVal: Boolean,
        remoteVal: Boolean,
        localUpd: Long,
        remoteUpd: Long
    ): Boolean {
        if (!hasBase) return localVal || remoteVal
        val lCh = localVal != baseVal
        val rCh = remoteVal != baseVal
        if (lCh && !rCh) return localVal
        if (rCh && !lCh) return remoteVal
        if (lCh && rCh) {
            if (localVal == remoteVal) return localVal
            return if (remoteUpd > localUpd) remoteVal else localVal
        }
        return baseVal
    }

    private fun mergeThreeWayString(
        hasBase: Boolean,
        baseVal: String,
        localVal: String,
        remoteVal: String,
        localUpd: Long,
        remoteUpd: Long
    ): String {
        if (!hasBase) {
            if (localVal.isNotEmpty() && remoteVal.isNotEmpty()) {
                return if (remoteUpd > localUpd) remoteVal else localVal
            }
            return localVal.ifEmpty { remoteVal }
        }
        val lCh = localVal != baseVal
        val rCh = remoteVal != baseVal
        if (lCh && !rCh) return localVal
        if (rCh && !lCh) return remoteVal
        if (lCh && rCh) {
            if (remoteUpd > localUpd && remoteVal.isNotEmpty()) return remoteVal
            return localVal.ifEmpty { remoteVal }
        }
        return baseVal
    }

    private fun mergeThreeWayGenres(
        hasBase: Boolean,
        baseG: String,
        baseSrc: String,
        l: SyncTrack,
        r: SyncTrack
    ): Pair<String, String> {
        if (!hasBase) {
            val lr = genreSourceRank(l.genreSource)
            val rr = genreSourceRank(r.genreSource)
            if (lr != rr) {
                if (rr > lr && (r.genre.isNotEmpty() || r.genreSource == "user")) {
                    return r.genre to r.genreSource
                }
                if (l.genre.isNotEmpty() || l.genreSource == "user") {
                    return l.genre to l.genreSource
                }
            }
            if (r.genre.isNotEmpty() && l.genre.isEmpty()) return r.genre to r.genreSource
            if (l.genre.isNotEmpty() && r.genre.isEmpty()) return l.genre to l.genreSource
            if (r.updatedAt > l.updatedAt && r.genre.isNotEmpty()) return r.genre to r.genreSource
            return l.genre to l.genreSource
        }

        val lCh = l.genre != baseG || l.genreSource != baseSrc
        val rCh = r.genre != baseG || r.genreSource != baseSrc
        if (lCh && !rCh) return l.genre to l.genreSource
        if (rCh && !lCh) return r.genre to r.genreSource
        if (lCh && rCh) {
            val lr = genreSourceRank(l.genreSource)
            val rr = genreSourceRank(r.genreSource)
            if (rr > lr) return r.genre to r.genreSource
            if (lr > rr) return l.genre to l.genreSource
            return if (r.updatedAt > l.updatedAt) r.genre to r.genreSource else l.genre to l.genreSource
        }
        return baseG to baseSrc
    }

    fun computeSyncPlan(
        local: SyncManifest,
        remote: SyncManifest,
        base: SyncSnapshot?,
        syncDeletions: Boolean
    ): SyncPlan {
        val now = System.currentTimeMillis() / 1000L
        val localTracks = local.tracks.associateBy { it.id }
        val remoteTracks = remote.tracks.associateBy { it.id }
        val localHidden = local.hiddenTrackIds.toHashSet()
        val remoteHidden = remote.hiddenTrackIds.toHashSet()

        val allTrackIds = sortedSetOf<String>().apply {
            addAll(localTracks.keys)
            addAll(remoteTracks.keys)
            if (base != null) addAll(base.tracks.keys)
        }

        // Union play history deduplicated by (trackId, playedAt)
        val histSeen = HashSet<Pair<String, Long>>()
        val mergedHist = ArrayList<SyncPlayEvent>()
        for (ev in local.playHistory) {
            if (ev.trackId.isNotEmpty() && ev.playedAt > 0L && histSeen.add(ev.trackId to ev.playedAt)) {
                mergedHist.add(ev)
            }
        }
        for (ev in remote.playHistory) {
            if (ev.trackId.isNotEmpty() && ev.playedAt > 0L && histSeen.add(ev.trackId to ev.playedAt)) {
                mergedHist.add(ev)
            }
        }

        val localHistCnt = local.playHistory.groupingBy { it.trackId }.eachCount()
        val remoteHistCnt = remote.playHistory.groupingBy { it.trackId }.eachCount()
        val unionHistCnt = mergedHist.groupingBy { it.trackId }.eachCount()

        val pullAudio = ArrayList<SyncTrack>()
        val pullCover = ArrayList<String>()
        val pullLyrics = ArrayList<String>()
        val pushAudio = ArrayList<SyncTrack>()
        val pushCover = ArrayList<String>()
        val pushLyrics = ArrayList<String>()
        val deleteLocal = ArrayList<String>()
        val deleteRemote = ArrayList<String>()

        val surviving = HashSet<String>()
        val mergedTracks = ArrayList<SyncTrack>()
        val mergedHidden = ArrayList<String>()
        val snapTracks = HashMap<String, SyncSnapshotTrack>()

        var localAdded = 0
        var remoteAdded = 0
        var updatedCnt = 0

        for (id in allTrackIds) {
            val lt = localTracks[id]
            val rt = remoteTracks[id]
            val bt = base?.tracks?.get(id)
            val inL = lt != null
            val inR = rt != null
            val inBase = bt != null

            if (syncDeletions && inBase) {
                if (!inL && inR && !local.libraryUnmounted) {
                    deleteRemote.add(id)
                    continue
                }
                if (!inR && inL && !remote.libraryUnmounted) {
                    deleteLocal.add(id)
                    continue
                }
                if (!inL && !inR) continue
            } else if (!inL && !inR) {
                continue
            }

            surviving.add(id)
            val merged: SyncTrack
            if (lt != null && rt == null) {
                merged = lt.copy(hidden = lt.hidden || localHidden.contains(id))
                pushAudio.add(merged)
                if (lt.hasCoverFile) pushCover.add(id)
                if (lt.hasLyricsFile) pushLyrics.add(id)
                remoteAdded++
            } else if (rt != null && lt == null) {
                merged = rt.copy(hidden = rt.hidden || remoteHidden.contains(id))
                pullAudio.add(merged)
                if (rt.hasCoverFile) pullCover.add(id)
                if (rt.hasLyricsFile) pullLyrics.add(id)
                localAdded++
            } else {
                val l = lt!!
                val r = rt!!
                val ytId = l.ytId.ifEmpty { r.ytId }
                val title = mergeThreeWayString(inBase, bt?.title.orEmpty(), l.title, r.title, l.updatedAt, r.updatedAt)
                val artist = mergeThreeWayString(inBase, bt?.artist.orEmpty(), l.artist, r.artist, l.updatedAt, r.updatedAt)
                val album = mergeThreeWayString(inBase, bt?.album.orEmpty(), l.album, r.album, l.updatedAt, r.updatedAt)
                val year = if (bt != null) {
                    when {
                        l.year != bt.year && r.year == bt.year -> l.year
                        r.year != bt.year && l.year == bt.year -> r.year
                        r.year > 0 && l.year == 0 -> r.year
                        else -> l.year
                    }
                } else if (l.year == 0 && r.year > 0) r.year else l.year

                val addedAt = minPositive(l.addedAt, r.addedAt)
                val lastPlayed = maxOf(l.lastPlayed, r.lastPlayed)
                val playCount = if (bt != null) {
                    bt.playCount + maxOf(0, l.playCount - bt.playCount) + maxOf(0, r.playCount - bt.playCount)
                } else {
                    val extraL = maxOf(0, l.playCount - (localHistCnt[id] ?: 0))
                    val extraR = maxOf(0, r.playCount - (remoteHistCnt[id] ?: 0))
                    maxOf((unionHistCnt[id] ?: 0) + extraL + extraR, maxOf(l.playCount, r.playCount))
                }

                val liked = mergeThreeWayBool(inBase, bt?.liked == true, l.liked, r.liked, l.updatedAt, r.updatedAt)
                val hidden = mergeThreeWayBool(
                    inBase,
                    bt?.hidden == true,
                    l.hidden || localHidden.contains(id),
                    r.hidden || remoteHidden.contains(id),
                    l.updatedAt,
                    r.updatedAt
                )
                val (genre, genreSource) = mergeThreeWayGenres(inBase, bt?.genre.orEmpty(), bt?.genreSource.orEmpty(), l, r)
                val updAt = maxOf(l.updatedAt, r.updatedAt).let { if (it == 0L) now else it }
                val hasCover = l.hasCover || r.hasCover || l.hasCoverFile || r.hasCoverFile

                if (l.hasCoverFile && !r.hasCoverFile) pushCover.add(id)
                else if (r.hasCoverFile && !l.hasCoverFile) pullCover.add(id)

                if (l.hasLyricsFile && !r.hasLyricsFile) pushLyrics.add(id)
                else if (r.hasLyricsFile && !l.hasLyricsFile) pullLyrics.add(id)

                merged = l.copy(
                    ytId = ytId,
                    title = title,
                    artist = artist,
                    album = album,
                    year = year,
                    addedAt = addedAt,
                    liked = liked,
                    playCount = playCount,
                    lastPlayed = lastPlayed,
                    hasCover = hasCover,
                    updatedAt = updAt,
                    genre = genre,
                    genreSource = genreSource,
                    hidden = hidden
                )

                if (merged.title != l.title || merged.artist != l.artist || merged.album != l.album ||
                    merged.liked != l.liked || merged.playCount != l.playCount || merged.genre != l.genre ||
                    merged.genreSource != l.genreSource || merged.hidden != l.hidden
                ) {
                    updatedCnt++
                }
            }

            if (merged.hidden) mergedHidden.add(id)
            mergedTracks.add(merged)
            snapTracks[id] = SyncSnapshotTrack(
                id = id,
                title = merged.title,
                artist = merged.artist,
                album = merged.album,
                year = merged.year,
                liked = merged.liked,
                playCount = merged.playCount,
                lastPlayed = merged.lastPlayed,
                genre = merged.genre,
                genreSource = merged.genreSource,
                hidden = merged.hidden,
                updatedAt = merged.updatedAt
            )
        }

        val filteredHist = mergedHist.filter { surviving.contains(it.trackId) }

        // Playlists merge
        val localPls = HashMap<String, SyncPlaylist>()
        val localByName = HashMap<String, String>()
        for (rawPl in local.playlists) {
            val u = rawPl.syncUuid.ifEmpty { "name:${rawPl.name.trim().lowercase()}" }
            localPls[u] = rawPl.copy(syncUuid = u)
            val nk = rawPl.name.trim().lowercase()
            if (nk.isNotEmpty()) localByName[nk] = u
        }

        val remotePls = HashMap<String, SyncPlaylist>()
        for (rawPl in remote.playlists) {
            var u = rawPl.syncUuid.ifEmpty { "name:${rawPl.name.trim().lowercase()}" }
            if (!localPls.containsKey(u)) {
                val nk = rawPl.name.trim().lowercase()
                val matchedLocalUuid = localByName[nk]
                if (matchedLocalUuid != null) {
                    val inBase = base?.playlists?.containsKey(u) == true
                    if (!inBase) u = matchedLocalUuid
                }
            }
            remotePls[u] = rawPl.copy(syncUuid = u)
        }

        val allPlUuids = sortedSetOf<String>().apply {
            addAll(localPls.keys)
            addAll(remotePls.keys)
            if (base != null) addAll(base.playlists.keys)
        }

        val mergedPlaylists = ArrayList<SyncPlaylist>()
        val delLocalPls = ArrayList<String>()
        val delRemotePls = ArrayList<String>()
        val snapPlaylists = HashMap<String, SyncSnapshotPlaylist>()

        for (u in allPlUuids) {
            val lp = localPls[u]
            val rp = remotePls[u]
            val bp = base?.playlists?.get(u)
            val inBase = bp != null

            if (syncDeletions && inBase) {
                if (lp == null && rp != null && !local.libraryUnmounted) {
                    delRemotePls.add(u)
                    continue
                }
                if (rp == null && lp != null && !remote.libraryUnmounted) {
                    delLocalPls.add(u)
                    continue
                }
                if (lp == null && rp == null) continue
            } else if (lp == null && rp == null) {
                continue
            }

            val mpl = if (lp != null && rp == null) {
                lp.copy(trackIds = filterPlaylistIds(lp.trackIds, surviving))
            } else if (rp != null && lp == null) {
                rp.copy(trackIds = filterPlaylistIds(rp.trackIds, surviving))
            } else {
                val l = lp!!
                val r = rp!!
                val mName = mergeThreeWayString(inBase, bp?.name.orEmpty(), l.name, r.name, l.lastPlayed, r.lastPlayed)
                val mCreated = minPositive(l.createdAt, r.createdAt)
                val mLast = maxOf(l.lastPlayed, r.lastPlayed)
                val mTrackIds = if (bp == null) {
                    filterPlaylistIds(l.trackIds + r.trackIds, surviving)
                } else {
                    val lCh = l.trackIds != bp.trackIds
                    val rCh = r.trackIds != bp.trackIds
                    when {
                        lCh && !rCh -> filterPlaylistIds(l.trackIds, surviving)
                        rCh && !lCh -> filterPlaylistIds(r.trackIds, surviving)
                        !lCh && !rCh -> filterPlaylistIds(l.trackIds, surviving)
                        else -> {
                            val inBaseSet = bp.trackIds.toHashSet()
                            val inRemoteSet = r.trackIds.toHashSet()
                            val combined = ArrayList<String>()
                            for (tid in l.trackIds) {
                                if (inBaseSet.contains(tid) && !inRemoteSet.contains(tid)) continue
                                combined.add(tid)
                            }
                            for (tid in r.trackIds) {
                                if (!inBaseSet.contains(tid)) combined.add(tid)
                            }
                            filterPlaylistIds(combined, surviving)
                        }
                    }
                }
                SyncPlaylist(syncUuid = u, name = mName, createdAt = mCreated, lastPlayed = mLast, trackIds = mTrackIds)
            }

            mergedPlaylists.add(mpl)
            snapPlaylists[u] = SyncSnapshotPlaylist(
                syncUuid = u,
                name = mpl.name,
                lastPlayed = mpl.lastPlayed,
                trackIds = mpl.trackIds
            )
        }

        val agMap = HashMap<String, SyncArtistGenre>()
        for (ag in local.artistGenres) agMap[ag.artistKey] = ag
        for (ag in remote.artistGenres) {
            val cur = agMap[ag.artistKey]
            if (cur == null || ag.checkedAt > cur.checkedAt || (ag.checkedAt == cur.checkedAt && ag.genre.isNotEmpty() && cur.genre.isEmpty())) {
                agMap[ag.artistKey] = ag
            }
        }

        val tcMap = HashMap<String, SyncTrackContext>()
        for (tc in local.trackContexts) tcMap[tc.cacheKey] = tc
        for (tc in remote.trackContexts) {
            val cur = tcMap[tc.cacheKey]
            if (cur == null || tc.updatedAt > cur.updatedAt) {
                tcMap[tc.cacheKey] = tc
            }
        }

        val mergedYtm = (local.syncedYtCollections + remote.syncedYtCollections).filter { it.isNotBlank() }.distinct()
        val mergedSnapshot = SyncSnapshot(syncedAt = now, tracks = snapTracks, playlists = snapPlaylists)

        val localCommit = SyncCommitPayload(
            senderDeviceId = remote.deviceId,
            senderDeviceName = remote.deviceName,
            senderPlatform = remote.platform,
            tracksMeta = mergedTracks,
            deletedTrackIds = deleteLocal,
            playlists = mergedPlaylists,
            deletedPlaylistUuids = delLocalPls,
            playHistory = filteredHist,
            hiddenTrackIds = mergedHidden,
            artistGenres = agMap.values.toList(),
            trackContexts = tcMap.values.toList(),
            syncedYtCollections = mergedYtm,
            newSnapshot = mergedSnapshot
        )

        val remoteCommit = SyncCommitPayload(
            senderDeviceId = local.deviceId,
            senderDeviceName = local.deviceName,
            senderPlatform = local.platform,
            tracksMeta = mergedTracks,
            deletedTrackIds = deleteRemote,
            playlists = mergedPlaylists,
            deletedPlaylistUuids = delRemotePls,
            playHistory = filteredHist,
            hiddenTrackIds = mergedHidden,
            artistGenres = agMap.values.toList(),
            trackContexts = tcMap.values.toList(),
            syncedYtCollections = mergedYtm,
            newSnapshot = mergedSnapshot
        )

        return SyncPlan(
            pullAudioFromRemote = pullAudio,
            pullCoverFromRemote = pullCover,
            pullLyricsFromRemote = pullLyrics,
            pushAudioToRemote = pushAudio,
            pushCoverToRemote = pushCover,
            pushLyricsToRemote = pushLyrics,
            deleteOnLocal = deleteLocal,
            deleteOnRemote = deleteRemote,
            localCommit = localCommit,
            remoteCommit = remoteCommit,
            mergedSnapshot = mergedSnapshot,
            summaryLocalAdded = localAdded,
            summaryLocalDeleted = deleteLocal.size,
            summaryRemoteAdded = remoteAdded,
            summaryRemoteDeleted = deleteRemote.size,
            summaryUpdated = updatedCnt,
            summaryPlaylists = mergedPlaylists.size
        )
    }
}
