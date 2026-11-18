package com.vibemusic.nativeapp.ytdlp

import android.util.Log
import com.vibemusic.nativeapp.data.CanonicalKey
import com.vibemusic.nativeapp.data.YtCollectionType
import com.vibemusic.nativeapp.data.YtMusicCollection
import com.vibemusic.nativeapp.data.YtMusicTrackItem
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class YtMusicClient(
    private val authManager: YtMusicAuthManager
) {
    private val countRegex = Regex("""(\d+)\s+(?:songs?|tracks?)""", RegexOption.IGNORE_CASE)
    private val durationRegex = Regex("""^\d{1,2}(?::\d{2}){1,2}$""")
    private val playsRegex = Regex("""^\d+(?:\.\d+)?[KMB]?\s+(?:plays?|views?)$""", RegexOption.IGNORE_CASE)
    private val yearRegex = Regex("""^(?:19|20)\d{2}$""")
    private val mediaTypePrefixes = setOf("song", "video", "album", "single", "ep", "podcast", "episode")

    suspend fun fetchUserLibrary(): List<YtMusicCollection> = withContext(Dispatchers.IO) {
        val result = mutableListOf<YtMusicCollection>()
        val seenIds = mutableSetOf<String>()

        val likedMusic = YtMusicCollection(
            id = "LM",
            browseId = "VLLM",
            playlistUrl = "https://music.youtube.com/playlist?list=LM",
            title = "Liked Music",
            subtitle = "Your YouTube Music likes • Syncs to Liked Songs",
            type = YtCollectionType.LIKED_MUSIC
        )
        result.add(likedMusic)
        seenIds.add("LM")

        // Fetch Playlists
        val playlists = fetchCollectionsBrowse(
            browseId = "FEmusic_liked_playlists",
            type = YtCollectionType.PLAYLIST,
            maxPages = 8
        )
        for (col in playlists) {
            if (seenIds.add(col.id)) {
                result.add(col)
            }
        }

        // Fallback via yt-dlp if InnerTube returned no playlists while logged in
        if (playlists.isEmpty() && authManager.cookiesFile.exists()) {
            for (col in fetchPlaylistsViaYtdlp()) {
                if (seenIds.add(col.id)) {
                    result.add(col)
                }
            }
        }

        // Fetch Saved Albums
        val albums = fetchCollectionsBrowse(
            browseId = "FEmusic_liked_albums",
            type = YtCollectionType.ALBUM,
            maxPages = 8
        )
        for (col in albums) {
            if (seenIds.add(col.id)) {
                result.add(col)
            }
        }

        result
    }

    suspend fun fetchCollectionTracks(collection: YtMusicCollection): List<YtMusicTrackItem> = withContext(Dispatchers.IO) {
        val primaryTracks = fetchTracksViaInnerTube(collection)
        if (primaryTracks.isNotEmpty()) {
            return@withContext primaryTracks
        }

        // Alternate browseId for Liked Music if VLLM returned nothing
        if (collection.type == YtCollectionType.LIKED_MUSIC && collection.browseId != "FEmusic_liked_videos") {
            val altTracks = fetchTracksViaInnerTube(collection.copy(browseId = "FEmusic_liked_videos"))
            if (altTracks.isNotEmpty()) {
                return@withContext altTracks
            }
        }

        // Fallback to yt-dlp --flat-playlist -J
        fetchTracksViaYtdlp(collection)
    }

    private fun fetchCollectionsBrowse(
        browseId: String,
        type: YtCollectionType,
        maxPages: Int
    ): List<YtMusicCollection> {
        val out = mutableListOf<YtMusicCollection>()
        val seenBrowseIds = mutableSetOf<String>()
        var continuation: String? = null
        var page = 0

        while (page < maxPages) {
            val root = callInnerTubeBrowse(
                browseId = if (page == 0) browseId else null,
                continuation = continuation
            ) ?: break

            val twoRowList = mutableListOf<JSONObject>()
            val listRowList = mutableListOf<JSONObject>()
            collectRenderers(root, twoRowList, listRowList)

            for (renderer in twoRowList) {
                parseTwoRowCollection(renderer, type)?.let { col ->
                    if (seenBrowseIds.add(col.browseId) && seenBrowseIds.add(col.id)) {
                        out.add(col)
                    }
                }
            }
            for (renderer in listRowList) {
                parseResponsiveCollection(renderer, type)?.let { col ->
                    if (seenBrowseIds.add(col.browseId) && seenBrowseIds.add(col.id)) {
                        out.add(col)
                    }
                }
            }

            continuation = extractContinuationToken(root)
            if (continuation.isNullOrBlank()) break
            page++
        }
        return out
    }

    private fun fetchTracksViaInnerTube(collection: YtMusicCollection): List<YtMusicTrackItem> {
        val out = mutableListOf<YtMusicTrackItem>()
        val seenVideoIds = mutableSetOf<String>()
        var continuation: String? = null
        var page = 0
        val maxPages = 25

        while (page < maxPages) {
            val root = callInnerTubeBrowse(
                browseId = if (page == 0) collection.browseId else null,
                continuation = continuation
            ) ?: break

            val twoRowList = mutableListOf<JSONObject>()
            val listRowList = mutableListOf<JSONObject>()

            if (collection.type == YtCollectionType.PLAYLIST || collection.type == YtCollectionType.LIKED_MUSIC) {
                val playlistShelves = mutableListOf<JSONObject>()
                findPlaylistShelfNodes(root, playlistShelves)
                if (playlistShelves.isNotEmpty()) {
                    for (shelf in playlistShelves) {
                        collectRenderers(shelf, twoRowList, listRowList, skipCarousels = true)
                    }
                    continuation = playlistShelves.firstNotNullOfOrNull { extractContinuationToken(it) }
                } else {
                    collectRenderers(root, twoRowList, listRowList, skipCarousels = true)
                    continuation = extractContinuationToken(root)
                }
            } else {
                collectRenderers(root, twoRowList, listRowList, skipCarousels = true)
                continuation = extractContinuationToken(root)
            }

            for (renderer in listRowList) {
                parseTrackFromListRenderer(renderer, collection)?.let { item ->
                    if (seenVideoIds.add(item.ytId)) {
                        out.add(item)
                    }
                }
            }

            if (continuation.isNullOrBlank()) break
            page++
        }
        return out
    }

    private fun parseTwoRowCollection(renderer: JSONObject, type: YtCollectionType): YtMusicCollection? {
        val navBrowseId = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId", "")
            ?.trim()
            .orEmpty()

        val playEndpoint = renderer.optJSONObject("thumbnailOverlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")

        val watchPlaylistId = playEndpoint?.optJSONObject("watchPlaylistEndpoint")
            ?.optString("playlistId", "")?.trim().orEmpty()
            .ifBlank {
                playEndpoint?.optJSONObject("watchEndpoint")
                    ?.optString("playlistId", "")?.trim().orEmpty()
            }

        val effectiveBrowseId = navBrowseId.ifBlank {
            if (watchPlaylistId.isNotBlank()) "VL$watchPlaylistId" else ""
        }
        if (effectiveBrowseId.isBlank() || isExcludedBrowseId(effectiveBrowseId)) {
            return null
        }

        val title = extractRunsText(renderer.optJSONObject("title")).trim()
        if (title.isBlank() || title.equals("New playlist", ignoreCase = true)) {
            return null
        }

        val subtitle = extractRunsText(renderer.optJSONObject("subtitle")).trim()
        val countHint = countRegex.find(subtitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val thumbUrl = extractBestThumbnail(
            renderer.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
        )

        return buildCollection(
            browseId = effectiveBrowseId,
            watchPlaylistId = watchPlaylistId,
            title = title,
            subtitle = subtitle,
            countHint = countHint,
            thumbUrl = thumbUrl,
            type = type
        )
    }

    private fun parseResponsiveCollection(renderer: JSONObject, type: YtCollectionType): YtMusicCollection? {
        val navBrowseId = renderer.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId", "")
            ?.trim()
            .orEmpty()

        val playEndpoint = renderer.optJSONObject("overlay")
            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("musicPlayButtonRenderer")
            ?.optJSONObject("playNavigationEndpoint")

        val watchPlaylistId = playEndpoint?.optJSONObject("watchPlaylistEndpoint")
            ?.optString("playlistId", "")?.trim().orEmpty()
            .ifBlank {
                playEndpoint?.optJSONObject("watchEndpoint")
                    ?.optString("playlistId", "")?.trim().orEmpty()
            }

        val flexCols = renderer.optJSONArray("flexColumns") ?: return null
        val col0Text = flexCols.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        val col1Text = flexCols.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")

        val col0BrowseId = col0Text?.optJSONArray("runs")?.optJSONObject(0)
            ?.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")
            ?.optString("browseId", "")?.trim().orEmpty()

        val effectiveBrowseId = navBrowseId.ifBlank { col0BrowseId }.ifBlank {
            if (watchPlaylistId.isNotBlank()) "VL$watchPlaylistId" else ""
        }
        if (effectiveBrowseId.isBlank() || isExcludedBrowseId(effectiveBrowseId)) {
            return null
        }

        val title = extractRunsText(col0Text).trim()
        if (title.isBlank() || title.equals("New playlist", ignoreCase = true)) {
            return null
        }

        val subtitle = extractRunsText(col1Text).trim()
        val countHint = countRegex.find(subtitle)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val thumbUrl = extractBestThumbnail(
            renderer.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
        )

        return buildCollection(
            browseId = effectiveBrowseId,
            watchPlaylistId = watchPlaylistId,
            title = title,
            subtitle = subtitle,
            countHint = countHint,
            thumbUrl = thumbUrl,
            type = type
        )
    }

    private fun isExcludedBrowseId(browseId: String): Boolean {
        return browseId == "VLLM" ||
            browseId == "LM" ||
            browseId == "FEmusic_liked_videos" ||
            browseId == "VLSE" ||
            browseId == "SE" ||
            browseId.startsWith("FEmusic_library_") && !browseId.contains("release_detail")
    }

    private fun buildCollection(
        browseId: String,
        watchPlaylistId: String,
        title: String,
        subtitle: String,
        countHint: Int,
        thumbUrl: String,
        type: YtCollectionType
    ): YtMusicCollection {
        val cleanId = when {
            watchPlaylistId.isNotBlank() -> watchPlaylistId
            browseId.startsWith("VL") -> browseId.removePrefix("VL")
            else -> browseId
        }
        val playlistUrl = when {
            watchPlaylistId.isNotBlank() -> "https://music.youtube.com/playlist?list=$watchPlaylistId"
            browseId.startsWith("VL") -> "https://music.youtube.com/playlist?list=${browseId.removePrefix("VL")}"
            else -> "https://music.youtube.com/browse/$browseId"
        }
        val defaultSubtitle = when (type) {
            YtCollectionType.ALBUM -> "Saved Album"
            YtCollectionType.PLAYLIST -> "Playlist"
            YtCollectionType.LIKED_MUSIC -> "Liked Songs"
        }
        return YtMusicCollection(
            id = cleanId,
            browseId = browseId,
            playlistUrl = playlistUrl,
            title = title,
            subtitle = subtitle.ifBlank { defaultSubtitle },
            trackCountHint = countHint,
            thumbnailUrl = thumbUrl,
            type = type
        )
    }

    private fun parseTrackFromListRenderer(
        renderer: JSONObject,
        collection: YtMusicCollection
    ): YtMusicTrackItem? {
        var videoId = renderer.optJSONObject("playlistItemData")
            ?.optString("videoId", "")?.trim().orEmpty()

        if (!CanonicalKey.isValidYtId(videoId)) {
            videoId = renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId", "")?.trim().orEmpty()
        }

        val flexCols = renderer.optJSONArray("flexColumns")
        if (!CanonicalKey.isValidYtId(videoId) && flexCols != null) {
            for (i in 0 until flexCols.length()) {
                val runs = flexCols.optJSONObject(i)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs") ?: continue
                for (r in 0 until runs.length()) {
                    val vid = runs.optJSONObject(r)
                        ?.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId", "")?.trim().orEmpty()
                    if (CanonicalKey.isValidYtId(vid)) {
                        videoId = vid
                        break
                    }
                }
                if (CanonicalKey.isValidYtId(videoId)) break
            }
        }

        if (!CanonicalKey.isValidYtId(videoId)) return null

        val col0Text = flexCols?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        val title = extractRunsText(col0Text).trim()
            .ifBlank { extractRunsText(renderer.optJSONObject("title")).trim() }
        if (title.isBlank() || title == "[Deleted video]" || title == "[Private video]") {
            return null
        }

        val artists = mutableListOf<String>()
        var album = if (collection.type == YtCollectionType.ALBUM) collection.title else ""
        var durationSec = 0.0

        val col1Text = flexCols?.optJSONObject(1)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
        val col1Runs = col1Text?.optJSONArray("runs")
        if (col1Runs != null) {
            for (i in 0 until col1Runs.length()) {
                val run = col1Runs.optJSONObject(i) ?: continue
                val text = run.optString("text", "").trim()
                if (text.isEmpty() || text == "•" || text == "," || text == "&") continue
                val pageType = run.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType", "")
                    .orEmpty()
                when (pageType) {
                    "MUSIC_PAGE_TYPE_ARTIST", "MUSIC_PAGE_TYPE_USER_CHANNEL" -> artists.add(text)
                    "MUSIC_PAGE_TYPE_ALBUM" -> if (album.isBlank()) album = text
                }
            }
        }

        if (artists.isEmpty() && col1Text != null) {
            val rawParts = extractRunsText(col1Text).split("•").map { it.trim() }.filter { it.isNotEmpty() }
            for (part in rawParts) {
                if (mediaTypePrefixes.contains(part.lowercase())) continue
                if (durationRegex.matches(part)) {
                    durationSec = parseDurationSeconds(part)
                    continue
                }
                if (playsRegex.matches(part) || yearRegex.matches(part)) continue
                artists.add(part)
                break
            }
        }

        // Also check col2 for album name when flexColumns has >= 3 columns (standard playlist rows)
        if (album.isBlank() && flexCols != null && flexCols.length() >= 3) {
            val col2Text = flexCols.optJSONObject(2)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
            val candidateAlbum = extractRunsText(col2Text).trim()
            if (candidateAlbum.isNotBlank() && !playsRegex.matches(candidateAlbum) && !durationRegex.matches(candidateAlbum)) {
                album = candidateAlbum
            }
        }

        if (durationSec <= 0.0) {
            val fixedCols = renderer.optJSONArray("fixedColumns")
            if (fixedCols != null) {
                for (i in 0 until fixedCols.length()) {
                    val textObj = fixedCols.optJSONObject(i)
                        ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                        ?.optJSONObject("text")
                    val durStr = extractRunsText(textObj).trim()
                    if (durationRegex.matches(durStr)) {
                        durationSec = parseDurationSeconds(durStr)
                        break
                    }
                }
            }
        }

        val cleanedArtist = CanonicalKey.cleanArtistString(artists.joinToString(", "))
            .ifBlank { if (collection.type == YtCollectionType.ALBUM) extractAlbumArtistFromSubtitle(collection.subtitle) else "" }

        return YtMusicTrackItem(
            ytId = videoId,
            title = title,
            artist = cleanedArtist,
            album = album,
            durationSec = durationSec
        )
    }

    private fun extractAlbumArtistFromSubtitle(subtitle: String): String {
        val parts = subtitle.split("•").map { it.trim() }.filter { it.isNotEmpty() }
        for (p in parts) {
            if (!mediaTypePrefixes.contains(p.lowercase()) && !yearRegex.matches(p) && !countRegex.containsMatchIn(p)) {
                return CanonicalKey.cleanArtistString(p)
            }
        }
        return ""
    }

    private fun parseDurationSeconds(text: String): Double {
        val parts = text.split(":").mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]).toDouble()
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toDouble()
            else -> 0.0
        }
    }

    private fun callInnerTubeBrowse(browseId: String?, continuation: String?): JSONObject? {
        val cookieHeader = authManager.getCookieHeader()
        val authHeader = authManager.buildSapisidHashHeader("https://music.youtube.com")

        val body = JSONObject().apply {
            put(
                "context",
                JSONObject().put(
                    "client",
                    JSONObject().apply {
                        put("clientName", "WEB_REMIX")
                        put("clientVersion", "1.20241118.01.00")
                        put("hl", "en")
                        put("gl", "US")
                    }
                )
            )
            if (!browseId.isNullOrBlank()) {
                put("browseId", browseId)
            }
            if (!continuation.isNullOrBlank()) {
                put("continuation", continuation)
            }
        }

        return try {
            val conn = (URL("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Origin", "https://music.youtube.com")
                setRequestProperty("Referer", "https://music.youtube.com/")
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
                setRequestProperty("X-Origin", "https://music.youtube.com")
                setRequestProperty("X-Goog-AuthUser", "0")
                setRequestProperty("X-Youtube-Client-Name", "67")
                setRequestProperty("X-Youtube-Client-Version", "1.20241118.01.00")
                if (cookieHeader.isNotBlank()) {
                    setRequestProperty("Cookie", cookieHeader)
                }
                if (!authHeader.isNullOrBlank()) {
                    setRequestProperty("Authorization", authHeader)
                }
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body.toString())
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299 || text.isBlank()) {
                Log.w("YtMusicClient", "InnerTube browse HTTP $code for browseId=$browseId")
                null
            } else {
                JSONObject(text)
            }
        } catch (e: Exception) {
            Log.w("YtMusicClient", "InnerTube browse failed for $browseId: ${e.message}")
            null
        }
    }

    private fun findPlaylistShelfNodes(node: Any?, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                var matched = false
                for (key in listOf("musicPlaylistShelfRenderer", "musicPlaylistShelfContinuation", "appendContinuationItemsAction")) {
                    val obj = node.optJSONObject(key)
                    if (obj != null) {
                        out.add(obj)
                        matched = true
                    }
                }
                if (!matched) {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        findPlaylistShelfNodes(node.opt(keys.next()), out)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findPlaylistShelfNodes(node.opt(i), out)
                }
            }
        }
    }

    private fun collectRenderers(
        node: Any?,
        twoRowOut: MutableList<JSONObject>,
        listRowOut: MutableList<JSONObject>,
        skipCarousels: Boolean = false
    ) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicTwoRowItemRenderer")?.let { twoRowOut.add(it) }
                node.optJSONObject("musicResponsiveListItemRenderer")?.let { listRowOut.add(it) }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (skipCarousels && k == "musicCarouselShelfRenderer") {
                        continue
                    }
                    if (k != "musicTwoRowItemRenderer" && k != "musicResponsiveListItemRenderer") {
                        collectRenderers(node.opt(k), twoRowOut, listRowOut, skipCarousels)
                    } else {
                        collectRenderers(node.optJSONObject(k), twoRowOut, listRowOut, skipCarousels)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectRenderers(node.opt(i), twoRowOut, listRowOut, skipCarousels)
                }
            }
        }
    }

    private fun extractContinuationToken(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("nextContinuationData")
                    ?.optString("continuation", "")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }

                node.optJSONObject("continuationCommand")
                    ?.optString("token", "")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { return it }

                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = extractContinuationToken(node.opt(keys.next()))
                    if (!found.isNullOrBlank()) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = extractContinuationToken(node.opt(i))
                    if (!found.isNullOrBlank()) return found
                }
            }
        }
        return null
    }

    private fun extractRunsText(textObj: JSONObject?): String {
        if (textObj == null) return ""
        val runs = textObj.optJSONArray("runs")
        if (runs != null) {
            val sb = StringBuilder()
            for (i in 0 until runs.length()) {
                sb.append(runs.optJSONObject(i)?.optString("text", "").orEmpty())
            }
            return sb.toString()
        }
        return textObj.optString("simpleText", "")
    }

    private fun extractBestThumbnail(thumbnailObj: JSONObject?): String {
        val arr = thumbnailObj?.optJSONArray("thumbnails") ?: return ""
        if (arr.length() == 0) return ""
        return arr.optJSONObject(arr.length() - 1)?.optString("url", "").orEmpty()
    }

    private fun fetchPlaylistsViaYtdlp(): List<YtMusicCollection> {
        val out = mutableListOf<YtMusicCollection>()
        try {
            val req = YoutubeDLRequest("https://music.youtube.com/library/playlists")
            if (authManager.cookiesFile.exists()) {
                req.addOption("--cookies", authManager.cookiesFile.absolutePath)
            }
            req.addOption("--flat-playlist")
            req.addOption("-J")
            req.addOption("--no-warnings")

            val resp = YoutubeDL.getInstance().execute(req)
            val jsonText = resp.out
            if (jsonText.isNullOrBlank()) return emptyList()
            val root = JSONObject(jsonText)
            val entries = root.optJSONArray("entries") ?: return emptyList()
            for (i in 0 until entries.length()) {
                val entry = entries.optJSONObject(i) ?: continue
                val id = entry.optString("id", "").trim()
                val title = entry.optString("title", "").trim()
                if (id.isBlank() || id == "LM" || id == "SE" || title.isBlank()) continue
                val url = entry.optString("url", "").ifBlank { "https://music.youtube.com/playlist?list=$id" }
                out.add(
                    YtMusicCollection(
                        id = id,
                        browseId = if (id.startsWith("VL")) id else "VL$id",
                        playlistUrl = url,
                        title = title,
                        subtitle = "Playlist",
                        thumbnailUrl = entry.optString("thumbnail", ""),
                        type = YtCollectionType.PLAYLIST
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("YtMusicClient", "yt-dlp playlist discovery fallback failed: ${e.message}")
        }
        return out
    }

    private fun fetchTracksViaYtdlp(collection: YtMusicCollection): List<YtMusicTrackItem> {
        val candidateUrls = buildList {
            if (collection.playlistUrl.isNotBlank()) add(collection.playlistUrl)
            if (collection.id.startsWith("PL") || collection.id.startsWith("OLAK") || collection.id == "LM") {
                add("https://www.youtube.com/playlist?list=${collection.id}")
            }
            if (collection.browseId.startsWith("MPREb_")) {
                add("https://music.youtube.com/browse/${collection.browseId}")
            }
        }.distinct()

        for (targetUrl in candidateUrls) {
            try {
                val req = YoutubeDLRequest(targetUrl)
                if (authManager.cookiesFile.exists()) {
                    req.addOption("--cookies", authManager.cookiesFile.absolutePath)
                }
                req.addOption("--flat-playlist")
                req.addOption("-J")
                req.addOption("--no-warnings")

                val resp = YoutubeDL.getInstance().execute(req)
                val jsonText = resp.out
                if (jsonText.isNullOrBlank()) continue
                val root = JSONObject(jsonText)
                val entries = root.optJSONArray("entries") ?: continue
                val out = mutableListOf<YtMusicTrackItem>()
                val seen = mutableSetOf<String>()
                for (i in 0 until entries.length()) {
                    val item = entries.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").trim()
                    if (!CanonicalKey.isValidYtId(id) || !seen.add(id)) continue
                    val title = item.optString("title", "").trim()
                    if (title.isBlank() || title == "[Deleted video]" || title == "[Private video]") continue
                    val rawArtist = item.optString("artist", "")
                        .ifBlank { item.optString("uploader", "") }
                        .ifBlank { item.optString("channel", "") }
                    val artist = CanonicalKey.cleanArtistString(rawArtist)
                    val album = item.optString("album", "").ifBlank {
                        if (collection.type == YtCollectionType.ALBUM) collection.title else ""
                    }
                    val dur = item.optDouble("duration", 0.0).let { if (it.isNaN() || it < 0.0) 0.0 else it }
                    out.add(
                        YtMusicTrackItem(
                            ytId = id,
                            title = title,
                            artist = artist,
                            album = album,
                            durationSec = dur
                        )
                    )
                }
                if (out.isNotEmpty()) return out
            } catch (e: Exception) {
                Log.w("YtMusicClient", "yt-dlp fallback track fetch failed for $targetUrl: ${e.message}")
            }
        }
        return emptyList()
    }
}
