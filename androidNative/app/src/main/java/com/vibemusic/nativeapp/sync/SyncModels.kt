package com.vibemusic.nativeapp.sync

import org.json.JSONArray
import org.json.JSONObject

object SyncConstants {
    const val PROTOCOL_VERSION = 1
    const val DISCOVERY_MAGIC = "VIBEMUSIC_SYNC_V1"
    const val DEFAULT_PORT = 53318
    const val MULTICAST_ADDR = "224.0.0.167"
    const val CHUNK_SIZE = 64 * 1024
}

data class SyncPeer(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val ip: String,
    val port: Int,
    val trackCount: Int,
    val playlistCount: Int,
    val pubKeySpkiB64: String,
    val trusted: Boolean,
    val lastSeen: Long
)

data class TrustedSyncPeer(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val pubKeySpkiB64: String,
    val pairedAt: Long = 0L,
    val lastSyncAt: Long = 0L
)

data class SyncPairPrompt(
    val pairId: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val trackCount: Int,
    val pin: String,
    val trustedDefault: Boolean = true,
    val incoming: Boolean = true
)

data class SyncStatus(
    val state: String = "idle", // idle | discovering | pairing | syncing | done | error
    val phase: String = "",
    val peerId: String = "",
    val peerName: String = "",
    val direction: String = "",
    val pin: String = "",
    val percent: Int = 0,
    val currentFile: String = "",
    val transferredBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val filesDone: Int = 0,
    val filesTotal: Int = 0,
    val speedBps: Long = 0L,
    val addedTracks: Int = 0,
    val updatedTracks: Int = 0,
    val deletedTracks: Int = 0,
    val syncedPlaylists: Int = 0,
    val error: String = "",
    val message: String = ""
)

data class SyncSelfInfo(
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    val port: Int,
    val localIps: List<String>,
    val trackCount: Int,
    val playlistCount: Int
)

data class SyncTrack(
    val id: String,
    val ytId: String = "",
    val relPath: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val duration: Double = 0.0,
    val year: Int = 0,
    val addedAt: Long = 0L,
    val liked: Boolean = false,
    val playCount: Int = 0,
    val lastPlayed: Long = 0L,
    val hasCover: Boolean = false,
    val hasCoverFile: Boolean = false,
    val hasLyricsFile: Boolean = false,
    val format: String = "",
    val bitrate: Int = 0,
    val fileSize: Long = 0L,
    val mtimeUnix: Long = 0L,
    val updatedAt: Long = 0L,
    val genre: String = "",
    val genreSource: String = "",
    val hidden: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("ytId", ytId)
        put("relPath", relPath)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("duration", duration)
        put("year", year)
        put("addedAt", addedAt)
        put("liked", liked)
        put("playCount", playCount)
        put("lastPlayed", lastPlayed)
        put("hasCover", hasCover)
        put("hasCoverFile", hasCoverFile)
        put("hasLyricsFile", hasLyricsFile)
        put("format", format)
        put("bitrate", bitrate)
        put("fileSize", fileSize)
        put("mtimeUnix", mtimeUnix)
        put("updatedAt", updatedAt)
        put("genre", genre)
        put("genreSource", genreSource)
        put("hidden", hidden)
    }

    companion object {
        fun fromJson(o: JSONObject): SyncTrack = SyncTrack(
            id = o.optString("id", ""),
            ytId = o.optString("ytId", ""),
            relPath = o.optString("relPath", ""),
            title = o.optString("title", ""),
            artist = o.optString("artist", ""),
            album = o.optString("album", ""),
            duration = o.optDouble("duration", 0.0),
            year = o.optInt("year", 0),
            addedAt = o.optLong("addedAt", 0L),
            liked = o.optBoolean("liked", false),
            playCount = o.optInt("playCount", 0),
            lastPlayed = o.optLong("lastPlayed", 0L),
            hasCover = o.optBoolean("hasCover", false),
            hasCoverFile = o.optBoolean("hasCoverFile", false),
            hasLyricsFile = o.optBoolean("hasLyricsFile", false),
            format = o.optString("format", ""),
            bitrate = o.optInt("bitrate", 0),
            fileSize = o.optLong("fileSize", 0L),
            mtimeUnix = o.optLong("mtimeUnix", 0L),
            updatedAt = o.optLong("updatedAt", 0L),
            genre = o.optString("genre", ""),
            genreSource = o.optString("genreSource", ""),
            hidden = o.optBoolean("hidden", false)
        )
    }
}

data class SyncPlaylist(
    val syncUuid: String,
    val name: String,
    val createdAt: Long = 0L,
    val lastPlayed: Long = 0L,
    val trackIds: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("syncUuid", syncUuid)
        put("name", name)
        put("createdAt", createdAt)
        put("lastPlayed", lastPlayed)
        put("trackIds", JSONArray(trackIds))
    }

    companion object {
        fun fromJson(o: JSONObject): SyncPlaylist {
            val arr = o.optJSONArray("trackIds")
            val tids = ArrayList<String>(arr?.length() ?: 0)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    if (v.isNotEmpty()) tids.add(v)
                }
            }
            return SyncPlaylist(
                syncUuid = o.optString("syncUuid", ""),
                name = o.optString("name", ""),
                createdAt = o.optLong("createdAt", 0L),
                lastPlayed = o.optLong("lastPlayed", 0L),
                trackIds = tids
            )
        }
    }
}

data class SyncPlayEvent(
    val trackId: String,
    val playedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("trackId", trackId)
        put("playedAt", playedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): SyncPlayEvent = SyncPlayEvent(
            trackId = o.optString("trackId", ""),
            playedAt = o.optLong("playedAt", 0L)
        )
    }
}

data class SyncArtistGenre(
    val artistKey: String,
    val genre: String,
    val source: String,
    val checkedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("artistKey", artistKey)
        put("genre", genre)
        put("source", source)
        put("checkedAt", checkedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): SyncArtistGenre = SyncArtistGenre(
            artistKey = o.optString("artistKey", ""),
            genre = o.optString("genre", ""),
            source = o.optString("source", ""),
            checkedAt = o.optLong("checkedAt", 0L)
        )
    }
}

data class SyncTrackContext(
    val cacheKey: String,
    val hints: String,
    val updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("cacheKey", cacheKey)
        put("hints", hints)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): SyncTrackContext = SyncTrackContext(
            cacheKey = o.optString("cacheKey", ""),
            hints = o.optString("hints", ""),
            updatedAt = o.optLong("updatedAt", 0L)
        )
    }
}

data class SyncManifest(
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    val generatedAt: Long,
    val libraryUnmounted: Boolean = false,
    val tracks: List<SyncTrack> = emptyList(),
    val playlists: List<SyncPlaylist> = emptyList(),
    val playHistory: List<SyncPlayEvent> = emptyList(),
    val hiddenTrackIds: List<String> = emptyList(),
    val artistGenres: List<SyncArtistGenre> = emptyList(),
    val trackContexts: List<SyncTrackContext> = emptyList(),
    val syncedYtCollections: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("deviceId", deviceId)
        put("deviceName", deviceName)
        put("platform", platform)
        put("generatedAt", generatedAt)
        put("libraryUnmounted", libraryUnmounted)
        put("tracks", JSONArray().apply { tracks.forEach { put(it.toJson()) } })
        put("playlists", JSONArray().apply { playlists.forEach { put(it.toJson()) } })
        put("playHistory", JSONArray().apply { playHistory.forEach { put(it.toJson()) } })
        put("hiddenTrackIds", JSONArray(hiddenTrackIds))
        put("artistGenres", JSONArray().apply { artistGenres.forEach { put(it.toJson()) } })
        put("trackContexts", JSONArray().apply { trackContexts.forEach { put(it.toJson()) } })
        put("syncedYtCollections", JSONArray(syncedYtCollections))
    }

    companion object {
        fun fromJson(o: JSONObject): SyncManifest {
            fun <T> parseArr(key: String, mapFn: (JSONObject) -> T): List<T> {
                val arr = o.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(mapFn) }
            }
            fun parseStrArr(key: String): List<String> {
                val arr = o.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").takeIf { it.isNotEmpty() } }
            }
            return SyncManifest(
                deviceId = o.optString("deviceId", ""),
                deviceName = o.optString("deviceName", ""),
                platform = o.optString("platform", ""),
                generatedAt = o.optLong("generatedAt", 0L),
                libraryUnmounted = o.optBoolean("libraryUnmounted", false),
                tracks = parseArr("tracks") { SyncTrack.fromJson(it) },
                playlists = parseArr("playlists") { SyncPlaylist.fromJson(it) },
                playHistory = parseArr("playHistory") { SyncPlayEvent.fromJson(it) },
                hiddenTrackIds = parseStrArr("hiddenTrackIds"),
                artistGenres = parseArr("artistGenres") { SyncArtistGenre.fromJson(it) },
                trackContexts = parseArr("trackContexts") { SyncTrackContext.fromJson(it) },
                syncedYtCollections = parseStrArr("syncedYtCollections")
            )
        }
    }
}

data class SyncSnapshotTrack(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: Int = 0,
    val liked: Boolean = false,
    val playCount: Int = 0,
    val lastPlayed: Long = 0L,
    val genre: String = "",
    val genreSource: String = "",
    val hidden: Boolean = false,
    val updatedAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("year", year)
        put("liked", liked)
        put("playCount", playCount)
        put("lastPlayed", lastPlayed)
        put("genre", genre)
        put("genreSource", genreSource)
        put("hidden", hidden)
        put("updatedAt", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): SyncSnapshotTrack = SyncSnapshotTrack(
            id = o.optString("id", ""),
            title = o.optString("title", ""),
            artist = o.optString("artist", ""),
            album = o.optString("album", ""),
            year = o.optInt("year", 0),
            liked = o.optBoolean("liked", false),
            playCount = o.optInt("playCount", 0),
            lastPlayed = o.optLong("lastPlayed", 0L),
            genre = o.optString("genre", ""),
            genreSource = o.optString("genreSource", ""),
            hidden = o.optBoolean("hidden", false),
            updatedAt = o.optLong("updatedAt", 0L)
        )
    }
}

data class SyncSnapshotPlaylist(
    val syncUuid: String,
    val name: String = "",
    val lastPlayed: Long = 0L,
    val trackIds: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("syncUuid", syncUuid)
        put("name", name)
        put("lastPlayed", lastPlayed)
        put("trackIds", JSONArray(trackIds))
    }

    companion object {
        fun fromJson(o: JSONObject): SyncSnapshotPlaylist {
            val arr = o.optJSONArray("trackIds")
            val tids = ArrayList<String>(arr?.length() ?: 0)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val v = arr.optString(i, "")
                    if (v.isNotEmpty()) tids.add(v)
                }
            }
            return SyncSnapshotPlaylist(
                syncUuid = o.optString("syncUuid", ""),
                name = o.optString("name", ""),
                lastPlayed = o.optLong("lastPlayed", 0L),
                trackIds = tids
            )
        }
    }
}

data class SyncSnapshot(
    val syncedAt: Long = 0L,
    val tracks: Map<String, SyncSnapshotTrack> = emptyMap(),
    val playlists: Map<String, SyncSnapshotPlaylist> = emptyMap()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("syncedAt", syncedAt)
        put("tracks", JSONObject().apply {
            for ((k, v) in tracks) put(k, v.toJson())
        })
        put("playlists", JSONObject().apply {
            for ((k, v) in playlists) put(k, v.toJson())
        })
    }

    companion object {
        fun fromJson(o: JSONObject): SyncSnapshot {
            val tObj = o.optJSONObject("tracks")
            val tMap = HashMap<String, SyncSnapshotTrack>()
            if (tObj != null) {
                val keys = tObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    tObj.optJSONObject(k)?.let { tMap[k] = SyncSnapshotTrack.fromJson(it) }
                }
            }
            val pObj = o.optJSONObject("playlists")
            val pMap = HashMap<String, SyncSnapshotPlaylist>()
            if (pObj != null) {
                val keys = pObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    pObj.optJSONObject(k)?.let { pMap[k] = SyncSnapshotPlaylist.fromJson(it) }
                }
            }
            return SyncSnapshot(
                syncedAt = o.optLong("syncedAt", 0L),
                tracks = tMap,
                playlists = pMap
            )
        }
    }
}

data class SyncCommitPayload(
    val senderDeviceId: String = "",
    val senderDeviceName: String = "",
    val senderPlatform: String = "",
    val tracksMeta: List<SyncTrack> = emptyList(),
    val deletedTrackIds: List<String> = emptyList(),
    val playlists: List<SyncPlaylist> = emptyList(),
    val deletedPlaylistUuids: List<String> = emptyList(),
    val playHistory: List<SyncPlayEvent> = emptyList(),
    val hiddenTrackIds: List<String> = emptyList(),
    val artistGenres: List<SyncArtistGenre> = emptyList(),
    val trackContexts: List<SyncTrackContext> = emptyList(),
    val syncedYtCollections: List<String> = emptyList(),
    val newSnapshot: SyncSnapshot = SyncSnapshot()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("senderDeviceId", senderDeviceId)
        put("senderDeviceName", senderDeviceName)
        put("senderPlatform", senderPlatform)
        put("tracksMeta", JSONArray().apply { tracksMeta.forEach { put(it.toJson()) } })
        put("deletedTrackIds", JSONArray(deletedTrackIds))
        put("playlists", JSONArray().apply { playlists.forEach { put(it.toJson()) } })
        put("deletedPlaylistUuids", JSONArray(deletedPlaylistUuids))
        put("playHistory", JSONArray().apply { playHistory.forEach { put(it.toJson()) } })
        put("hiddenTrackIds", JSONArray(hiddenTrackIds))
        put("artistGenres", JSONArray().apply { artistGenres.forEach { put(it.toJson()) } })
        put("trackContexts", JSONArray().apply { trackContexts.forEach { put(it.toJson()) } })
        put("syncedYtCollections", JSONArray(syncedYtCollections))
        put("newSnapshot", newSnapshot.toJson())
    }

    companion object {
        fun fromJson(o: JSONObject): SyncCommitPayload {
            fun <T> parseArr(key: String, mapFn: (JSONObject) -> T): List<T> {
                val arr = o.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(mapFn) }
            }
            fun parseStrArr(key: String): List<String> {
                val arr = o.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length()).mapNotNull { i -> arr.optString(i, "").takeIf { it.isNotEmpty() } }
            }
            return SyncCommitPayload(
                senderDeviceId = o.optString("senderDeviceId", ""),
                senderDeviceName = o.optString("senderDeviceName", ""),
                senderPlatform = o.optString("senderPlatform", ""),
                tracksMeta = parseArr("tracksMeta") { SyncTrack.fromJson(it) },
                deletedTrackIds = parseStrArr("deletedTrackIds"),
                playlists = parseArr("playlists") { SyncPlaylist.fromJson(it) },
                deletedPlaylistUuids = parseStrArr("deletedPlaylistUuids"),
                playHistory = parseArr("playHistory") { SyncPlayEvent.fromJson(it) },
                hiddenTrackIds = parseStrArr("hiddenTrackIds"),
                artistGenres = parseArr("artistGenres") { SyncArtistGenre.fromJson(it) },
                trackContexts = parseArr("trackContexts") { SyncTrackContext.fromJson(it) },
                syncedYtCollections = parseStrArr("syncedYtCollections"),
                newSnapshot = o.optJSONObject("newSnapshot")?.let { SyncSnapshot.fromJson(it) } ?: SyncSnapshot()
            )
        }
    }
}
