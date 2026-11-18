package com.vibemusic.nativeapp.data

import kotlin.math.max
import kotlin.math.min

object RecommendationEngine {

    private fun hashId(id: String): Long {
        var h = 0L
        for (c in id) {
            h = (h * 31L + c.code.toLong()) and 0xFFFFFFFFL
        }
        return h
    }

    /**
     * Builds Quick Picks:
     * High play count, liked, recently played, with artist spreading so the initial 3x3
     * strip has great variety.
     */
    fun buildQuickPicks(allTracks: List<Track>, seed: Int = 0): List<Track> {
        if (allTracks.isEmpty()) return emptyList()
        val now = System.currentTimeMillis() / 1000

        data class ScoredItem(val track: Track, val score: Double)

        val scored = allTracks.map { t ->
            var s = 0.0
            if (t.playCount > 0) s += min(t.playCount * 14.0, 90.0)
            if (t.liked) s += 45.0
            if (t.lastPlayed > 0) {
                val ageDays = max(0.0, (now - t.lastPlayed).toDouble() / 86400.0)
                s += max(0.0, 60.0 - ageDays * 3.0)
            }
            val h = (((hashId(t.id) * 2654435761L) xor ((seed + 1L) * 1013904223L)) and 0xFFFFFFFFL).toDouble() / 4294967295.0
            s += h * 40.0
            ScoredItem(t, s)
        }.sortedByDescending { it.score }

        // Spread same-artist tracks so the initial grid has high diversity
        val out = mutableListOf<Track>()
        val deferred = mutableListOf<Track>()
        val recentArtistCount = mutableMapOf<String, Int>()

        for (item in scored) {
            val primaryArtist = CanonicalKey.norm(CanonicalKey.splitArtists(item.track.artist).firstOrNull() ?: "")
            val count = recentArtistCount.getOrDefault(primaryArtist, 0)
            if (count < 2) {
                out.add(item.track)
                recentArtistCount[primaryArtist] = count + 1
            } else {
                deferred.add(item.track)
            }
        }
        out.addAll(deferred)
        return out
    }

    /**
     * Builds radio mixes:
     * Mode: "all", "familiar", "rediscover", "artist"
     */
    fun buildRadioMix(
        seedTrack: Track,
        allTracks: List<Track>,
        playlistTrackMap: Map<Long, List<String>> = emptyMap(),
        mode: String = "all",
        limit: Int = 25
    ): List<Track> {
        val seedArtists = CanonicalKey.splitArtists(seedTrack.artist).map { CanonicalKey.norm(it) }.toSet()
        val seedAlbum = CanonicalKey.norm(seedTrack.album)
        val seedTokens = CanonicalKey.titleTokens(seedTrack.title).toSet()

        val cooccur = mutableMapOf<String, Int>()
        for (tids in playlistTrackMap.values) {
            if (tids.contains(seedTrack.id)) {
                for (id in tids) {
                    if (id != seedTrack.id) {
                        cooccur[id] = cooccur.getOrDefault(id, 0) + 1
                    }
                }
            }
        }

        var pool = allTracks.filter { it.id != seedTrack.id }
        if (mode == "artist") {
            val sameArt = pool.filter { t ->
                CanonicalKey.splitArtists(t.artist).any { seedArtists.contains(CanonicalKey.norm(it)) }
            }
            if (sameArt.isNotEmpty()) pool = sameArt
        } else if (mode == "familiar") {
            val fam = pool.filter { it.playCount > 0 || it.liked }
            if (fam.isNotEmpty()) pool = fam
        } else if (mode == "rediscover") {
            val now = System.currentTimeMillis() / 1000
            val deep = pool.filter { it.playCount <= 1 || (now - it.lastPlayed > 7 * 86400) }
            if (deep.isNotEmpty()) pool = deep
        }

        data class ScoredItem(val track: Track, val score: Double)

        val scored = pool.map { c ->
            var score = 0.0
            val cArtists = CanonicalKey.splitArtists(c.artist).map { CanonicalKey.norm(it) }
            val sharedArt = cArtists.count { seedArtists.contains(it) }
            if (sharedArt > 0) {
                score += if (mode == "artist") 140.0 else (65.0 * sharedArt)
            }
            val co = cooccur.getOrDefault(c.id, 0)
            if (co > 0) score += 50.0 * min(co, 3)
            if (seedAlbum.isNotEmpty() && CanonicalKey.norm(c.album) == seedAlbum) score += 45.0
            for (tok in CanonicalKey.titleTokens(c.title)) {
                if (seedTokens.contains(tok)) score += 20.0
            }

            if (mode == "familiar") {
                score += min(c.playCount * 10.0, 60.0) + (if (c.liked) 35.0 else 0.0)
            } else if (mode == "rediscover") {
                if (c.playCount == 0) score += 45.0
                if (c.liked) score += 20.0
            } else {
                if (c.liked) score += 18.0
                score += min(c.playCount * 4.0, 24.0)
            }

            val h = (((hashId(c.id) * 2654435761L) xor (hashId(seedTrack.id) * 1013904223L)) and 0xFFFFFFFFL).toDouble() / 4294967295.0
            score += h * 32.0
            ScoredItem(c, score)
        }.sortedByDescending { it.score }

        val result = mutableListOf<Track>()
        result.add(seedTrack)
        result.addAll(scored.take(max(0, limit - 1)).map { it.track })
        return result
    }

    /**
     * Recommends tracks for a playlist (co-occurrence and genre/token similarity)
     */
    fun recommendForPlaylist(
        playlistTracks: List<Track>,
        allTracks: List<Track>,
        seed: Int = 0,
        limit: Int = 8,
        playlistTrackMap: Map<Long, List<String>> = emptyMap()
    ): List<Track> {
        val inPlaylist = playlistTracks.map { it.id }.toSet()
        val candidates = allTracks.filter { !inPlaylist.contains(it.id) }
        if (candidates.isEmpty()) return emptyList()

        val artistFreq = mutableMapOf<String, Int>()
        val albumFreq = mutableMapOf<String, Int>()
        val tokenFreq = mutableMapOf<String, Int>()
        val cooccurFreq = mutableMapOf<String, Int>()

        for (t in playlistTracks) {
            for (a in CanonicalKey.splitArtists(t.artist)) {
                if (a.isNotEmpty() && a != "Unknown Artist") {
                    val k = CanonicalKey.norm(a)
                    artistFreq[k] = artistFreq.getOrDefault(k, 0) + 1
                }
            }
            if (t.album.isNotEmpty()) {
                val ak = CanonicalKey.norm(t.album)
                albumFreq[ak] = albumFreq.getOrDefault(ak, 0) + 1
            }
            for (tok in CanonicalKey.titleTokens(t.title)) {
                tokenFreq[tok] = tokenFreq.getOrDefault(tok, 0) + 1
            }
        }

        for (tids in playlistTrackMap.values) {
            if (tids.size < 2) continue
            val overlap = tids.count { inPlaylist.contains(it) }
            if (overlap > 0) {
                val weight = min(overlap, 4)
                for (id in tids) {
                    if (!inPlaylist.contains(id)) {
                        cooccurFreq[id] = cooccurFreq.getOrDefault(id, 0) + weight
                    }
                }
            }
        }

        data class ScoredItem(val track: Track, val score: Double)

        val scored = candidates.map { c ->
            var score = 0.0
            for (a in CanonicalKey.splitArtists(c.artist)) {
                val f = artistFreq.getOrDefault(CanonicalKey.norm(a), 0)
                if (f > 0) score += 55.0 * min(f, 3)
            }
            val co = cooccurFreq.getOrDefault(c.id, 0)
            if (co > 0) score += 42.0 * min(co, 4)
            if (c.album.isNotEmpty()) {
                val f = albumFreq.getOrDefault(CanonicalKey.norm(c.album), 0)
                if (f > 0) score += 38.0 * min(f, 3)
            }
            for (tok in CanonicalKey.titleTokens(c.title)) {
                val f = tokenFreq.getOrDefault(tok, 0)
                if (f > 0) score += 16.0 * min(f, 2)
            }
            if (c.liked) score += 12.0
            score += min(c.playCount * 3.0, 18.0)

            val hash = (((hashId(c.id) * 2654435761L) xor ((seed + 1L) * 1013904223L)) and 0xFFFFFFFFL).toDouble() / 4294967295.0
            val jitter = if (seed == 0) (if (playlistTracks.isNotEmpty()) 28.0 else 50.0) else 260.0
            score += hash * jitter
            ScoredItem(c, score)
        }.sortedByDescending { it.score }

        return scored.take(limit).map { it.track }
    }

    data class ArtistAffinity(
        val artist: String,
        val normArtist: String,
        val score: Double,
        val listenSeconds: Double,
        val topTracks: List<Track>
    )

    fun scoreTrackAffinity(
        track: Track,
        nowSec: Long = System.currentTimeMillis() / 1000,
        recentPlays7d: Int = 0,
        recentPlays30d: Int = 0,
        playlistCount: Int = 0
    ): Double {
        val effDuration = if (track.duration > 30.0) track.duration else 180.0
        val listenMins = (track.playCount * effDuration) / 60.0
        var s = 0.0
        s += min(listenMins * 15.0, 300.0)
        s += min(track.playCount * 14.0, 140.0)
        s += min(recentPlays7d * 40.0 + recentPlays30d * 18.0, 220.0)
        if (track.lastPlayed > 0) {
            val ageDays = max(0.0, (nowSec - track.lastPlayed).toDouble() / 86400.0)
            s += max(0.0, 120.0 - ageDays * 4.5)
        }
        if (track.liked) s += 75.0
        s += min(playlistCount * 22.0, 66.0)
        val addedAgeDays = if (track.addedAt > 0) max(0.0, (nowSec - track.addedAt).toDouble() / 86400.0) else 365.0
        s += 15.0 + max(0.0, 25.0 - addedAgeDays * 0.5)
        return s
    }

    fun rankLibraryArtistsByAffinity(
        allTracks: List<Track>,
        recentPlays7d: Map<String, Int> = emptyMap(),
        recentPlays30d: Map<String, Int> = emptyMap(),
        playlistTrackMap: Map<Long, List<String>> = emptyMap()
    ): List<ArtistAffinity> {
        if (allTracks.isEmpty()) return emptyList()
        val nowSec = System.currentTimeMillis() / 1000

        val playlistCount = mutableMapOf<String, Int>()
        for (tids in playlistTrackMap.values) {
            for (id in tids.distinct()) {
                playlistCount[id] = playlistCount.getOrDefault(id, 0) + 1
            }
        }

        val displayByNorm = mutableMapOf<String, String>()
        val tracksByNorm = mutableMapOf<String, MutableList<Track>>()
        val seenTrackPerArtist = mutableMapOf<String, MutableSet<String>>()

        for (t in allTracks) {
            for (rawArt in CanonicalKey.splitArtists(t.artist)) {
                val trimmed = rawArt.trim()
                if (trimmed.isEmpty() || trimmed.equals("Unknown Artist", ignoreCase = true)) continue
                val normKey = CanonicalKey.norm(trimmed)
                if (normKey.isEmpty()) continue
                displayByNorm.putIfAbsent(normKey, trimmed)
                val seenIds = seenTrackPerArtist.getOrPut(normKey) { mutableSetOf() }
                if (seenIds.add(t.id)) {
                    tracksByNorm.getOrPut(normKey) { mutableListOf() }.add(t)
                }
            }
        }

        return tracksByNorm.map { (normKey, tracks) ->
            val totalListenSeconds = tracks.sumOf {
                it.playCount * (if (it.duration > 30.0) it.duration else 180.0)
            }
            val totalRecent7d = tracks.sumOf { recentPlays7d[it.id] ?: 0 }
            val totalRecent30d = tracks.sumOf { recentPlays30d[it.id] ?: 0 }
            val likedCount = tracks.count { it.liked }
            val maxLastPlayed = tracks.maxOfOrNull { it.lastPlayed } ?: 0L
            val inPlaylists = tracks.sumOf { playlistCount[it.id] ?: 0 }

            var artistScore = 0.0
            artistScore += min((totalListenSeconds / 60.0) * 18.0, 400.0)
            artistScore += min(totalRecent7d * 45.0 + totalRecent30d * 20.0, 250.0)
            if (maxLastPlayed > 0) {
                val ageDays = max(0.0, (nowSec - maxLastPlayed).toDouble() / 86400.0)
                artistScore += max(0.0, 130.0 - ageDays * 4.5)
            }
            artistScore += min(likedCount * 40.0, 160.0)
            artistScore += min(inPlaylists * 18.0, 90.0)
            artistScore += min(tracks.size * 16.0, 96.0)

            val topTracks = tracks.sortedByDescending {
                scoreTrackAffinity(
                    track = it,
                    nowSec = nowSec,
                    recentPlays7d = recentPlays7d[it.id] ?: 0,
                    recentPlays30d = recentPlays30d[it.id] ?: 0,
                    playlistCount = playlistCount[it.id] ?: 0
                )
            }

            ArtistAffinity(
                artist = displayByNorm[normKey] ?: normKey,
                normArtist = normKey,
                score = artistScore,
                listenSeconds = totalListenSeconds,
                topTracks = topTracks
            )
        }.sortedByDescending { it.score }
    }
}
