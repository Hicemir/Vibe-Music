package com.vibemusic.nativeapp.data

import com.vibemusic.nativeapp.genre.GenreTaxonomy
import java.io.File

data class Track(
    val id: String, // "yt:<11-char-id>" or "local:<sha256>"
    val ytId: String? = null,
    val rootKey: String,
    val relPath: String,
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
    val format: String = "",
    val bitrate: Int = 0,
    val fileSize: Long = 0L,
    val mtimeUnix: Long = 0L,
    val updatedAt: Long = 0L,
    var resolvedPath: String = "",
    val genre: String = "",
    val genreSource: String = ""
) {
    val genres: List<String>
        get() = GenreTaxonomy.splitGenres(genre)
    val displayTitle: String
        get() = title.ifBlank {
            File(relPath).nameWithoutExtension.ifBlank { "Unknown Title" }
        }

    val displayArtist: String
        get() = artist.ifBlank { "Unknown Artist" }

    fun formattedDuration(): String {
        val totalSec = duration.toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }
}

data class Playlist(
    val id: Long,
    val syncUuid: String = "",
    val name: String,
    val createdAt: Long = 0L,
    val lastPlayed: Long = 0L,
    val trackCount: Int = 0,
    val duration: Double = 0.0,
    val coverTracks: List<String> = emptyList()
)

data class AiGenreSettings(
    val enabled: Boolean = false,
    val autoOnNew: Boolean = false,
    val provider: String = "gemini",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "gemini-3.5-flash-lite",
    val allowCustomGenres: Boolean = false,
    val webSearch: Boolean = true,
    val geminiGrounding: Boolean = false
)

data class AppSettings(
    val musicDirs: List<String> = emptyList(),
    val downloadDir: String = "",
    val downloadFormat: String = "m4a", // m4a | opus | mp3 | best
    val volume: Int = 100,
    val shuffle: Boolean = false,
    val repeat: String = "off", // off | all | one
    val fadeSeconds: Float = 0.5f,
    val replayGain: Boolean = true,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "Flat",
    val eqPreamp: Float = 0f, // -12dB to +6dB
    val eqBands: FloatArray = FloatArray(10), // -12dB to +12dB
    val autoUpdateYtdlp: Boolean = true,
    val homeOrder: List<String> = listOf(
        "recentPlaylists",
        "quickPicks",
        "topTracks",
        "artistRadios",
        "discover",
        "recentlyAdded"
    ),
    // Online Discovery is off by default
    val homeHidden: Set<String> = setOf("discover"),
    val enableOnlineFeatures: Boolean = true,
    val ai: AiGenreSettings = AiGenreSettings()
)

data class DiscoverItem(
    val id: String, // "yt:<video_id>"
    val ytId: String,
    val title: String,
    val artist: String,
    val duration: Double = 0.0,
    val thumbnailUrl: String = "",
    val isOwned: Boolean = false
)

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class Lyrics(
    val trackId: String,
    val isSynced: Boolean,
    val lines: List<LyricLine>,
    val raw: String = ""
)

data class DownloadJob(
    val id: String,
    val url: String,
    val ytId: String = "",
    val mode: String, // "track" | "playlist"
    val status: String = "pending", // pending, downloading, done, error, cancelled
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speed: String = "",
    val eta: String = "",
    val stage: String = "",
    val title: String = "",
    val error: String? = null,
    val targetPlaylistId: Long? = null,
    val targetPlaylistIds: List<Long> = emptyList(),
    val markAsLiked: Boolean = false,
    val albumOverride: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class YtCollectionType {
    LIKED_MUSIC,
    PLAYLIST,
    ALBUM
}

data class YtMusicCollection(
    val id: String,          // "LM", "PL...", "OLAK5uy_...", "MPREb_..."
    val browseId: String,    // "VLLM", "VLPL...", "MPREb_..."
    val playlistUrl: String, // "https://music.youtube.com/playlist?list=..."
    val title: String,
    val subtitle: String = "",
    val trackCountHint: Int = 0,
    val thumbnailUrl: String = "",
    val type: YtCollectionType
)

data class YtMusicTrackItem(
    val ytId: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Double = 0.0
)

data class PlayerState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isShuffle: Boolean = false,
    val repeatMode: String = "off", // off, all, one
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val sleepTimerMinutes: Int = 0
)
