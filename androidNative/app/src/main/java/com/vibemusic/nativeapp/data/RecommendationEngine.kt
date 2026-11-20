package com.vibemusic.nativeapp.data

import com.vibemusic.nativeapp.genre.GenreRelation
import com.vibemusic.nativeapp.genre.GenreTaxonomy
import kotlin.math.max
import kotlin.math.min

object RecommendationEngine {

    private fun hashId(id: String): Long {
        var h = 2166136261L
        for (c in id) {
            h = (h xor c.code.toLong()) and 0xFFFFFFFFL
            h = (h * 16777619L) and 0xFFFFFFFFL
        }
        return h
    }

    /**
     * Quick Picks: most listened songs first (play count, then recency), then liked-but-unplayed songs,
     * then the rest ordered strictly by file tags (artist, album, title tokens) -> genre -> other signals,
     * with artist spreading for variety.
     */
    fun buildQuickPicks(
        allTracks: List<Track>,
        seed: Int = 0,
        playlistTrackMap: Map<Long, List<String>> = emptyMap()
    ): List<Track> {
        if (allTracks.isEmpty()) return emptyList()

        val inPlaylistCount = HashMap<String, Int>()
        for (tids in playlistTrackMap.values) {
            for (id in tids.distinct()) {
                inPlaylistCount[id] = (inPlaylistCount[id] ?: 0) + 1
            }
        }

        val played = allTracks
            .filter { it.playCount > 0 }
            .sortedWith(
                compareByDescending<Track> { it.playCount }
                    .thenByDescending { it.lastPlayed }
                    .thenByDescending { it.liked }
            )
        val rest = allTracks.filter { it.playCount == 0 }
        val liked = rest.filter { it.liked }
        val others = rest.filter { !it.liked }

        val artistWeight = HashMap<String, Int>()
        val albumWeight = HashMap<String, Int>()
        val tokenWeight = HashMap<String, Int>()
        val genreWeight = HashMap<String, Int>()
        var totalArtistW = 0
        var totalAlbumW = 0
        var totalTokenW = 0
        var totalGenreW = 0

        fun addProfileTrack(t: Track, w: Int) {
            for (a in CanonicalKey.splitArtists(t.artist)) {
                if (a.isNotBlank() && !a.equals("Unknown Artist", ignoreCase = true)) {
                    val k = CanonicalKey.norm(a)
                    artistWeight[k] = (artistWeight[k] ?: 0) + w
                    totalArtistW += w
                }
            }
            if (t.album.isNotBlank()) {
                val ak = CanonicalKey.norm(t.album)
                albumWeight[ak] = (albumWeight[ak] ?: 0) + w
                totalAlbumW += w
            }
            for (tok in CanonicalKey.metaTokens(t.title, t.album)) {
                tokenWeight[tok] = (tokenWeight[tok] ?: 0) + w
                totalTokenW += w
            }
            for (g in GenreTaxonomy.effectiveGenres(t, allTracks)) {
                genreWeight[g] = (genreWeight[g] ?: 0) + w
                totalGenreW += w
            }
        }

        var hasActivity = false
        for (t in allTracks) {
            val plW = inPlaylistCount[t.id] ?: 0
            val w = t.playCount * 2 + (if (t.liked) 3 else 0) + min(plW, 3)
            if (w > 0) {
                hasActivity = true
                addProfileTrack(t, w)
            }
        }
        if (!hasActivity) {
            for (t in allTracks) addProfileTrack(t, 1)
        }

        data class GenreStats(val pos: Double, val net: Double, val clashes: Boolean)

        val genreProfile = genreWeight.entries.toList()
        fun genreStats(gs: List<String>): GenreStats {
            if (gs.isEmpty() || totalGenreW == 0) return GenreStats(0.0, 0.0, false)
            var posW = 0.0
            var clashW = 0.0
            for ((pg, pw) in genreProfile) {
                val pList = listOf(pg)
                posW += pw * GenreTaxonomy.genreSim(gs, pList)
                clashW += pw * GenreTaxonomy.genreClash(gs, pList)
            }
            val pos = min(1.0, posW / totalGenreW.toDouble())
            val neg = min(1.0, clashW / totalGenreW.toDouble())
            val net = pos - neg * 0.9
            return GenreStats(pos, net, neg >= 0.4 && net < 0.1)
        }

        fun jitter(id: String): Double {
            val h = ((hashId(id) * 2654435761L) xor ((seed + 1L) * 1013904223L)) and 0xFFFFFFFFL
            return h.toDouble() / 4294967295.0
        }

        data class RankedPick(val track: Track, val tier: Int, val score: Double)

        fun rankTail(list: List<Track>): List<Track> = list.map { t ->
            // Priority 1: File tags (artist, album, title/album tokens)
            var artW = 0
            for (a in CanonicalKey.splitArtists(t.artist)) {
                if (a.isNotBlank() && !a.equals("Unknown Artist", ignoreCase = true)) {
                    artW = max(artW, artistWeight[CanonicalKey.norm(a)] ?: 0)
                }
            }
            val artAff = if (totalArtistW > 0 && artW > 0) min(1.0, (artW * 4.0) / totalArtistW.toDouble()) else 0.0
            val albW = if (t.album.isNotBlank()) albumWeight[CanonicalKey.norm(t.album)] ?: 0 else 0
            val albAff = if (totalAlbumW > 0 && albW > 0) min(1.0, (albW * 5.0) / totalAlbumW.toDouble()) else 0.0
            var tokW = 0
            for (tok in CanonicalKey.metaTokens(t.title, t.album)) tokW += tokenWeight[tok] ?: 0
            val tokAff = if (totalTokenW > 0 && tokW > 0) min(1.0, (tokW * 6.0) / totalTokenW.toDouble()) else 0.0
            val hasTagMatch = artW > 0 || albW > 0 || tokW > 0
            val tagScore = artAff * 120.0 + albAff * 90.0 + tokAff * 45.0

            // Priority 2: Genre affinity (with style clash penalty)
            val gs = genreStats(GenreTaxonomy.effectiveGenres(t, allTracks))
            val genreScore = gs.net * 100.0

            // Priority 3: Other signals
            val plBonus = min(inPlaylistCount[t.id] ?: 0, 3) * 8.0
            val tier = when {
                hasTagMatch && !gs.clashes -> 0
                !gs.clashes && gs.net >= 0.35 -> 1
                !gs.clashes && gs.pos > 0.0 -> 2
                !gs.clashes -> 3
                hasTagMatch -> 2
                else -> 4
            }
            RankedPick(t, tier, tagScore + genreScore + plBonus + jitter(t.id) * 22.0)
        }.sortedWith(
            compareBy<RankedPick> { it.tier }.thenByDescending { it.score }
        ).map { it.track }

        fun spread(list: List<Track>): List<Track> {
            val out = mutableListOf<Track>()
            val deferred = mutableListOf<Track>()
            val counts = HashMap<String, Int>()
            for (t in list) {
                val primary = CanonicalKey.norm(CanonicalKey.splitArtists(t.artist).firstOrNull() ?: "")
                val count = counts.getOrDefault(primary, 0)
                if (count < 2) {
                    out.add(t)
                    counts[primary] = count + 1
                } else {
                    deferred.add(t)
                }
            }
            out.addAll(deferred)
            return out
        }

        val combined = ArrayList<Track>(allTracks.size)
        combined.addAll(spread(played))
        combined.addAll(spread(rankTail(liked)))
        combined.addAll(spread(rankTail(others)))
        return combined
    }

    /**
     * Builds radio mixes ("all", "familiar", "rediscover", "artist") strictly prioritizing:
     * file tags (artist, album, title/album tokens) -> genre similarity -> co-occurrence & plays/likes.
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
        val seedTokens = CanonicalKey.metaTokens(seedTrack.title, seedTrack.album).toSet()
        val seedGenres = GenreTaxonomy.effectiveGenres(seedTrack, allTracks)

        val cooccur = HashMap<String, Int>()
        for (tids in playlistTrackMap.values) {
            if (tids.contains(seedTrack.id)) {
                for (id in tids) {
                    if (id != seedTrack.id) {
                        cooccur[id] = cooccur.getOrDefault(id, 0) + 1
                    }
                }
            }
        }

        val now = System.currentTimeMillis() / 1000L
        val pool = allTracks.filter { it.id != seedTrack.id }

        fun tierOf(sharedArt: Int, sameAlbum: Boolean, sharedTok: Int, rel: GenreRelation): Int {
            if (mode == "artist") {
                if (sharedArt > 0) return 0
                if (sameAlbum || sharedTok > 0) return 1
                return when (rel) {
                    GenreRelation.SAME -> 2
                    GenreRelation.RELATED -> 3
                    GenreRelation.UNKNOWN -> 4
                    GenreRelation.CLASH -> 5
                }
            }
            if (sharedArt > 0 || sameAlbum) return 0
            if (sharedTok > 0 && rel != GenreRelation.CLASH) return 1
            return when {
                rel == GenreRelation.SAME -> 2
                rel == GenreRelation.RELATED || (sharedTok > 0 && rel == GenreRelation.CLASH) -> 3
                rel == GenreRelation.UNKNOWN -> 4
                else -> 5
            }
        }

        data class ScoredRadio(val track: Track, val tier: Int, val pref: Int, val score: Double)

        val scored = pool.map { c ->
            // Priority 1: File tags (artist, album, title/album tokens)
            val cArtists = CanonicalKey.splitArtists(c.artist).map { CanonicalKey.norm(it) }
            val sharedArt = cArtists.count { seedArtists.contains(it) }
            val sameAlbum = seedAlbum.isNotEmpty() && CanonicalKey.norm(c.album) == seedAlbum
            var sharedTok = 0
            for (tok in CanonicalKey.metaTokens(c.title, c.album)) {
                if (seedTokens.contains(tok)) sharedTok++
            }
            val tagScore = (if (sharedArt > 0) (if (mode == "artist") 240.0 else 120.0) * sharedArt else 0.0) +
                (if (sameAlbum) 95.0 else 0.0) +
                min(sharedTok, 3) * 32.0

            // Priority 2: Genre similarity & style clash penalty
            val cGenres = GenreTaxonomy.effectiveGenres(c, allTracks)
            val sim = if (seedGenres.isNotEmpty() && cGenres.isNotEmpty()) GenreTaxonomy.genreSim(seedGenres, cGenres) else 0.0
            val clash = if (seedGenres.isNotEmpty() && cGenres.isNotEmpty()) GenreTaxonomy.genreClash(seedGenres, cGenres) else 0.0
            val rel = GenreTaxonomy.genreRelation(seedGenres, cGenres)
            val genreScore = sim * 110.0 - clash * 80.0

            // Priority 3: Other signals
            val co = cooccur.getOrDefault(c.id, 0)
            var otherScore = if (co > 0) 28.0 * min(co, 3) else 0.0
            when (mode) {
                "familiar" -> otherScore += min(c.playCount * 10.0, 60.0) + (if (c.liked) 35.0 else 0.0)
                "rediscover" -> {
                    if (c.playCount == 0) otherScore += 45.0
                    if (c.liked) otherScore += 20.0
                }
                else -> {
                    if (c.liked) otherScore += 18.0
                    otherScore += min(c.playCount * 4.0, 24.0)
                }
            }
            val h = (((hashId(c.id) * 2654435761L) xor (hashId(seedTrack.id) * 1013904223L)) and 0xFFFFFFFFL).toDouble() / 4294967295.0
            otherScore += h * 24.0

            var pref = 0
            if (mode == "familiar" && !(c.playCount > 0 || c.liked)) pref = 1
            if (mode == "rediscover" && !(c.playCount <= 1 || now - c.lastPlayed > 7 * 86400L)) pref = 1

            ScoredRadio(c, tierOf(sharedArt, sameAlbum, sharedTok, rel), pref, tagScore + genreScore + otherScore)
        }.sortedWith(
            compareBy<ScoredRadio> { it.tier }
                .thenBy { it.pref }
                .thenByDescending { it.score }
        )

        val result = ArrayList<Track>(min(limit, pool.size + 1))
        result.add(seedTrack)
        result.addAll(scored.take(max(0, limit - 1)).map { it.track })
        return result
    }

    /**
     * Recommends tracks for a playlist in strict priority order:
     * file tags (artist, album, title/album tokens) -> genre similarity -> co-occurrence & plays/likes.
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

        val artistFreq = HashMap<String, Int>()
        val albumFreq = HashMap<String, Int>()
        val tokenFreq = HashMap<String, Int>()
        val cooccurFreq = HashMap<String, Int>()
        val genreFreq = HashMap<String, Int>()
        var genreTrackTotal = 0

        for (t in playlistTracks) {
            for (a in CanonicalKey.splitArtists(t.artist)) {
                if (a.isNotEmpty() && !a.equals("Unknown Artist", ignoreCase = true)) {
                    val k = CanonicalKey.norm(a)
                    artistFreq[k] = artistFreq.getOrDefault(k, 0) + 1
                }
            }
            if (t.album.isNotEmpty()) {
                val ak = CanonicalKey.norm(t.album)
                albumFreq[ak] = albumFreq.getOrDefault(ak, 0) + 1
            }
            for (tok in CanonicalKey.metaTokens(t.title, t.album)) {
                tokenFreq[tok] = tokenFreq.getOrDefault(tok, 0) + 1
            }
            val eff = GenreTaxonomy.effectiveGenres(t, allTracks)
            if (eff.isNotEmpty()) {
                genreTrackTotal++
                for (g in eff) genreFreq[g] = genreFreq.getOrDefault(g, 0) + 1
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

        val sortedG = genreFreq.entries.sortedByDescending { it.value }
        val topGenres = sortedG.filterIndexed { i, e ->
            i == 0 || e.value >= max(1.0, genreTrackTotal * 0.2)
        }.map { it.key }

        fun weightedGenreAffinity(gs: List<String>): Double {
            if (gs.isEmpty() || genreTrackTotal == 0) return 0.0
            var w = 0.0
            for (e in sortedG) w += e.value * GenreTaxonomy.genreSim(gs, listOf(e.key))
            return min(1.0, w / genreTrackTotal.toDouble())
        }

        data class ScoredRec(val track: Track, val tier: Int, val score: Double)

        val scored = candidates.map { c ->
            // Priority 1: File tags (artist, album, title/album tokens)
            var artistScore = 0.0
            for (a in CanonicalKey.splitArtists(c.artist)) {
                val f = artistFreq.getOrDefault(CanonicalKey.norm(a), 0)
                if (f > 0) artistScore += 120.0 * min(f, 3)
            }
            artistScore = min(artistScore, 360.0)

            var albumScore = 0.0
            if (c.album.isNotEmpty()) {
                val f = albumFreq.getOrDefault(CanonicalKey.norm(c.album), 0)
                if (f > 0) albumScore = min(285.0, 95.0 * min(f, 3))
            }

            var tokenScore = 0.0
            for (tok in CanonicalKey.metaTokens(c.title, c.album)) {
                val f = tokenFreq.getOrDefault(tok, 0)
                if (f > 0) tokenScore += 32.0 * min(f, 2)
            }
            tokenScore = min(tokenScore, 128.0)
            val tagScore = artistScore + albumScore + tokenScore

            // Priority 2: Genre similarity & style clash penalty
            val cGenres = GenreTaxonomy.effectiveGenres(c, allTracks)
            val simTop = if (topGenres.isNotEmpty() && cGenres.isNotEmpty()) GenreTaxonomy.genreSim(cGenres, topGenres) else 0.0
            val clashTop = if (topGenres.isNotEmpty() && cGenres.isNotEmpty()) GenreTaxonomy.genreClash(cGenres, topGenres) else 0.0
            val simAvg = weightedGenreAffinity(cGenres)
            val sim = max(simTop, simAvg)
            val rel = when {
                topGenres.isEmpty() || cGenres.isEmpty() -> GenreRelation.UNKNOWN
                clashTop >= 0.45 && sim < 0.35 -> GenreRelation.CLASH
                sim >= 0.5 -> GenreRelation.SAME
                sim >= 0.2 -> GenreRelation.RELATED
                else -> GenreRelation.CLASH
            }
            val genreScore = simTop * 90.0 + simAvg * 60.0 - clashTop * 80.0

            // Priority 3: Other signals
            val co = cooccurFreq.getOrDefault(c.id, 0)
            val coScore = if (co > 0) 28.0 * min(co, 4) else 0.0
            val popScore = (if (c.liked) 12.0 else 0.0) + min(c.playCount * 3.0, 18.0)
            val hash = (((hashId(c.id) * 2654435761L) xor ((seed + 1L) * 1013904223L)) and 0xFFFFFFFFL).toDouble() / 4294967295.0
            val jitter = if (seed == 0) (if (playlistTracks.isNotEmpty()) 20.0 else 45.0) else 95.0

            val tier = if (playlistTracks.isEmpty()) {
                0
            } else when {
                artistScore > 0.0 || albumScore > 0.0 -> 0
                tokenScore > 0.0 && rel != GenreRelation.CLASH -> 1
                rel == GenreRelation.SAME -> 2
                rel == GenreRelation.RELATED || (tokenScore > 0.0 && rel == GenreRelation.CLASH) -> 3
                rel == GenreRelation.UNKNOWN -> 4
                else -> 5
            }

            ScoredRec(c, tier, tagScore + genreScore + coScore + popScore + hash * jitter)
        }.sortedWith(
            compareBy<ScoredRec> { it.tier }.thenByDescending { it.score }
        )

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
