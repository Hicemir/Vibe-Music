package com.vibemusic.nativeapp.ytdlp

import android.content.Context
import android.os.Environment
import android.util.Log
import com.vibemusic.nativeapp.data.CanonicalKey
import com.vibemusic.nativeapp.data.DiscoverItem
import com.vibemusic.nativeapp.data.DownloadJob
import com.vibemusic.nativeapp.data.RecommendationEngine
import com.vibemusic.nativeapp.data.Track
import com.vibemusic.nativeapp.data.VibeStore
import com.vibemusic.nativeapp.data.YtCollectionType
import com.vibemusic.nativeapp.data.YtMusicCollection
import com.vibemusic.nativeapp.genre.GenreTaxonomy
import com.vibemusic.nativeapp.scanner.LibraryScanner
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

class YtdlpManager(
    private val context: Context,
    private val store: VibeStore,
    private val scanner: LibraryScanner
) {
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    val authManager = YtMusicAuthManager(context)
    val ytMusicClient = YtMusicClient(authManager)
    private val downloadSemaphore = Semaphore(1)
    private val discoverySemaphore = Semaphore(3)
    private val updateMutex = Mutex()
    private val initReady = CompletableDeferred<Unit>()
    private val updatedMarkerFile: File
        get() = File(context.filesDir, "ytdlp_updated_v1")

    private val _jobs = MutableStateFlow(store.loadDownloadHistory())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val _statusMessage = MutableStateFlow("yt-dlp ready")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _isSyncingYtMusic = MutableStateFlow(false)
    val isSyncingYtMusic: StateFlow<Boolean> = _isSyncingYtMusic.asStateFlow()

    var onDownloadComplete: (() -> Unit)? = null

    private var isInitialized = false

    private val seenMatcherByScope = ConcurrentHashMap<String, CanonicalKey.SongMatcher>()
    private val scopeSeedCounter = ConcurrentHashMap<String, Int>()
    private val shownHomeArtists = ConcurrentHashMap.newKeySet<String>()

    private val ytIdUrlRegex = Regex("""(?:[?&]v=|youtu\.be/|youtube\.com/shorts/|youtube\.com/embed/)([A-Za-z0-9_-]{11})""")
    private val archiveSkipRegex = Regex("""\[download\]\s+([A-Za-z0-9_-]{11}):\s+has already been recorded in the archive""")
    private val stdDownloadRegex = Regex(
        """\[download\]\s+(\d+(?:\.\d+)?)%\s+of\s+~?\s*([0-9.]+)\s*([KMG]i?B)(?:\s+at\s+([0-9.]+\s*[KMG]i?B/s))?(?:\s+ETA\s+([0-9:]+))?"""
    )

    fun extractYtId(url: String): String {
        val trimmed = url.trim()
        if (CanonicalKey.isValidYtId(trimmed)) return trimmed
        return ytIdUrlRegex.find(trimmed)?.groupValues?.getOrNull(1) ?: ""
    }

    @Synchronized
    fun clearHistory() {
        val active = _jobs.value.filter { it.status == "downloading" || it.status == "pending" }
        _jobs.value = active
        store.saveDownloadHistory(active)
    }

    @Synchronized
    private fun updateJob(jobId: String, persist: Boolean = false, transform: (DownloadJob) -> DownloadJob) {
        val updatedList = _jobs.value.map { if (it.id == jobId) transform(it) else it }
        _jobs.value = updatedList
        if (persist) {
            store.saveDownloadHistory(updatedList)
        }
    }

    private fun cleanSpeedString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() ||
            trimmed.equals("NA", ignoreCase = true) ||
            trimmed.equals("N/A", ignoreCase = true) ||
            trimmed.equals("None", ignoreCase = true) ||
            trimmed.contains("Unknown", ignoreCase = true)
        ) {
            return ""
        }
        return trimmed
            .replace("GiB/s", "GB/s")
            .replace("MiB/s", "MB/s")
            .replace("KiB/s", "KB/s")
    }

    private fun cleanMetaString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() ||
            trimmed.equals("NA", ignoreCase = true) ||
            trimmed.equals("N/A", ignoreCase = true) ||
            trimmed.equals("None", ignoreCase = true) ||
            trimmed.contains("Unknown", ignoreCase = true)
        ) {
            return ""
        }
        return trimmed
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        if (bytesPerSec <= 0.0) return ""
        val mb = 1024.0 * 1024.0
        val kb = 1024.0
        return when {
            bytesPerSec >= mb -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / mb)
            bytesPerSec >= kb -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / kb)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }

    private fun parseSizeToBytes(valueStr: String, unit: String): Long {
        val v = valueStr.toDoubleOrNull() ?: return 0L
        val mult = when (unit.uppercase(Locale.US)) {
            "B" -> 1.0
            "KB", "KIB" -> 1024.0
            "MB", "MIB" -> 1024.0 * 1024.0
            "GB", "GIB" -> 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }
        return (v * mult).toLong()
    }

    fun init() {
        if (isInitialized) return
        scope.launch {
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                Aria2c.getInstance().init(context)
                isInitialized = true
                _statusMessage.value = "yt-dlp ready"

                val settings = store.loadSettings()
                if (settings.autoUpdateYtdlp) {
                    if (!updatedMarkerFile.exists()) {
                        withTimeoutOrNull(25_000L) {
                            performUpdateYtdlp()
                        }
                    } else {
                        scope.launch { performUpdateYtdlp() }
                    }
                }
            } catch (e: Exception) {
                Log.e("YtdlpManager", "Failed to initialize YoutubeDL", e)
                _statusMessage.value = "Init error: ${e.message}"
            } finally {
                if (!initReady.isCompleted) {
                    initReady.complete(Unit)
                }
            }
        }
    }

    fun updateYtdlp() {
        if (_isUpdating.value) return
        scope.launch {
            performUpdateYtdlp()
        }
    }

    private suspend fun performUpdateYtdlp() {
        updateMutex.withLock {
            if (_isUpdating.value) return
            _isUpdating.value = true
            _statusMessage.value = "Updating yt-dlp..."
            try {
                val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
                _statusMessage.value = "yt-dlp: $status"
                if (status != null) {
                    try { updatedMarkerFile.writeText(status.toString()) } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w("YtdlpManager", "Failed to update yt-dlp", e)
                _statusMessage.value = "yt-dlp ready"
            } finally {
                _isUpdating.value = false
            }
        }
    }

    fun cancelJob(jobId: String) {
        try {
            YoutubeDL.getInstance().destroyProcessById(jobId)
        } catch (e: Exception) {
            Log.w("YtdlpManager", "Failed to kill download process $jobId: ${e.message}")
        }
        updateJob(jobId, persist = true) { cur ->
            cur.copy(status = "cancelled", error = "Cancelled by user", speed = "", eta = "", stage = "")
        }
    }

    fun retryJob(jobId: String) {
        val target = _jobs.value.find { it.id == jobId } ?: return
        if (target.status == "downloading" || target.status == "pending") return
        updateJob(jobId, persist = true) { cur ->
            cur.copy(
                status = "pending",
                progress = 0f,
                downloadedBytes = 0L,
                totalBytes = 0L,
                speed = "",
                eta = "",
                stage = "Queued...",
                error = null
            )
        }
        scope.launch {
            processJob(jobId)
        }
    }

    fun removeJob(jobId: String) {
        synchronized(this) {
            val updated = _jobs.value.filterNot { it.id == jobId }
            _jobs.value = updated
            store.saveDownloadHistory(updated)
        }
    }

    fun enqueueDownload(
        url: String,
        mode: String = "track",
        targetPlaylistId: Long? = null,
        title: String = "",
        markAsLiked: Boolean = false,
        albumOverride: String? = null
    ) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return

        val ytId = extractYtId(trimmed)
        val finalUrl = if (ytId.isNotEmpty() && CanonicalKey.isValidYtId(trimmed)) {
            "https://www.youtube.com/watch?v=$ytId"
        } else {
            trimmed
        }

        synchronized(this) {
            val existingActive = _jobs.value.firstOrNull { job ->
                (job.status == "pending" || job.status == "downloading") &&
                    ((ytId.isNotEmpty() && job.ytId == ytId) || job.url == finalUrl)
            }
            if (existingActive != null) {
                updateJob(existingActive.id, persist = true) { cur ->
                    val mergedIds = (cur.targetPlaylistIds + listOfNotNull(cur.targetPlaylistId, targetPlaylistId))
                        .filter { it > 0L }
                        .distinct()
                    cur.copy(
                        targetPlaylistId = cur.targetPlaylistId ?: targetPlaylistId,
                        targetPlaylistIds = mergedIds,
                        markAsLiked = cur.markAsLiked || markAsLiked,
                        albumOverride = cur.albumOverride ?: albumOverride
                    )
                }
                return
            }

            val initialPlaylistIds = listOfNotNull(targetPlaylistId).filter { it > 0L }.distinct()
            val job = DownloadJob(
                id = UUID.randomUUID().toString(),
                url = finalUrl,
                ytId = ytId,
                mode = mode,
                title = title.ifBlank { finalUrl },
                targetPlaylistId = targetPlaylistId,
                targetPlaylistIds = initialPlaylistIds,
                markAsLiked = markAsLiked,
                albumOverride = albumOverride
            )

            val filtered = if (ytId.isNotEmpty()) {
                _jobs.value.filterNot {
                    it.ytId == ytId && it.status != "pending" && it.status != "downloading"
                }
            } else {
                _jobs.value
            }

            val list = listOf(job) + filtered
            _jobs.value = list
            store.saveDownloadHistory(list)

            scope.launch {
                processJob(job.id)
            }
        }
    }

    fun importYtMusicCollections(
        collections: List<YtMusicCollection>,
        onComplete: ((String) -> Unit)? = null
    ) {
        if (collections.isEmpty()) {
            onComplete?.invoke("No collections selected")
            return
        }
        val mergedSaved = (store.loadSyncedYtCollections() + collections.map { it.id }).toSet()
        store.saveSyncedYtCollections(mergedSaved)

        scope.launch {
            _isSyncingYtMusic.value = true
            var linkedExisting = 0
            var queuedDownloads = 0
            var playlistsTouched = 0
            try {
                for (col in collections) {
                    _statusMessage.value = "Reading YT Music: ${col.title}..."
                    val trackItems = ytMusicClient.fetchCollectionTracks(col)

                    val targetPlId: Long? = when (col.type) {
                        YtCollectionType.LIKED_MUSIC -> null // Goes into built-in Liked Songs
                        YtCollectionType.PLAYLIST, YtCollectionType.ALBUM -> store.getOrCreatePlaylist(col.title)
                    }
                    if (targetPlId != null && targetPlId > 0L) {
                        playlistsTouched++
                    }

                    if (trackItems.isEmpty()) {
                        if (col.playlistUrl.isNotBlank()) {
                            enqueueDownload(
                                url = col.playlistUrl,
                                mode = "playlist",
                                targetPlaylistId = targetPlId,
                                title = col.title,
                                markAsLiked = (col.type == YtCollectionType.LIKED_MUSIC),
                                albumOverride = if (col.type == YtCollectionType.ALBUM) col.title else null
                            )
                            queuedDownloads++
                        }
                        continue
                    }

                    val ownedByYtId = store.getAllTracks(includeHidden = true)
                        .filter { !it.ytId.isNullOrBlank() }
                        .associateBy { it.ytId!! }

                    val existingIdsToLink = mutableListOf<String>()
                    for (item in trackItems) {
                        val existing = ownedByYtId[item.ytId]
                        if (existing != null) {
                            if (col.type == YtCollectionType.LIKED_MUSIC && !existing.liked) {
                                store.setLiked(existing.id, true)
                            }
                            if (col.type == YtCollectionType.ALBUM) {
                                store.updateTrackAlbum(existing.id, col.title)
                            }
                            existingIdsToLink.add(existing.id)
                            linkedExisting++
                        } else {
                            val displayTitle = listOf(item.artist, item.title)
                                .filter { it.isNotBlank() }
                                .joinToString(" - ")
                                .ifBlank { item.ytId }
                            enqueueDownload(
                                url = "https://www.youtube.com/watch?v=${item.ytId}",
                                mode = "track",
                                targetPlaylistId = targetPlId,
                                title = displayTitle,
                                markAsLiked = (col.type == YtCollectionType.LIKED_MUSIC),
                                albumOverride = if (col.type == YtCollectionType.ALBUM) col.title else null
                            )
                            queuedDownloads++
                        }
                    }

                    if (targetPlId != null && targetPlId > 0L && existingIdsToLink.isNotEmpty()) {
                        store.addToPlaylist(targetPlId, existingIdsToLink)
                    }
                }

                val summary = "YT Music import: $linkedExisting linked, $queuedDownloads queued (${collections.size} collections)"
                _statusMessage.value = summary
                onDownloadComplete?.invoke()
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(summary)
                }
            } catch (e: Exception) {
                Log.e("YtdlpManager", "YT Music import failed", e)
                val errMsg = "YT Music import error: ${e.message ?: "failed"}"
                _statusMessage.value = errMsg
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(errMsg)
                }
            } finally {
                _isSyncingYtMusic.value = false
            }
        }
    }

    fun syncYtMusicLibrary(onComplete: ((String) -> Unit)? = null) {
        if (_isSyncingYtMusic.value) {
            onComplete?.invoke("YT Music sync is already running")
            return
        }
        scope.launch {
            _isSyncingYtMusic.value = true
            _statusMessage.value = "Syncing with YouTube Music..."
            try {
                val allCols = ytMusicClient.fetchUserLibrary()
                val savedIds = store.loadSyncedYtCollections()
                val localPlaylistNames = store.getPlaylists().map { it.name.trim().lowercase() }.toSet()

                val toSync = if (savedIds.isEmpty()) {
                    allCols
                } else {
                    allCols.filter { col ->
                        col.type == YtCollectionType.LIKED_MUSIC ||
                            savedIds.contains(col.id) ||
                            localPlaylistNames.contains(col.title.trim().lowercase())
                    }.ifEmpty { allCols }
                }

                _isSyncingYtMusic.value = false
                importYtMusicCollections(toSync, onComplete)
            } catch (e: Exception) {
                _isSyncingYtMusic.value = false
                val errMsg = "Sync failed: ${e.message ?: "unknown error"}"
                _statusMessage.value = errMsg
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(errMsg)
                }
            }
        }
    }

    private suspend fun processJob(jobId: String) = withContext(Dispatchers.IO) {
        downloadSemaphore.withPermit {
        val initialJob = _jobs.value.find { it.id == jobId } ?: return@withPermit
        if (initialJob.status == "cancelled") return@withPermit

        withTimeoutOrNull(30_000L) { initReady.await() }
        updateMutex.withLock { /* wait if a yt-dlp binary update is in progress */ }

        if (!isInitialized) {
            try {
                YoutubeDL.getInstance().init(context)
                FFmpeg.getInstance().init(context)
                Aria2c.getInstance().init(context)
                isInitialized = true
            } catch (e: Exception) {
                updateJob(jobId, persist = true) { cur ->
                    cur.copy(status = "error", error = "Init failed: ${e.message}")
                }
                return@withPermit
            }
        }

        val currentJobSnapshot = _jobs.value.find { it.id == jobId } ?: initialJob
        if (currentJobSnapshot.status == "cancelled") return@withPermit

        val alreadyOwnedTrack = if (currentJobSnapshot.mode == "track" && currentJobSnapshot.ytId.isNotBlank()) {
            store.getAllTracks(includeHidden = true).firstOrNull { it.ytId == currentJobSnapshot.ytId }
        } else null
        if (alreadyOwnedTrack != null) {
            if (currentJobSnapshot.markAsLiked || currentJobSnapshot.url.contains("list=LM")) {
                store.setLiked(alreadyOwnedTrack.id, true)
            }
            if (!currentJobSnapshot.albumOverride.isNullOrBlank()) {
                store.updateTrackAlbum(alreadyOwnedTrack.id, currentJobSnapshot.albumOverride)
            }
            val targetIds = (currentJobSnapshot.targetPlaylistIds + listOfNotNull(currentJobSnapshot.targetPlaylistId))
                .filter { it > 0L }
                .distinct()
            for (plId in targetIds) {
                store.addToPlaylist(plId, listOf(alreadyOwnedTrack.id))
            }
            updateJob(jobId, persist = true) { cur ->
                cur.copy(
                    status = "done",
                    progress = 100f,
                    downloadedBytes = alreadyOwnedTrack.fileSize,
                    totalBytes = alreadyOwnedTrack.fileSize,
                    speed = "",
                    eta = "",
                    stage = ""
                )
            }
            onDownloadComplete?.invoke()
            return@withPermit
        }

        updateJob(jobId, persist = true) { cur ->
            if (cur.status == "cancelled") cur else cur.copy(status = "downloading", stage = "Fetching stream info...")
        }

        val settings = store.loadSettings()

        // Verify write access to download dir, fallback to app-specific external music dir if scoped storage blocks it
        var targetDir = File(settings.downloadDir.ifEmpty {
            val extMusic = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "Music")
            File(extMusic, "VibeMusic").path
        })

        var canWrite = false
        try {
            targetDir.mkdirs()
            val testFile = File(targetDir, ".write_test_${System.currentTimeMillis()}")
            if (testFile.createNewFile()) {
                testFile.delete()
                canWrite = true
            }
        } catch (_: Exception) {}

        if (!canWrite) {
            val appMusic = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                ?: File(context.filesDir, "Music")
            targetDir = File(appMusic, "VibeMusic").apply { mkdirs() }
            val newDirs = (settings.musicDirs + targetDir.absolutePath).distinct()
            store.saveSettings(settings.copy(downloadDir = targetDir.absolutePath, musicDirs = newDirs))
        }

        // Per-job temp dir for intermediate downloads/thumbnails to prevent concurrent downloads colliding
        val tempDir = File(context.cacheDir, "ytdlp_temp/$jobId").apply { mkdirs() }
        val archiveFile = File(context.cacheDir, "dl-archive-$jobId.txt")

        try {
            val existing = store.getAllTracks(includeHidden = true).mapNotNull { it.ytId }
            archiveFile.printWriter().use { out ->
                for (vid in existing) {
                    out.println("youtube $vid")
                    out.println("youtubetab $vid")
                }
            }

            val currentJobUrl = _jobs.value.find { it.id == jobId }?.url ?: initialJob.url
            val currentJobMode = _jobs.value.find { it.id == jobId }?.mode ?: initialJob.mode
            val resolvedYtId = (_jobs.value.find { it.id == jobId }?.ytId ?: initialJob.ytId)
                .ifBlank { extractYtId(currentJobUrl) }

            val audioExtensions = setOf("m4a", "mp3", "opus", "ogg", "flac", "wav")
            var lastSampleTimeMs = System.currentTimeMillis()
            var lastSampleBytes = 0L
            var lastComputedSpeed = ""
            var lastPersistMs = System.currentTimeMillis()
            val postProcessingStarted = AtomicBoolean(false)

            val stageRank = mapOf(
                "Fetching stream info..." to 1,
                "Converting cover art..." to 2,
                "Converting audio..." to 3,
                "Writing metadata tags..." to 4,
                "Embedding cover art..." to 5,
                "Saving to music folder..." to 6,
                "Adding to library..." to 7
            )

            fun enterPostProcessStage(minProgress: Float, stageLabel: String, persist: Boolean = false) {
                updateJob(jobId, persist = persist) { cur ->
                    if (cur.status == "cancelled") return@updateJob cur
                    val resolvedStage = if ((stageRank[cur.stage] ?: 0) > (stageRank[stageLabel] ?: 0)) {
                        cur.stage
                    } else {
                        stageLabel
                    }
                    cur.copy(
                        status = "downloading",
                        progress = maxOf(cur.progress, minProgress),
                        downloadedBytes = maxOf(cur.downloadedBytes, cur.totalBytes),
                        speed = "",
                        eta = "",
                        stage = resolvedStage
                    )
                }
            }

            val tickerJob = scope.launch {
                while (isActive) {
                    delay(400L)
                    val snapshot = _jobs.value.find { it.id == jobId }
                    if (snapshot == null || snapshot.status != "downloading") break
                    if (postProcessingStarted.get()) {
                        updateJob(jobId, persist = false) { cur ->
                            if (cur.status != "downloading") return@updateJob cur
                            val nextPct = (cur.progress + 1.5f).coerceAtMost(90f)
                            val suggestedStage = when {
                                nextPct < 58f && cur.stage == "Converting cover art..." -> "Converting cover art..."
                                nextPct < 72f -> "Converting audio..."
                                nextPct <= 82f -> "Writing metadata tags..."
                                else -> "Embedding cover art..."
                            }
                            val resolvedStage = if ((stageRank[cur.stage] ?: 0) > (stageRank[suggestedStage] ?: 0)) {
                                cur.stage
                            } else {
                                suggestedStage
                            }
                            cur.copy(
                                progress = maxOf(cur.progress, nextPct),
                                downloadedBytes = maxOf(cur.downloadedBytes, cur.totalBytes),
                                speed = "",
                                eta = "",
                                stage = resolvedStage
                            )
                        }
                    }
                }
            }

            var detectedPlaylistTitle = ""
            val skippedYtIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            var lastAttemptError: String? = null

            fun hasDownloadedAudio(): Boolean {
                if (skippedYtIds.isNotEmpty()) return true
                return tempDir.listFiles()?.any { file ->
                    file.isFile && audioExtensions.contains(file.extension.lowercase())
                } == true
            }

            fun executeAttempt(targetUrl: String, useCookies: Boolean, playerClients: String?) {
                if (_jobs.value.find { it.id == jobId }?.status == "cancelled") return
                postProcessingStarted.set(false)
                var tempCookie: File? = null
                try {
                    val request = YoutubeDLRequest(targetUrl)
                    request.addOption("--progress")
                    request.addOption("--newline")
                    request.addOption("--progress-delta", "0.2")
                    request.addOption(
                        "--progress-template",
                        "download:VMPROG|%(progress._percent_str)s|%(progress._speed_str)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress._eta_str)s|%(info.title)s"
                    )
                    request.addOption("--no-colors")
                    if (currentJobMode == "playlist") {
                        request.addOption("--ignore-errors")
                    }
                    request.addOption("--no-overwrites")
                    request.addOption("--download-archive", archiveFile.absolutePath)
                    request.addOption("--cache-dir", context.cacheDir.absolutePath)
                    request.addOption("--paths", "temp:${tempDir.absolutePath}")
                    request.addOption("--embed-metadata")
                    request.addOption("--embed-thumbnail")
                    request.addOption("--parse-metadata", "yt:%(id)s:%(meta_comment)s")
                    request.addOption("--convert-thumbnails", "jpg")
                    request.addOption("--no-warnings")

                    if (useCookies) {
                        tempCookie = authManager.createTempCookiesFile(tempDir)
                        if (tempCookie != null) {
                            request.addOption("--cookies", tempCookie.absolutePath)
                        }
                    }
                    if (!playerClients.isNullOrBlank()) {
                        request.addOption("--extractor-args", "youtube:player_client=$playerClients")
                    }

                    if (currentJobMode == "playlist") {
                        request.addOption("--yes-playlist")
                        request.addOption("--print", "before_dl:VMPL|%(playlist_title)s")
                        request.addOption("--print", "after_move:VMFILE|%(filepath)s|%(playlist_title)s")
                    } else {
                        request.addOption("--no-playlist")
                    }

                    // Audio format arguments
                    when (settings.downloadFormat.lowercase()) {
                        "opus" -> {
                            request.addOption("-f", "bestaudio[ext=opus]/bestaudio/best")
                            request.addOption("-x")
                            request.addOption("--audio-format", "opus")
                        }
                        "mp3" -> {
                            request.addOption("-f", "bestaudio/best")
                            request.addOption("-x")
                            request.addOption("--audio-format", "mp3")
                        }
                        "best" -> {
                            request.addOption("-f", "bestaudio/best")
                            request.addOption("-x")
                            request.addOption("--audio-format", "best")
                        }
                        else -> { // m4a
                            request.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                            request.addOption("-x")
                            request.addOption("--audio-format", "m4a")
                        }
                    }

                    request.addOption("-P", tempDir.absolutePath)
                    request.addOption("-o", "%(title)s [%(id)s].%(ext)s")

                    YoutubeDL.getInstance().execute(request, jobId) { progress, etaInSeconds, line ->
                        val curSnapshot = _jobs.value.find { it.id == jobId } ?: return@execute
                        if (curSnapshot.status == "cancelled") return@execute

                        if (line.contains("ERROR:")) {
                            lastAttemptError = line.trim()
                        }

                        val nowMs = System.currentTimeMillis()
                        val shouldPersistNow = (nowMs - lastPersistMs) >= 1000L
                        if (shouldPersistNow) {
                            lastPersistMs = nowMs
                        }

                        if (line.startsWith("VMPL|")) {
                            val pl = cleanMetaString(line.removePrefix("VMPL|"))
                            if (pl.isNotBlank()) detectedPlaylistTitle = pl
                            return@execute
                        }

                        if (line.startsWith("VMFILE|")) {
                            val parts = line.removePrefix("VMFILE|").split("|", limit = 2)
                            val pl = if (parts.size >= 2) cleanMetaString(parts[1]) else ""
                            if (pl.isNotBlank()) detectedPlaylistTitle = pl
                            return@execute
                        }

                        val skipMatch = archiveSkipRegex.find(line)
                        if (skipMatch != null) {
                            val vid = skipMatch.groupValues[1]
                            if (CanonicalKey.isValidYtId(vid)) {
                                skippedYtIds.add(vid)
                            }
                            return@execute
                        }

                        if (line.contains("VMPROG|")) {
                            val payload = line.substringAfter("VMPROG|")
                            val parts = payload.split("|", limit = 7)
                            if (parts.size >= 6) {
                                val rawPct = parts[0].trim().removeSuffix("%").trim().toFloatOrNull()
                                val rawSpeed = cleanSpeedString(parts[1])
                                var dlBytes = parts[2].trim().toDoubleOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L
                                val totBytes = (parts[3].trim().toDoubleOrNull()?.toLong()?.takeIf { it > 0L }
                                    ?: parts[4].trim().toDoubleOrNull()?.toLong()?.takeIf { it > 0L }
                                    ?: 0L)
                                val etaStr = cleanMetaString(parts[5])
                                val infoTitle = if (parts.size >= 7) cleanMetaString(parts[6]) else ""

                                val rawMediaPct = when {
                                    rawPct != null && !rawPct.isNaN() -> rawPct.coerceIn(0f, 100f)
                                    totBytes > 0L && dlBytes > 0L -> ((dlBytes.toDouble() / totBytes.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
                                    else -> (curSnapshot.progress * 2f).coerceIn(0f, 100f)
                                }
                                if (dlBytes == 0L && totBytes > 0L && rawMediaPct > 0f) {
                                    dlBytes = ((rawMediaPct / 100.0) * totBytes).toLong()
                                }

                                if (dlBytes < lastSampleBytes) {
                                    lastSampleBytes = dlBytes
                                    lastSampleTimeMs = nowMs
                                    lastComputedSpeed = ""
                                } else if (dlBytes > lastSampleBytes) {
                                    val dtMs = nowMs - lastSampleTimeMs
                                    if (dtMs >= 500L) {
                                        val bytesPerSec = ((dlBytes - lastSampleBytes) * 1000.0) / dtMs
                                        lastComputedSpeed = formatSpeed(bytesPerSec)
                                        lastSampleTimeMs = nowMs
                                        lastSampleBytes = dlBytes
                                    }
                                }

                                val scaledPct = (rawMediaPct * 0.5f).coerceIn(0f, 50f)
                                if (rawMediaPct >= 99.9f) {
                                    postProcessingStarted.set(true)
                                    updateJob(jobId, persist = shouldPersistNow) { cur ->
                                        val doneDl = maxOf(dlBytes, totBytes, cur.downloadedBytes, cur.totalBytes)
                                        val doneTot = if (totBytes > 0L) totBytes else maxOf(cur.totalBytes, doneDl)
                                        cur.copy(
                                            status = "downloading",
                                            progress = maxOf(cur.progress, 50f),
                                            downloadedBytes = doneDl,
                                            totalBytes = doneTot,
                                            speed = "",
                                            eta = "",
                                            stage = if (cur.stage.isNotBlank() && cur.progress >= 50f) cur.stage else "Converting audio...",
                                            title = if (infoTitle.isNotEmpty()) infoTitle else cur.title
                                        )
                                    }
                                } else if (!postProcessingStarted.get()) {
                                    val resolvedSpeed = rawSpeed.ifEmpty { lastComputedSpeed }
                                    updateJob(jobId, persist = shouldPersistNow) { cur ->
                                        cur.copy(
                                            status = "downloading",
                                            progress = maxOf(cur.progress, scaledPct),
                                            downloadedBytes = if (dlBytes > 0L) dlBytes else cur.downloadedBytes,
                                            totalBytes = if (totBytes > 0L) totBytes else cur.totalBytes,
                                            speed = resolvedSpeed.ifEmpty { cur.speed },
                                            eta = etaStr,
                                            stage = "",
                                            title = if (infoTitle.isNotEmpty()) infoTitle else cur.title
                                        )
                                    }
                                }
                            }
                            return@execute
                        }

                        val stdMatch = stdDownloadRegex.find(line)
                        if (stdMatch != null) {
                            val fallbackRawPct = (curSnapshot.progress * 2f).coerceIn(0f, 100f)
                            val rawMediaPct = stdMatch.groupValues[1].toFloatOrNull()?.coerceIn(0f, 100f) ?: fallbackRawPct
                            val totBytes = parseSizeToBytes(stdMatch.groupValues[2], stdMatch.groupValues[3])
                            val dlBytes = if (totBytes > 0L) ((rawMediaPct / 100.0) * totBytes).toLong() else curSnapshot.downloadedBytes
                            val parsedSpeed = cleanSpeedString(stdMatch.groupValues[4])
                            val parsedEta = cleanMetaString(stdMatch.groupValues[5])
                            val scaledPct = (rawMediaPct * 0.5f).coerceIn(0f, 50f)

                            if (rawMediaPct >= 99.9f) {
                                postProcessingStarted.set(true)
                                updateJob(jobId, persist = shouldPersistNow) { cur ->
                                    val doneDl = maxOf(dlBytes, totBytes, cur.downloadedBytes, cur.totalBytes)
                                    val doneTot = if (totBytes > 0L) totBytes else maxOf(cur.totalBytes, doneDl)
                                    cur.copy(
                                        status = "downloading",
                                        progress = maxOf(cur.progress, 50f),
                                        downloadedBytes = doneDl,
                                        totalBytes = doneTot,
                                        speed = "",
                                        eta = "",
                                        stage = if (cur.stage.isNotBlank() && cur.progress >= 50f) cur.stage else "Converting audio..."
                                    )
                                }
                            } else if (!postProcessingStarted.get()) {
                                updateJob(jobId, persist = shouldPersistNow) { cur ->
                                    cur.copy(
                                        status = "downloading",
                                        progress = maxOf(cur.progress, scaledPct),
                                        downloadedBytes = dlBytes,
                                        totalBytes = if (totBytes > 0L) totBytes else cur.totalBytes,
                                        speed = parsedSpeed.ifEmpty { cur.speed },
                                        eta = parsedEta.ifEmpty { if (etaInSeconds > 0) "${etaInSeconds}s" else "" },
                                        stage = ""
                                    )
                                }
                            }
                            return@execute
                        }

                        if (line.contains("[download]") && line.contains("Destination:")) {
                            val destName = line.substringAfter("Destination:").trim()
                                .let { File(it).nameWithoutExtension }
                                .replace(Regex("""\s*\[[A-Za-z0-9_-]{11}\]\s*$"""), "")
                                .trim()
                            if (destName.isNotEmpty()) {
                                updateJob(jobId, persist = false) { cur ->
                                    if (cur.title.isBlank() || cur.title == cur.url) {
                                        cur.copy(title = destName)
                                    } else cur
                                }
                            }
                            return@execute
                        }

                        if (!postProcessingStarted.get() && (line.contains("[youtube]") || line.contains("[info]"))) {
                            updateJob(jobId, persist = false) { cur ->
                                if (cur.progress < 5f) {
                                    cur.copy(progress = maxOf(cur.progress, 3f), stage = "Fetching stream info...")
                                } else cur
                            }
                            return@execute
                        }

                        if (line.contains("[ThumbnailsConvertor]") || line.contains("Converting thumbnail")) {
                            postProcessingStarted.set(true)
                            enterPostProcessStage(55f, "Converting cover art...")
                            return@execute
                        }

                        if (line.contains("[ExtractAudio]") || line.contains("[Fixup") || line.contains("[Merger]")) {
                            postProcessingStarted.set(true)
                            enterPostProcessStage(64f, "Converting audio...")
                            return@execute
                        }

                        if (line.contains("[Metadata]") || line.contains("[ModifyChapters]")) {
                            postProcessingStarted.set(true)
                            enterPostProcessStage(76f, "Writing metadata tags...")
                            return@execute
                        }

                        if (line.contains("[EmbedThumbnail]") || line.contains("Adding thumbnail")) {
                            postProcessingStarted.set(true)
                            enterPostProcessStage(84f, "Embedding cover art...")
                            return@execute
                        }

                        if (progress >= 0f && !postProcessingStarted.get()) {
                            val scaledFallback = (progress * 0.5f).coerceIn(0f, 50f)
                            updateJob(jobId, persist = shouldPersistNow) { cur ->
                                cur.copy(
                                    progress = maxOf(cur.progress, scaledFallback),
                                    eta = if (etaInSeconds > 0) "${etaInSeconds}s" else cur.eta
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    val msg = e.message?.trim()
                    if (!msg.isNullOrEmpty()) {
                        lastAttemptError = msg
                    }
                    Log.w("YtdlpManager", "yt-dlp attempt failed (cookies=$useCookies, clients=${playerClients ?: "default"}): $msg")
                } finally {
                    try { tempCookie?.delete() } catch (_: Exception) {}
                }
            }

            val isLoggedIn = authManager.isLoggedIn()
            val usedCookiesInFirst = isLoggedIn
            try {
                // Attempt 1: Authenticated (if logged in) with default clients
                executeAttempt(currentJobUrl, useCookies = usedCookiesInFirst, playerClients = null)

                // Attempt 2: Fallback across cookie-less JS-less clients or authenticated non-default clients
                if (!hasDownloadedAudio() && _jobs.value.find { it.id == jobId }?.status != "cancelled") {
                    if (usedCookiesInFirst) {
                        executeAttempt(
                            currentJobUrl,
                            useCookies = false,
                            playerClients = "android_vr,visionos,ios,tv"
                        )
                    } else if (authManager.isLoggedIn()) {
                        executeAttempt(
                            currentJobUrl,
                            useCookies = true,
                            playerClients = "tv_downgraded,tv_samsung,tv,web_embedded,mweb,web_music,web"
                        )
                    }
                }

                // Attempt 3: Authenticated non-default clients when Attempt 1 used cookies + default clients
                if (!hasDownloadedAudio() && _jobs.value.find { it.id == jobId }?.status != "cancelled" && usedCookiesInFirst && authManager.isLoggedIn()) {
                    executeAttempt(
                        currentJobUrl,
                        useCookies = true,
                        playerClients = "tv_downgraded,tv_samsung,tv,web_embedded,mweb,web_music,web"
                    )
                }

                // Attempt 4: Direct YouTube Music watch URL with web_music / tv_downgraded clients
                if (!hasDownloadedAudio() && _jobs.value.find { it.id == jobId }?.status != "cancelled" &&
                    authManager.isLoggedIn() && currentJobMode == "track" && resolvedYtId.isNotBlank()
                ) {
                    executeAttempt(
                        "https://music.youtube.com/watch?v=$resolvedYtId",
                        useCookies = true,
                        playerClients = "web_music,tv_downgraded,web_embedded"
                    )
                }
            } finally {
                tickerJob.cancel()
            }

            if (_jobs.value.find { it.id == jobId }?.status == "cancelled") {
                return@withPermit
            }

            // Move completed audio files from tempDir to targetDir (or fallback external music dir)
            enterPostProcessStage(92f, "Saving to music folder...")
            val downloadedFiles = tempDir.listFiles()?.filter { file ->
                file.isFile && audioExtensions.contains(file.extension.lowercase())
            } ?: emptyList()

            if (downloadedFiles.isEmpty() && skippedYtIds.isEmpty()) {
                throw RuntimeException(lastAttemptError ?: "Download failed")
            }

            val finalSizeBytes = downloadedFiles.sumOf { it.length() }

            for (dlFile in downloadedFiles) {
                var destFile = File(targetDir, dlFile.name)
                var moved = false
                try {
                    dlFile.copyTo(destFile, overwrite = true)
                    dlFile.delete()
                    moved = true
                } catch (ioe: Exception) {
                    Log.w("YtdlpManager", "Failed writing to targetDir, falling back to app external music: ${ioe.message}")
                }
                if (!moved) {
                    val appMusic = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                        ?: File(context.filesDir, "Music")
                    val fallbackDir = File(appMusic, "VibeMusic").apply { mkdirs() }
                    destFile = File(fallbackDir, dlFile.name)
                    dlFile.copyTo(destFile, overwrite = true)
                    dlFile.delete()
                    val newDirs = (settings.musicDirs + fallbackDir.absolutePath).distinct()
                    store.saveSettings(settings.copy(downloadDir = fallbackDir.absolutePath, musicDirs = newDirs))
                }
            }

            // Rescan library across all standard and user directories to immediately ingest downloaded files
            updateJob(jobId, persist = false) { cur ->
                val knownBytes = if (finalSizeBytes > 0L) finalSizeBytes else maxOf(cur.downloadedBytes, cur.totalBytes)
                cur.copy(
                    status = "downloading",
                    progress = maxOf(cur.progress, 96f),
                    downloadedBytes = knownBytes,
                    totalBytes = if (finalSizeBytes > 0L) finalSizeBytes else cur.totalBytes,
                    speed = "",
                    eta = "",
                    stage = "Adding to library..."
                )
            }
            val allScanDirs = mutableListOf<String>()
            allScanDirs.addAll(settings.musicDirs)
            allScanDirs.add(targetDir.absolutePath)
            allScanDirs.add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath)
            allScanDirs.add(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "VibeMusic").absolutePath)
            context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)?.let {
                allScanDirs.add(it.absolutePath)
                allScanDirs.add(File(it, "VibeMusic").absolutePath)
            }
            val existingDirs = allScanDirs.filter { File(it).exists() }.distinct()
            scanner.scan(existingDirs)

            val allTracksNow = store.getAllTracks(includeHidden = true)
            val preCheckJob = _jobs.value.find { it.id == jobId } ?: initialJob
            val dlYtIds = downloadedFiles.mapNotNull { CanonicalKey.ytIdFromName(it.name) }.toMutableSet()
            dlYtIds.addAll(skippedYtIds)
            if (preCheckJob.ytId.isNotBlank() && (downloadedFiles.isNotEmpty() || skippedYtIds.contains(preCheckJob.ytId))) {
                dlYtIds.add(preCheckJob.ytId)
            }
            val dlNames = downloadedFiles.map { it.name }.toSet()
            val matchedTracks = allTracksNow.filter { tr ->
                (tr.ytId != null && dlYtIds.contains(tr.ytId)) ||
                    dlNames.contains(File(tr.resolvedPath.ifEmpty { tr.relPath }).name)
            }
            val matchedIds = matchedTracks.map { it.id }.distinct()

            val resolvedBytes = if (finalSizeBytes > 0L) {
                finalSizeBytes
            } else {
                val matchedSize = matchedTracks.sumOf { it.fileSize }
                if (matchedSize > 0L) matchedSize else maxOf(preCheckJob.downloadedBytes, preCheckJob.totalBytes)
            }

            lateinit var finishedJob: DownloadJob
            synchronized(this) {
                finishedJob = _jobs.value.find { it.id == jobId } ?: preCheckJob
                updateJob(jobId, persist = true) { cur ->
                    val doneBytes = if (resolvedBytes > 0L) resolvedBytes else maxOf(cur.downloadedBytes, cur.totalBytes)
                    cur.copy(
                        status = "done",
                        progress = 100f,
                        downloadedBytes = doneBytes,
                        totalBytes = doneBytes,
                        speed = "",
                        eta = "",
                        stage = ""
                    )
                }
            }

            if (finishedJob.markAsLiked || currentJobUrl.contains("list=LM")) {
                for (tid in matchedIds) {
                    store.setLiked(tid, true)
                }
            }
            val albumOverride = finishedJob.albumOverride
            if (!albumOverride.isNullOrBlank()) {
                for (tid in matchedIds) {
                    store.updateTrackAlbum(tid, albumOverride)
                }
            }

            val targetIds = (finishedJob.targetPlaylistIds + listOfNotNull(finishedJob.targetPlaylistId))
                .filter { it > 0L }
                .distinct()
                .toMutableList()
            if (currentJobMode == "playlist" && targetIds.isEmpty() && !currentJobUrl.contains("list=LM") && matchedIds.isNotEmpty()) {
                val plName = detectedPlaylistTitle.ifBlank { "Downloaded Playlist" }
                val autoPlId = store.getOrCreatePlaylist(plName)
                if (autoPlId > 0L) targetIds.add(autoPlId)
            }
            for (plId in targetIds) {
                if (matchedIds.isNotEmpty()) {
                    store.addToPlaylist(plId, matchedIds)
                }
            }

            onDownloadComplete?.invoke()
        } catch (e: Exception) {
            val curStatus = _jobs.value.find { it.id == jobId }?.status
            if (curStatus != "cancelled") {
                Log.e("YtdlpManager", "Download failed for $jobId", e)
                updateJob(jobId, persist = true) { cur ->
                    cur.copy(
                        status = "error",
                        error = e.message ?: "Download failed",
                        speed = "",
                        eta = "",
                        stage = ""
                    )
                }
            }
        } finally {
            try { archiveFile.delete() } catch (_: Exception) {}
            try { tempDir.deleteRecursively() } catch (_: Exception) {}
        }
        }
    }

    private data class SeedTarget(
        val artist: String,
        val track: Track,
        val artists: List<String> = emptyList(),
        val artistSet: Set<String> = emptySet(),
        val artistWords: List<String> = emptyList(),
        val tokens: List<String> = emptyList()
    )

    private enum class RadioSource(val rank: Int, val maxPos: Int) {
        YTM(1, 16),
        YT(2, 12),
        SEARCH(3, 18)
    }

    private data class RadioEntry(
        val item: DiscoverItem,
        val verifiedMusic: Boolean
    )

    private data class RadioFetchOutcome(
        val seed: SeedTarget,
        val source: RadioSource,
        val entries: List<RadioEntry>,
        val coherent: Boolean,
        val seedArtistHits: Int = 0,
        val libArtistHits: Int = 0,
        val tokenHits: Int = 0,
        val anchorHits: Int = 0
    )

    private data class LibraryDiscoveryProfile(
        val artists: Set<String>,
        val genreWeight: Map<String, Double>,
        val genreTotal: Double
    )

    private val genericArtistKeys = setOf(
        "", "unknown", "unknown artist", "various artists", "youtube", "release", "auto generated by youtube", "auto-generated by youtube"
    )
    private val tailYtBracketRe = Regex("\\s*\\[[A-Za-z0-9_-]{11}\\]\\s*$")
    private val viewsMetaRe = Regex("\\b\\d+(?:[.,]\\d+)?\\s*[KMB]?\\s*views\\b", RegexOption.IGNORE_CASE)
    private val explicitMusicTitleRe = Regex(
        "(?i)\\b(?:official\\s+(?:audio|music\\s+video|mv|lyric\\s+video|visualizer)|ost|soundtrack|theme\\s+song|character\\s+song|opening\\s+theme|ending\\s+theme|full\\s+ver(?:sion)?)\\b"
    )
    private val searchDurationRe = Regex("^\\d{1,2}(?::\\d{2}){1,2}$")
    private val searchPlaysRe = Regex("^\\d+(?:\\.\\d+)?[KMB]?\\s+(?:plays?|views?)$", RegexOption.IGNORE_CASE)
    private val searchYearRe = Regex("^(?:19|20)\\d{2}$")
    private val searchMediaPrefixes = setOf("song", "video", "album", "single", "ep", "podcast", "episode")

    private val nonMusicTitlePrefixes = listOf(
        "how ", "why ", "who was ", "who is ", "what is ", "what if "
    )

    private val badTitleKeywords = listOf(
        "commentary", "audio commentary", "podcast", "review", "interview",
        "trailer", "teaser", "reaction", "reacts", "livestream", "vlog",
        "compilation", "1 hour", "2 hours", "3 hours", "10 hours",
        "full album", "full ost", "full soundtrack", "mashup", "talk show",
        "audiobook", "#shorts", "asmr", "behind the scenes", "speed up",
        "sped up", "slowed", "parody", "gameplay", "walkthrough", "playthrough",
        "cutscene", "unboxing", "tutorial", "lesson", "karaoke",
        "instrumental", "backing track", "guitar cover", "drum cover",
        "vocal cover", "piano tutorial", "synthesia", "guitar tab",
        "making of", "voice drama", "drama cd", "radio show", "tier list",
        "music theory", "beginner's guide",
        "but it's", "but its", "edited like", "in a nutshell",
        "explained", "explanation", "documentary", "biography", "history of",
        "video essay", "essay", "breakdown", "analysis", "analyzing",
        "critique", "discussion", "retrospective", "the story of",
        "what happened to", "deep dive", "ranking", "ranked",
        "top 10", "top 5", "top 20", "lecture", "meme", "memes",
        "shitpost", "funny moments", "out of context", "deleted scene",
        "movie clip", "scene pack", "inventor", "crash course", "ted-ed",
        "q&a", "guide to", "how to", "writes a", "voicing", "voiceleading",
        "chord progression", "sheet music", "play along", "full episode"
    )

    private val badChannelKeywords = listOf(
        "podcast", "reacts", "reviews", "gaming", "tutorials",
        "lessons", "synthesia", "piano tutorial", "guitar tab", "karaoke",
        "learning", "pbs", "education", "academy", "ted-ed", "tedx",
        "crashcourse", "news", "documentary", "history", "science",
        "explained", "music theory", "memes", "shitpost", "clips",
        "shorts", "comedy", "ign", "gamespot", "movieclips"
    )

    private suspend fun ensureInitialized(): Boolean {
        withTimeoutOrNull(30_000L) { initReady.await() }
        updateMutex.withLock { /* wait if a yt-dlp binary update is in progress */ }
        if (isInitialized) return true
        return try {
            YoutubeDL.getInstance().init(context)
            isInitialized = true
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isNonMusicEntry(title: String, channel: String, duration: Double): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || trimmed == "[Deleted video]" || trimmed == "[Private video]") {
            return true
        }
        if (duration > 0.0 && (duration < 45.0 || duration > 540.0)) {
            return true
        }
        val tl = trimmed.lowercase(Locale.ROOT)
        if (nonMusicTitlePrefixes.any { tl.startsWith(it) } || tl.contains("|| how ") || tl.contains("| how ")) {
            return true
        }
        if (badTitleKeywords.any { tl.contains(it) }) {
            return true
        }
        if (channel.isNotBlank() && badChannelKeywords.any { channel.contains(it, ignoreCase = true) }) {
            return true
        }
        return false
    }

    private fun buildOwnedMatcher(extraTracks: List<Track> = emptyList()): CanonicalKey.SongMatcher {
        val allTracks = store.getAllTracks(includeHidden = true) + extraTracks
        val knownNames = CanonicalKey.collectKnownLibraryNames(allTracks)
        val matcher = CanonicalKey.SongMatcher(knownNames)
        for (t in allTracks) {
            val yt = CanonicalKey.trackVideoId(t)
            matcher.add(yt, t.title, t.artist)
            matcher.add(yt, t.displayTitle, t.displayArtist)
            val stem = File(t.relPath).nameWithoutExtension.replace(tailYtBracketRe, "").trim()
            if (stem.isNotBlank() && stem != t.title && stem != t.displayTitle) {
                matcher.add(yt, stem, t.artist)
            }
        }
        for (job in _jobs.value) {
            if (job.status == "cancelled" || job.status == "error") continue
            val jVid = job.ytId.ifBlank { extractYtId(job.url) }
            val jTitle = job.title.takeIf { it.isNotBlank() && it != job.url && it != jVid }.orEmpty()
            matcher.add(jVid, jTitle, "")
        }
        return matcher
    }

    private fun isTrackOwned(owned: CanonicalKey.SongMatcher, ytId: String, title: String, artist: String): Boolean {
        return owned.matches(ytId, title, artist)
    }

    private fun cleanInnerTubeArtist(raw: String): String {
        var s = raw.trim()
        for (sep in listOf(" • ", " · ")) {
            val idx = s.indexOf(sep)
            if (idx >= 0) {
                s = s.substring(0, idx).trim()
            }
        }
        return s
    }

    private fun cleanEntryArtist(
        rawChannel: String,
        title: String,
        fallbackArtist: String,
        allowSeedFallback: Boolean
    ): String {
        val cleaned = CanonicalKey.cleanArtistString(cleanInnerTubeArtist(rawChannel))
        val isGeneric = cleaned.isBlank() ||
            cleaned.equals("YouTube", ignoreCase = true) ||
            cleaned.equals("Various Artists", ignoreCase = true) ||
            cleaned.equals("Release", ignoreCase = true) ||
            cleaned.equals("Auto-generated by YouTube", ignoreCase = true)
        if (!isGeneric) return cleaned

        for (sep in listOf(" - ", " – ", " — ")) {
            val idx = title.indexOf(sep)
            if (idx > 0) {
                val candidate = CanonicalKey.cleanArtistString(title.substring(0, idx))
                if (candidate.isNotBlank() && !candidate.equals("YouTube", ignoreCase = true)) {
                    return candidate
                }
            }
        }

        return if (allowSeedFallback && fallbackArtist.isNotBlank()) fallbackArtist else "YouTube"
    }

    private fun postInnerTubeJson(
        endpoint: String,
        origin: String,
        clientId: String,
        clientVer: String,
        payload: JSONObject
    ): JSONObject? {
        return try {
            val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 7000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Origin", origin)
                setRequestProperty("Referer", "$origin/")
                setRequestProperty("X-Origin", origin)
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
                setRequestProperty("X-Youtube-Client-Name", clientId)
                setRequestProperty("X-Youtube-Client-Version", clientVer)
                val cookieHeader = authManager.getCookieHeader()
                if (cookieHeader.isNotBlank()) {
                    setRequestProperty("Cookie", cookieHeader)
                    val auth = authManager.buildSapisidHashHeader(origin)
                    if (!auth.isNullOrBlank()) {
                        setRequestProperty("Authorization", auth)
                        setRequestProperty("X-Goog-AuthUser", "0")
                    }
                }
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            conn.disconnect()
            if (code !in 200..299 || text.isBlank()) null else JSONObject(text)
        } catch (_: Exception) {
            null
        }
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

    private fun parseDurationText(raw: String): Double {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return 0.0
        val parts = trimmed.split(':')
        if (parts.size !in 2..3) return 0.0
        var total = 0L
        for ((i, p) in parts.withIndex()) {
            val n = p.trim().toLongOrNull() ?: return 0.0
            if (n < 0 || (i > 0 && n >= 60)) return 0.0
            total = total * 60 + n
        }
        return total.toDouble()
    }

    private fun extractThumbUrl(renderer: JSONObject, videoId: String): String {
        val thumbs = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        var url = ""
        if (thumbs != null && thumbs.length() > 0) {
            url = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "").orEmpty()
        }
        if (url.startsWith("//")) url = "https:$url"
        return url.ifBlank { "https://i.ytimg.com/vi/$videoId/mqdefault.jpg" }
    }

    private fun hasVerifiedArtistBadge(renderer: JSONObject): Boolean {
        val badges = renderer.optJSONArray("ownerBadges") ?: return false
        for (i in 0 until badges.length()) {
            val style = badges.optJSONObject(i)
                ?.optJSONObject("metadataBadgeRenderer")
                ?.optString("style", "")
                .orEmpty()
            if (style == "BADGE_STYLE_TYPE_VERIFIED_ARTIST") return true
        }
        return false
    }

    private fun hasMusicPageRun(textObj: JSONObject?): Boolean {
        val runs = textObj?.optJSONArray("runs") ?: return false
        for (i in 0 until runs.length()) {
            val pageType = runs.optJSONObject(i)
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("browseEndpoint")
                ?.optJSONObject("browseEndpointContextSupportedConfigs")
                ?.optJSONObject("browseEndpointContextMusicConfig")
                ?.optString("pageType", "")
                .orEmpty()
            if (pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_ALBUM") {
                return true
            }
        }
        return false
    }

    private fun parseInnerTubeNextEntries(
        root: JSONObject,
        seedVideoId: String,
        limit: Int,
        fallbackArtist: String,
        isYtm: Boolean
    ): Pair<List<RadioEntry>, Boolean> {
        val out = mutableListOf<RadioEntry>()
        val seenIds = HashSet<String>()
        var containsSeed = false

        fun handlePanelRenderer(vr: JSONObject) {
            if (vr.has("unplayableText")) return
            val vid = vr.optString("videoId", "").trim()
            if (!CanonicalKey.isValidYtId(vid)) return
            if (vid == seedVideoId) containsSeed = true
            if (out.size >= limit || !seenIds.add(vid)) return

            val musicVideoType = vr.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optJSONObject("watchEndpointMusicSupportedConfigs")
                ?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType", "")
                .orEmpty()
            if (vid != seedVideoId && musicVideoType == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE") return

            val title = extractRunsText(vr.optJSONObject("title")).trim()
            if (title.isEmpty()) return
            val shortObj = vr.optJSONObject("shortBylineText")
            val longObj = vr.optJSONObject("longBylineText")
            val longBylineRaw = extractRunsText(longObj)
            val rawChannel = extractRunsText(shortObj)
                .ifBlank { longBylineRaw }
                .trim()
            val dur = parseDurationText(extractRunsText(vr.optJSONObject("lengthText")))
            if (vid != seedVideoId && isNonMusicEntry(title, rawChannel, dur)) return

            val hasMusicPage = hasMusicPageRun(shortObj) || hasMusicPageRun(longObj)
            val hasViewsMeta = viewsMetaRe.containsMatchIn(longBylineRaw)
            val hasArtistBadge = hasVerifiedArtistBadge(vr)
            val isMusicChannel = rawChannel.endsWith("- Topic", ignoreCase = true) ||
                rawChannel.contains("VEVO", ignoreCase = true)

            val verifiedMusic = if (isYtm) {
                musicVideoType == "MUSIC_VIDEO_TYPE_ATV" ||
                    musicVideoType == "MUSIC_VIDEO_TYPE_OMV" ||
                    (hasMusicPage && !hasViewsMeta && musicVideoType != "MUSIC_VIDEO_TYPE_UGC") ||
                    (isMusicChannel && musicVideoType != "MUSIC_VIDEO_TYPE_UGC")
            } else {
                isMusicChannel || hasArtistBadge
            }

            val artist = cleanEntryArtist(rawChannel, title, fallbackArtist, allowSeedFallback = false)
            out.add(
                RadioEntry(
                    item = DiscoverItem(
                        id = "yt:$vid",
                        ytId = vid,
                        title = title,
                        artist = artist,
                        duration = dur,
                        thumbnailUrl = extractThumbUrl(vr, vid),
                        isOwned = false
                    ),
                    verifiedMusic = verifiedMusic
                )
            )
        }

        fun walk(node: Any?) {
            when (node) {
                is JSONObject -> {
                    val ppr = node.optJSONObject("playlistPanelRenderer")
                    if (ppr != null) {
                        val pid = ppr.optString("playlistId", "")
                        if (seedVideoId.isNotEmpty() && pid.contains(seedVideoId)) {
                            containsSeed = true
                        }
                    }
                    val vr = node.optJSONObject("playlistPanelVideoRenderer")
                    if (vr != null) {
                        handlePanelRenderer(vr)
                        return
                    }
                    val wr = node.optJSONObject("playlistPanelVideoWrapperRenderer")
                    if (wr != null) {
                        val counterparts = wr.optJSONArray("counterpart")
                        if (counterparts != null) {
                            for (i in 0 until counterparts.length()) {
                                val cvr = counterparts.optJSONObject(i)
                                    ?.optJSONObject("counterpartRenderer")
                                    ?.optJSONObject("playlistPanelVideoRenderer")
                                if (cvr?.optString("videoId", "") == seedVideoId) {
                                    containsSeed = true
                                }
                            }
                        }
                        wr.optJSONObject("primaryRenderer")?.let { walk(it) }
                        return
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        walk(node.opt(keys.next()))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        walk(node.opt(i))
                    }
                }
            }
        }
        walk(root)
        return out to containsSeed
    }

    private fun fetchInnerTubeRadio(videoId: String, fallbackArtist: String): Pair<List<RadioEntry>, RadioSource>? {
        if (!CanonicalKey.isValidYtId(videoId)) return null

        val ytmBody = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20241118.01.00")
                    put("hl", "en")
                })
            })
            put("videoId", videoId)
            put("playlistId", "RDAMVM$videoId")
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
            put("tunerSettingValue", "AUTOMIX_SETTING_NORMAL")
        }
        postInnerTubeJson(
            "https://music.youtube.com/youtubei/v1/next?prettyPrint=false",
            "https://music.youtube.com",
            "67",
            "1.20241118.01.00",
            ytmBody
        )?.let { root ->
            val (entries, containsSeed) = parseInnerTubeNextEntries(root, videoId, 25, fallbackArtist, isYtm = true)
            val nonSeed = entries.count { it.item.ytId != videoId }
            if (containsSeed && nonSeed >= 3) {
                return entries to RadioSource.YTM
            }
        }

        val ytBody = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20251001.01.00")
                    put("hl", "en")
                })
            })
            put("videoId", videoId)
            put("playlistId", "RD$videoId")
        }
        postInnerTubeJson(
            "https://www.youtube.com/youtubei/v1/next?prettyPrint=false",
            "https://www.youtube.com",
            "1",
            "2.20251001.01.00",
            ytBody
        )?.let { root ->
            val (entries, containsSeed) = parseInnerTubeNextEntries(root, videoId, 20, fallbackArtist, isYtm = false)
            val nonSeed = entries.count { it.item.ytId != videoId }
            if (containsSeed && nonSeed >= 3) {
                return entries to RadioSource.YT
            }
        }
        return null
    }

    private fun fetchYouTubeMusicSongsSearch(query: String, limit: Int, fallbackArtist: String): List<RadioEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20241118.01.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("query", q)
            put("params", "EgWKAQIIAWoMEAMQBBAJEAoQBRAV")
        }
        val root = postInnerTubeJson(
            "https://music.youtube.com/youtubei/v1/search?prettyPrint=false",
            "https://music.youtube.com",
            "67",
            "1.20241118.01.00",
            body
        ) ?: return emptyList()

        val out = mutableListOf<RadioEntry>()
        val seenIds = HashSet<String>()

        fun handleSongRenderer(renderer: JSONObject) {
            if (out.size >= limit) return
            val overlayWatch = renderer.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")

            var vid = renderer.optJSONObject("playlistItemData")?.optString("videoId", "")?.trim().orEmpty()
            if (!CanonicalKey.isValidYtId(vid)) {
                vid = overlayWatch?.optString("videoId", "")?.trim().orEmpty()
            }
            val flexCols = renderer.optJSONArray("flexColumns")
            if (!CanonicalKey.isValidYtId(vid) && flexCols != null) {
                for (i in 0 until flexCols.length()) {
                    val runs = flexCols.optJSONObject(i)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs") ?: continue
                    for (r in 0 until runs.length()) {
                        val cand = runs.optJSONObject(r)
                            ?.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("watchEndpoint")
                            ?.optString("videoId", "")?.trim().orEmpty()
                        if (CanonicalKey.isValidYtId(cand)) {
                            vid = cand
                            break
                        }
                    }
                    if (CanonicalKey.isValidYtId(vid)) break
                }
            }
            if (!CanonicalKey.isValidYtId(vid) || !seenIds.add(vid)) return

            var musicVideoType = overlayWatch
                ?.optJSONObject("watchEndpointMusicSupportedConfigs")
                ?.optJSONObject("watchEndpointMusicConfig")
                ?.optString("musicVideoType", "")
                .orEmpty()
            if (musicVideoType.isEmpty() && flexCols != null && flexCols.length() > 0) {
                musicVideoType = flexCols.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    ?.optJSONObject("text")
                    ?.optJSONArray("runs")
                    ?.optJSONObject(0)
                    ?.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("watchEndpoint")
                    ?.optJSONObject("watchEndpointMusicSupportedConfigs")
                    ?.optJSONObject("watchEndpointMusicConfig")
                    ?.optString("musicVideoType", "")
                    .orEmpty()
            }
            if (musicVideoType == "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" || musicVideoType == "MUSIC_VIDEO_TYPE_UGC") {
                return
            }

            val col0Text = flexCols?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
            val title = extractRunsText(col0Text).trim()
            if (title.isEmpty()) return

            val artists = mutableListOf<String>()
            var dur = 0.0
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
                    if (pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL") {
                        artists.add(text)
                    } else if (dur <= 0.0 && searchDurationRe.matches(text)) {
                        dur = parseDurationText(text)
                    }
                }
            }
            if (col1Text != null && (artists.isEmpty() || dur <= 0.0)) {
                val rawParts = extractRunsText(col1Text).split("•").map { it.trim() }.filter { it.isNotEmpty() }
                for (part in rawParts) {
                    if (searchMediaPrefixes.contains(part.lowercase(Locale.ROOT))) continue
                    if (searchDurationRe.matches(part)) {
                        if (dur <= 0.0) dur = parseDurationText(part)
                        continue
                    }
                    if (searchPlaysRe.matches(part) || searchYearRe.matches(part)) continue
                    if (artists.isEmpty()) {
                        artists.add(part)
                    }
                }
            }
            if (dur <= 0.0) {
                val fixedCols = renderer.optJSONArray("fixedColumns")
                if (fixedCols != null) {
                    for (i in 0 until fixedCols.length()) {
                        val textObj = fixedCols.optJSONObject(i)
                            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                            ?.optJSONObject("text")
                        val durStr = extractRunsText(textObj).trim()
                        if (searchDurationRe.matches(durStr)) {
                            dur = parseDurationText(durStr)
                            break
                        }
                    }
                }
            }

            val artist = CanonicalKey.cleanArtistString(artists.joinToString(", "))
                .ifBlank { fallbackArtist.ifBlank { "YouTube Music" } }
            if (isNonMusicEntry(title, artist, dur)) return

            val thumbs = renderer.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
            var thumbUrl = if (thumbs != null && thumbs.length() > 0) {
                thumbs.optJSONObject(thumbs.length() - 1)?.optString("url", "").orEmpty()
            } else ""
            if (thumbUrl.startsWith("//")) thumbUrl = "https:$thumbUrl"
            if (thumbUrl.isBlank()) thumbUrl = "https://i.ytimg.com/vi/$vid/mqdefault.jpg"

            out.add(
                RadioEntry(
                    item = DiscoverItem(
                        id = "yt:$vid",
                        ytId = vid,
                        title = title,
                        artist = artist,
                        duration = dur,
                        thumbnailUrl = thumbUrl,
                        isOwned = false
                    ),
                    verifiedMusic = true
                )
            )
        }

        fun walk(node: Any?) {
            if (out.size >= limit) return
            when (node) {
                is JSONObject -> {
                    val mr = node.optJSONObject("musicResponsiveListItemRenderer")
                    if (mr != null) {
                        handleSongRenderer(mr)
                        return
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        walk(node.opt(keys.next()))
                        if (out.size >= limit) return
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        walk(node.opt(i))
                        if (out.size >= limit) return
                    }
                }
            }
        }
        walk(root)
        return out
    }

    private fun fetchInnerTubeSearch(query: String, limit: Int, fallbackArtist: String): List<RadioEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val body = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20251001.01.00")
                    put("hl", "en")
                    put("gl", "US")
                })
            })
            put("query", q)
        }
        val root = postInnerTubeJson(
            "https://www.youtube.com/youtubei/v1/search?prettyPrint=false",
            "https://www.youtube.com",
            "1",
            "2.20251001.01.00",
            body
        ) ?: return emptyList()

        val out = mutableListOf<RadioEntry>()
        val seenIds = HashSet<String>()
        fun walk(node: Any?) {
            if (out.size >= limit) return
            when (node) {
                is JSONObject -> {
                    val vr = node.optJSONObject("videoRenderer")
                    if (vr != null) {
                        if (vr.has("unplayableText")) return
                        val vid = vr.optString("videoId", "").trim()
                        if (!CanonicalKey.isValidYtId(vid) || !seenIds.add(vid)) return
                        val title = extractRunsText(vr.optJSONObject("title")).trim()
                        if (title.isEmpty()) return
                        val rawChannel = extractRunsText(vr.optJSONObject("ownerText"))
                            .ifBlank { extractRunsText(vr.optJSONObject("longBylineText")) }
                            .ifBlank { extractRunsText(vr.optJSONObject("shortBylineText")) }
                            .trim()
                        val dur = parseDurationText(extractRunsText(vr.optJSONObject("lengthText")))
                        if (isNonMusicEntry(title, rawChannel, dur)) return
                        val verified = hasVerifiedArtistBadge(vr) ||
                            rawChannel.endsWith("- Topic", ignoreCase = true) ||
                            rawChannel.contains("VEVO", ignoreCase = true)
                        val artist = cleanEntryArtist(rawChannel, title, fallbackArtist, allowSeedFallback = true)
                        out.add(
                            RadioEntry(
                                item = DiscoverItem(
                                    id = "yt:$vid",
                                    ytId = vid,
                                    title = title,
                                    artist = artist,
                                    duration = dur,
                                    thumbnailUrl = extractThumbUrl(vr, vid),
                                    isOwned = false
                                ),
                                verifiedMusic = verified
                            )
                        )
                        return
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        walk(node.opt(keys.next()))
                        if (out.size >= limit) return
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        walk(node.opt(i))
                        if (out.size >= limit) return
                    }
                }
            }
        }
        walk(root)
        return out
    }

    private fun normDiscoveryArtists(raw: String): List<String> {
        val cleaned = cleanInnerTubeArtist(raw)
        if (cleaned.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        fun add(cand: String) {
            val k = CanonicalKey.normName(cand)
            if (genericArtistKeys.contains(k) || !seen.add(k)) return
            out.add(k)
        }
        for (a in CanonicalKey.splitArtists(cleaned)) add(a)
        if (out.isEmpty()) add(cleaned)
        return out
    }

    private fun extractDistinctiveTokens(title: String, album: String, artistNorms: List<String>): List<String> {
        val artistToks = HashSet<String>()
        for (an in artistNorms) {
            for (w in CanonicalKey.normSongText(an).split(' ')) {
                if (w.isNotEmpty()) artistToks.add(w)
            }
        }
        val cleaned = "${CanonicalKey.stripTitleDecorations(title)} ${CanonicalKey.stripTitleDecorations(album)}".trim()
        return CanonicalKey.titleTokens(cleaned).filter { tok ->
            !artistToks.contains(tok) && (tok.length >= 4 || tok.any { it.code > 127 })
        }
    }

    private data class EntryMatchSignals(
        val matchesSeedArtist: Boolean,
        val matchesLibArtist: Boolean,
        val matchesSeedArtistChannel: Boolean,
        val matchesLibArtistChannel: Boolean,
        val matchesSeedToken: Boolean,
        val entryArtists: List<String>
    )

    private fun evaluateEntrySignals(
        seed: SeedTarget,
        item: DiscoverItem,
        libArtists: Set<String>
    ): EntryMatchSignals {
        val entryArtists = normDiscoveryArtists(item.artist)
        var msaChan = false
        var mlaChan = false
        for (ea in entryArtists) {
            if (seed.artistSet.contains(ea)) {
                msaChan = true
            } else if (libArtists.contains(ea)) {
                mlaChan = true
            }
        }
        var msa = msaChan
        val titleWords = CanonicalKey.normSongText(item.title)
        if (!msa && titleWords.isNotEmpty() && seed.artistWords.isNotEmpty()) {
            val padded = " $titleWords "
            for (saw in seed.artistWords) {
                if (saw.length >= 3 && padded.contains(" $saw ")) {
                    msa = true
                    break
                }
            }
        }
        var mst = false
        if (seed.tokens.isNotEmpty() && titleWords.isNotEmpty()) {
            val paddedTitle = " $titleWords "
            var matchedTokens = 0
            var hasStrongToken = false
            for (tok in seed.tokens) {
                if (paddedTitle.contains(" $tok ")) {
                    matchedTokens++
                    if (tok.length >= 8 || tok.any { it.code > 127 }) {
                        hasStrongToken = true
                    }
                }
            }
            mst = matchedTokens >= 2 || hasStrongToken
        }
        return EntryMatchSignals(msa, mlaChan, msaChan, mlaChan, mst, entryArtists)
    }

    private fun evaluateMixCoherence(
        seed: SeedTarget,
        entries: List<RadioEntry>,
        source: RadioSource,
        libArtists: Set<String>
    ): RadioFetchOutcome {
        if (source == RadioSource.SEARCH) {
            return RadioFetchOutcome(seed, source, entries, coherent = entries.isNotEmpty())
        }
        val seedVid = CanonicalKey.trackVideoId(seed.track)
        var seedArtistHits = 0
        var libArtistHits = 0
        var tokenHits = 0
        var anchorHits = 0
        var inspected = 0
        for (entry in entries) {
            val item = entry.item
            if (item.ytId == seedVid) continue
            val sig = evaluateEntrySignals(seed, item, libArtists)
            if (entry.verifiedMusic || sig.matchesSeedArtistChannel || sig.matchesLibArtistChannel) {
                if (sig.matchesSeedArtist) seedArtistHits++
                if (sig.matchesLibArtist) libArtistHits++
                if (sig.matchesSeedToken) tokenHits++
                if (sig.matchesSeedArtist || sig.matchesLibArtist || sig.matchesSeedToken) anchorHits++
            }
            inspected++
            if (inspected >= 14) break
        }
        val coherent = when (source) {
            RadioSource.YTM -> anchorHits >= 1
            RadioSource.YT -> seedArtistHits >= 1 || libArtistHits >= 2 || (libArtistHits >= 1 && tokenHits >= 1)
            RadioSource.SEARCH -> entries.isNotEmpty()
        }
        return RadioFetchOutcome(
            seed = seed,
            source = source,
            entries = entries,
            coherent = coherent,
            seedArtistHits = seedArtistHits,
            libArtistHits = libArtistHits,
            tokenHits = tokenHits,
            anchorHits = anchorHits
        )
    }

    private suspend fun fetchFlatPlaylistEntries(
        targetUrl: String,
        playlistEnd: Int = 22,
        fallbackArtist: String = "",
        allowSeedFallback: Boolean = false
    ): List<DiscoverItem> {
        if (!ensureInitialized()) return emptyList()
        val out = mutableListOf<DiscoverItem>()
        return discoverySemaphore.withPermit {
            var tempCookie: File? = null
            try {
                tempCookie = authManager.createTempCookiesFile()
                val request = YoutubeDLRequest(targetUrl)
                if (tempCookie != null) {
                    request.addOption("--cookies", tempCookie.absolutePath)
                }
                request.addOption("--flat-playlist")
                request.addOption("-J")
                if (playlistEnd > 0) {
                    request.addOption("--playlist-end", playlistEnd.toString())
                }
                request.addOption("--no-warnings")

                val response = YoutubeDL.getInstance().execute(request)
                val jsonText = response.out
                if (jsonText.isNullOrBlank()) return@withPermit emptyList()

                val root = JSONObject(jsonText)
                val entries = root.optJSONArray("entries") ?: return@withPermit emptyList()

                for (i in 0 until entries.length()) {
                    val item = entries.optJSONObject(i) ?: continue
                    val id = item.optString("id", "").trim()
                    if (!CanonicalKey.isValidYtId(id)) continue

                    val title = item.optString("title", "").trim()
                    val rawChannel = item.optString("channel", "").ifEmpty {
                        item.optString("uploader", "")
                    }.trim()
                    val rawDur = item.optDouble("duration", 0.0)
                    val duration = if (rawDur.isNaN() || rawDur < 0.0) 0.0 else rawDur

                    if (isNonMusicEntry(title, rawChannel, duration)) continue

                    var thumbnail = item.optString("thumbnail", "")
                    if (thumbnail.isBlank()) {
                        val thumbsArr = item.optJSONArray("thumbnails")
                        if (thumbsArr != null && thumbsArr.length() > 0) {
                            thumbnail = thumbsArr.optJSONObject(thumbsArr.length() - 1)?.optString("url", "") ?: ""
                        }
                    }
                    if (thumbnail.isBlank()) {
                        thumbnail = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
                    }

                    val artist = cleanEntryArtist(rawChannel, title, fallbackArtist, allowSeedFallback)
                    out.add(
                        DiscoverItem(
                            id = "yt:$id",
                            ytId = id,
                            title = title,
                            artist = artist,
                            duration = duration,
                            thumbnailUrl = thumbnail,
                            isOwned = false
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w("YtdlpManager", "Discovery query failed for $targetUrl: ${e.message}")
            } finally {
                try { tempCookie?.delete() } catch (_: Exception) {}
            }
            out
        }
    }

    private fun weightedSelectArtists(
        candidates: List<RecommendationEngine.ArtistAffinity>,
        count: Int
    ): List<RecommendationEngine.ArtistAffinity> {
        if (candidates.isEmpty() || count <= 0) return emptyList()
        val remaining = candidates.map { it to it.score.coerceAtLeast(1.0).pow(1.15) }.toMutableList()
        val picked = mutableListOf<RecommendationEngine.ArtistAffinity>()
        while (picked.size < count && remaining.isNotEmpty()) {
            val totalWeight = remaining.sumOf { it.second }
            if (totalWeight <= 0.0) {
                picked.add(remaining.removeAt(0).first)
                continue
            }
            var r = Random.nextDouble(totalWeight)
            var chosenIdx = remaining.lastIndex
            for (idx in remaining.indices) {
                r -= remaining[idx].second
                if (r <= 0.0) {
                    chosenIdx = idx
                    break
                }
            }
            picked.add(remaining.removeAt(chosenIdx).first)
        }
        return picked
    }

    private fun recordSeenItems(scopeKey: String, items: List<DiscoverItem>) {
        val seenMatcher = seenMatcherByScope.getOrPut(scopeKey) { CanonicalKey.SongMatcher() }
        synchronized(seenMatcher) {
            for (item in items) {
                seenMatcher.add(item.ytId, item.title, item.artist)
            }
        }
    }

    private fun interleaveAndDeduplicate(
        scopeKey: String,
        perSeed: List<List<DiscoverItem>>,
        owned: CanonicalKey.SongMatcher,
        targetSize: Int,
        minBackfillSize: Int,
        allowBackfill: Boolean,
        maxCapRelaxed: Int
    ): List<DiscoverItem> {
        val seenMatcher = seenMatcherByScope.getOrPut(scopeKey) { CanonicalKey.SongMatcher() }
        val batchMatcher = CanonicalKey.SongMatcher(owned.knownNames)

        val out = mutableListOf<DiscoverItem>()
        val deferredArtistCap = mutableListOf<DiscoverItem>()
        val previouslySeen = mutableListOf<DiscoverItem>()
        val artistCounts = mutableMapOf<String, Int>()

        fun tryAdd(item: DiscoverItem, maxPerArtist: Int): Boolean {
            if (isTrackOwned(owned, item.ytId, item.title, item.artist)) return false
            if (batchMatcher.matches(item.ytId, item.title, item.artist)) return false
            val aKey = CanonicalKey.normName(item.artist)
            val cnt = artistCounts.getOrDefault(aKey, 0)
            if (aKey.isNotEmpty() && cnt >= maxPerArtist) return false
            batchMatcher.add(item.ytId, item.title, item.artist)
            if (aKey.isNotEmpty()) artistCounts[aKey] = cnt + 1
            out.add(item)
            return true
        }

        val maxLen = perSeed.maxOfOrNull { it.size } ?: 0
        synchronized(seenMatcher) {
            for (rowIdx in 0 until maxLen) {
                if (out.size >= targetSize) break
                for (seedList in perSeed) {
                    if (out.size >= targetSize) break
                    if (rowIdx >= seedList.size) continue
                    val item = seedList[rowIdx]
                    if (isTrackOwned(owned, item.ytId, item.title, item.artist)) continue
                    if (batchMatcher.matches(item.ytId, item.title, item.artist)) continue

                    if (seenMatcher.matches(item.ytId, item.title, item.artist)) {
                        previouslySeen.add(item)
                        continue
                    }

                    if (!tryAdd(item, 2)) {
                        deferredArtistCap.add(item)
                    }
                }
            }

            if (out.size < minBackfillSize && maxCapRelaxed > 2) {
                for (item in deferredArtistCap) {
                    if (out.size >= targetSize) break
                    tryAdd(item, maxCapRelaxed)
                }
            }

            if (out.size < minBackfillSize && allowBackfill && previouslySeen.isNotEmpty()) {
                seenMatcher.clear()
                val caps = if (maxCapRelaxed > 2) listOf(2, maxCapRelaxed) else listOf(2)
                for (cap in caps) {
                    for (item in previouslySeen) {
                        if (out.size >= targetSize) break
                        tryAdd(item, cap)
                    }
                }
            }
        }

        recordSeenItems(scopeKey, out)
        return out
    }

    /**
     * Personalized Home Online Discovery:
     * - Derives seeds strictly from the user's library, listening time, play history, and likes
     * - Prefers YouTube Music radio mixes (`RDAMVM<vid>`) over generic YouTube `RD` mixes
     * - Rejects incoherent/drifted mixes and genre-clashing mainstream filler
     * - Strictly excludes owned library tracks (including local variants and canonical title matches)
     * - Caps tracks per artist (max 2) so recommendations span many artists
     */
    suspend fun discoverOnline(
        seed: String = "",
        excludeArtists: Set<String> = emptySet(),
        append: Boolean = false,
        forceRefresh: Boolean = false
    ): List<DiscoverItem> = withContext(Dispatchers.IO) {
        if (seed.isNotBlank()) {
            return@withContext discoverForArtist(seed, forceRefresh = forceRefresh)
        }

        val allTracks = store.getAllTracks(includeHidden = false)
        if (allTracks.isEmpty()) return@withContext emptyList()

        val now = System.currentTimeMillis() / 1000
        val recent7d = store.getRecentPlayCounts(now - 7 * 86400L)
        val recent30d = store.getRecentPlayCounts(now - 30 * 86400L)
        val plMap = store.getAllPlaylistTracks()
        val playlistCounts = mutableMapOf<String, Int>()
        for (tids in plMap.values) {
            for (tid in tids.distinct()) {
                playlistCounts[tid] = playlistCounts.getOrDefault(tid, 0) + 1
            }
        }

        val libArtists = HashSet<String>()
        val genreWeight = HashMap<String, Double>()
        var genreTotal = 0.0
        for (t in allTracks) {
            for (a in CanonicalKey.splitArtists(t.artist)) {
                val nk = CanonicalKey.normName(a)
                if (!genericArtistKeys.contains(nk)) libArtists.add(nk)
            }
            if (t.genres.isNotEmpty()) {
                val gw = 1.0 + t.playCount * 0.5 + if (t.liked) 2.0 else 0.0
                for (g in t.genres) {
                    genreWeight[g] = (genreWeight[g] ?: 0.0) + gw
                    genreTotal += gw
                }
            }
        }
        val libProfile = LibraryDiscoveryProfile(libArtists, genreWeight, genreTotal)

        fun buildSeedTarget(artistDisplay: String, track: Track): SeedTarget {
            val artists = mutableListOf<String>()
            val artSet = HashSet<String>()
            val artWords = mutableListOf<String>()
            fun addArt(raw: String) {
                val nk = CanonicalKey.normName(raw)
                if (genericArtistKeys.contains(nk) || !artSet.add(nk)) return
                artists.add(nk)
                val fw = CanonicalKey.normSongText(raw)
                if (fw.isNotEmpty()) artWords.add(fw)
            }
            if (artistDisplay.isNotBlank()) addArt(artistDisplay)
            for (a in CanonicalKey.splitArtists(track.artist)) addArt(a)
            val tokens = extractDistinctiveTokens(track.title, track.album, artists)
            return SeedTarget(artistDisplay, track, artists, artSet, artWords, tokens)
        }

        val rankedArtists = RecommendationEngine.rankLibraryArtistsByAffinity(allTracks, recent7d, recent30d, plMap)
        val round = scopeSeedCounter.compute("home") { _, v -> (v ?: -1) + 1 } ?: 0
        val excludeNorms = excludeArtists.map { CanonicalKey.norm(it) }.filter { it.isNotEmpty() }.toSet()

        val chosenArtists = mutableListOf<RecommendationEngine.ArtistAffinity>()
        val unvisited = rankedArtists.filter {
            !excludeNorms.contains(it.normArtist) && !shownHomeArtists.contains(it.normArtist)
        }
        chosenArtists.addAll(weightedSelectArtists(unvisited, 6))

        if (chosenArtists.size < 6) {
            val pickedNorms = chosenArtists.map { it.normArtist }.toMutableSet()
            val notExcluded = rankedArtists.filter { !pickedNorms.contains(it.normArtist) && !excludeNorms.contains(it.normArtist) }
            if (notExcluded.isNotEmpty()) {
                val extra = weightedSelectArtists(notExcluded, 6 - chosenArtists.size)
                chosenArtists.addAll(extra)
                pickedNorms.addAll(extra.map { it.normArtist })
            }
            if (chosenArtists.size < 6) {
                shownHomeArtists.clear()
                val remaining = rankedArtists.filter { !pickedNorms.contains(it.normArtist) }
                chosenArtists.addAll(weightedSelectArtists(remaining, 6 - chosenArtists.size))
            }
        }
        for (art in chosenArtists) {
            shownHomeArtists.add(art.normArtist)
        }

        val selectedSeeds = mutableListOf<SeedTarget>()
        val usedTrackIds = mutableSetOf<String>()
        for ((idx, aff) in chosenArtists.withIndex()) {
            val ytTracks = aff.topTracks.filter { CanonicalKey.trackVideoId(it).isNotEmpty() && !usedTrackIds.contains(it.id) }
            val pool = ytTracks.ifEmpty { aff.topTracks.filter { !usedTrackIds.contains(it.id) } }.ifEmpty { aff.topTracks }
            if (pool.isNotEmpty()) {
                val pick = pool[(round + idx) % pool.size]
                usedTrackIds.add(pick.id)
                selectedSeeds.add(buildSeedTarget(aff.artist, pick))
            }
        }

        if (selectedSeeds.size < 6) {
            val scoredTracks = allTracks.filter { !usedTrackIds.contains(it.id) }
                .sortedByDescending {
                    val base = RecommendationEngine.scoreTrackAffinity(
                        track = it,
                        nowSec = now,
                        recentPlays7d = recent7d[it.id] ?: 0,
                        recentPlays30d = recent30d[it.id] ?: 0,
                        playlistCount = playlistCounts[it.id] ?: 0
                    )
                    base + if (CanonicalKey.trackVideoId(it).isNotEmpty()) 25.0 else 0.0
                }
            for (t in scoredTracks) {
                if (selectedSeeds.size >= 6) break
                usedTrackIds.add(t.id)
                val primaryArtist = CanonicalKey.splitArtists(t.artist)
                    .firstOrNull { !genericArtistKeys.contains(CanonicalKey.normName(it)) } ?: ""
                if (primaryArtist.isNotEmpty() || chosenArtists.isEmpty()) {
                    selectedSeeds.add(buildSeedTarget(primaryArtist, t))
                }
            }
        }

        if (selectedSeeds.isEmpty()) return@withContext emptyList()

        val owned = buildOwnedMatcher()
        val searchSuffixes = listOf("Topic", "official audio", "music")

        val outcomes: List<RadioFetchOutcome> = coroutineScope {
            selectedSeeds.mapIndexed { idx, s ->
                async(Dispatchers.IO) {
                    val ytId = CanonicalKey.trackVideoId(s.track).takeIf { CanonicalKey.isValidYtId(it) }
                    if (ytId != null) {
                        val radioRes = fetchInnerTubeRadio(ytId, s.artist)
                            ?: if (isInitialized) {
                                val fallbackEntries = fetchFlatPlaylistEntries(
                                    "https://www.youtube.com/watch?v=$ytId&list=RD$ytId",
                                    playlistEnd = 20,
                                    fallbackArtist = s.artist,
                                    allowSeedFallback = false
                                ).map { RadioEntry(it, verifiedMusic = false) }
                                if (fallbackEntries.size >= 3) fallbackEntries to RadioSource.YT else null
                            } else {
                                null
                            }
                        if (radioRes != null) {
                            val evaluated = evaluateMixCoherence(s, radioRes.first, radioRes.second, libArtists)
                            if (evaluated.coherent) {
                                return@async evaluated
                            }
                        }
                    }

                    // Fallback to YouTube Music Songs search first, then standard search when radio failed or drifted
                    var searchItems = if (s.artist.isNotBlank()) {
                        fetchYouTubeMusicSongsSearch(s.artist, 18, s.artist)
                    } else {
                        emptyList()
                    }
                    if (searchItems.isEmpty()) {
                        val suffix = searchSuffixes[(round + idx) % searchSuffixes.size]
                        val queryTerm = if (s.artist.isNotBlank()) "${s.artist} $suffix" else s.track.displayTitle
                        searchItems = fetchInnerTubeSearch(queryTerm, 18, s.artist)
                        if (searchItems.isEmpty() && isInitialized) {
                            searchItems = fetchFlatPlaylistEntries(
                                "ytsearch14:$queryTerm",
                                playlistEnd = 14,
                                fallbackArtist = s.artist,
                                allowSeedFallback = s.artist.isNotBlank()
                            ).map { RadioEntry(it, verifiedMusic = false) }
                        }
                    }
                    RadioFetchOutcome(s, RadioSource.SEARCH, searchItems, coherent = searchItems.isNotEmpty())
                }
            }.awaitAll().filter { it.coherent }
        }

        // Cross-seed consensus counts across coherent outcomes (computed before owned/seen filtering)
        val trackSeedCount = HashMap<String, Int>()
        val artistSeedCount = HashMap<String, Int>()
        for (o in outcomes) {
            val seedVid = CanonicalKey.trackVideoId(o.seed.track)
            val seenVids = HashSet<String>()
            val seenArts = HashSet<String>()
            var pos = 0
            for (entry in o.entries) {
                val item = entry.item
                if (item.ytId == seedVid) continue
                if (pos >= o.source.maxPos) break
                pos++
                if (seenVids.add(item.ytId)) {
                    trackSeedCount[item.ytId] = (trackSeedCount[item.ytId] ?: 0) + 1
                }
                for (ea in normDiscoveryArtists(item.artist)) {
                    if (seenArts.add(ea)) {
                        artistSeedCount[ea] = (artistSeedCount[ea] ?: 0) + 1
                    }
                }
            }
        }

        val artistGenreCache = HashMap<String, List<String>>()
        val rankedPerSeed = outcomes
            .sortedWith(compareBy<RadioFetchOutcome> { it.source.rank }.thenByDescending { it.anchorHits })
            .mapNotNull { o ->
                val seedVid = CanonicalKey.trackVideoId(o.seed.track)
                val scored = mutableListOf<Pair<DiscoverItem, Double>>()
                var pos = 0
                for (entry in o.entries) {
                    val item = entry.item
                    if (item.ytId == seedVid) continue
                    if (pos >= o.source.maxPos) break
                    val curPos = pos++

                    if (isTrackOwned(owned, item.ytId, item.title, item.artist)) continue
                    val sig = evaluateEntrySignals(o.seed, item, libProfile.artists)
                    val primaryArt = sig.entryArtists.firstOrNull().orEmpty()
                    val crossSeed = maxOf(
                        trackSeedCount[item.ytId] ?: 0,
                        if (primaryArt.isNotEmpty()) artistSeedCount[primaryArt] ?: 0 else 0
                    )
                    val corroborated = sig.matchesSeedArtist || sig.matchesLibArtist || sig.matchesSeedToken || crossSeed >= 2

                    val musicOk = entry.verifiedMusic ||
                        sig.matchesSeedArtistChannel ||
                        sig.matchesLibArtistChannel ||
                        (explicitMusicTitleRe.containsMatchIn(item.title) && (sig.matchesSeedArtist || sig.matchesLibArtist || crossSeed >= 2))
                    if (!musicOk) continue

                    val admitted = when (o.source) {
                        RadioSource.SEARCH -> sig.matchesSeedArtist || sig.matchesLibArtist
                        RadioSource.YT -> corroborated || (o.seedArtistHits >= 2 && curPos < 4)
                        RadioSource.YTM -> corroborated || curPos < 8
                    }
                    if (!admitted) continue

                    var genreBonus = 0.0
                    if (libProfile.genreTotal > 0.0 && !sig.matchesSeedArtist && !sig.matchesLibArtist) {
                        val cachedArtGenres = if (primaryArt.isNotEmpty()) {
                            artistGenreCache.getOrPut(primaryArt) {
                                val (gs, _, ok) = store.getArtistGenres(primaryArt)
                                if (ok) gs else emptyList()
                            }
                        } else emptyList()
                        val candGenres = GenreTaxonomy.dedupeGenres(
                            cachedArtGenres + GenreTaxonomy.heuristicGenres(Track(id = item.id, rootKey = "", relPath = "", title = item.title, artist = item.artist)),
                            5
                        )
                        if (candGenres.isNotEmpty()) {
                            var simW = 0.0
                            var clashW = 0.0
                            for ((lg, lw) in libProfile.genreWeight) {
                                val lgList = listOf(lg)
                                simW += lw * GenreTaxonomy.genreSim(candGenres, lgList)
                                clashW += lw * GenreTaxonomy.genreClash(candGenres, lgList)
                            }
                            val sim = min(1.0, simW / libProfile.genreTotal)
                            val clash = min(1.0, clashW / libProfile.genreTotal)
                            if (clash >= 0.55 && sim < 0.25) continue
                            genreBonus = sim * 18.0 - clash * 20.0
                        }
                    }

                    val base = when (o.source) {
                        RadioSource.YTM -> 36.0 - curPos * 2.4
                        RadioSource.YT -> 18.0 - curPos * 3.5
                        RadioSource.SEARCH -> 24.0 - curPos * 1.0
                    }
                    val cohBonus = min(o.anchorHits.toDouble(), 6.0) * 4.0
                    val anchorBonus = (if (sig.matchesSeedArtist) 18.0 else 0.0) +
                        (if (sig.matchesLibArtist) 16.0 else 0.0) +
                        (if (sig.matchesSeedToken) 14.0 else 0.0)
                    val crossBonus = if (crossSeed >= 2) min((crossSeed - 1) * 24.0, 48.0) else 0.0
                    val score = base + cohBonus + anchorBonus + crossBonus + genreBonus + Random.nextDouble() * 14.0
                    scored.add(item to score)
                }
                if (scored.isEmpty()) null else scored.sortedByDescending { it.second }.map { it.first }
            }

        interleaveAndDeduplicate(
            scopeKey = "home",
            perSeed = rankedPerSeed,
            owned = owned,
            targetSize = 24,
            minBackfillSize = 12,
            allowBackfill = !append,
            maxCapRelaxed = 3
        )
    }

    /**
     * Dedicated Artist Radio Online Discovery:
     * - Rotates queries and YouTube Radio Mix seeds (`list=RD<vid>`) across calls
     * - Strictly excludes owned tracks and tracks already shown in the artist radio list
     * - Remembers previously displayed discoveries per artist so refreshes bring fresh songs
     */
    suspend fun discoverForArtist(
        artistName: String,
        forceRefresh: Boolean = false
    ): List<DiscoverItem> = withContext(Dispatchers.IO) {
        val cleanName = artistName.trim()
        if (cleanName.isEmpty()) return@withContext emptyList()

        val scopeKey = "artist:${CanonicalKey.norm(cleanName)}"
        val round = scopeSeedCounter.compute(scopeKey) { _, v -> (v ?: -1) + 1 } ?: 0

        val now = System.currentTimeMillis() / 1000
        val recent7d = store.getRecentPlayCounts(now - 7 * 86400L)
        val recent30d = store.getRecentPlayCounts(now - 30 * 86400L)
        val allTracks = store.getAllTracks(includeHidden = false)
        val artistTracks = allTracks
            .filter { CanonicalKey.hasArtist(it.artist, cleanName) }
            .sortedByDescending {
                RecommendationEngine.scoreTrackAffinity(
                    track = it,
                    nowSec = now,
                    recentPlays7d = recent7d[it.id] ?: 0,
                    recentPlays30d = recent30d[it.id] ?: 0
                )
            }

        val owned = buildOwnedMatcher(extraTracks = artistTracks)
        val searchPool = listOf(
            "$cleanName Topic",
            "$cleanName official audio",
            "$cleanName music",
            "$cleanName songs",
            "$cleanName album",
            "$cleanName ost"
        )

        val artistTracksWithYt = artistTracks.filter { CanonicalKey.trackVideoId(it).isNotEmpty() }
        val sources = mutableListOf<String>()
        sources.add(searchPool[round % searchPool.size])
        sources.add(searchPool[(round + 1) % searchPool.size])
        val radioVid = if (artistTracksWithYt.isNotEmpty()) {
            CanonicalKey.trackVideoId(artistTracksWithYt[round % artistTracksWithYt.size])
        } else {
            sources.add(searchPool[(round + 2) % searchPool.size])
            ""
        }

        val fetched = coroutineScope {
            val ytmSongsJob = async<List<DiscoverItem>>(Dispatchers.IO) {
                fetchYouTubeMusicSongsSearch(cleanName, 25, cleanName)
                    .filter { it.verifiedMusic || CanonicalKey.hasArtist(it.item.artist, cleanName) || explicitMusicTitleRe.containsMatchIn(it.item.title) }
                    .map { it.item }
            }
            val searchJobs = sources.map { q ->
                async(Dispatchers.IO) {
                    val items = fetchInnerTubeSearch(q, 25, cleanName)
                        .filter { it.verifiedMusic || CanonicalKey.hasArtist(it.item.artist, cleanName) || explicitMusicTitleRe.containsMatchIn(it.item.title) }
                        .map { it.item }
                    if (items.isNotEmpty()) items
                    else if (isInitialized) fetchFlatPlaylistEntries("ytsearch25:$q", 25, cleanName, true)
                    else emptyList()
                }
            }
            val radioJob = if (radioVid.isNotEmpty()) {
                async<List<DiscoverItem>>(Dispatchers.IO) {
                    val radioEntries = fetchInnerTubeRadio(radioVid, cleanName)?.first
                    if (radioEntries != null) {
                        radioEntries
                            .filter { it.verifiedMusic || CanonicalKey.hasArtist(it.item.artist, cleanName) || explicitMusicTitleRe.containsMatchIn(it.item.title) }
                            .map { it.item }
                    } else if (isInitialized) {
                        fetchFlatPlaylistEntries("https://www.youtube.com/watch?v=$radioVid&list=RD$radioVid", 25, cleanName, false)
                    } else {
                        emptyList()
                    }
                }
            } else null
            val list = ytmSongsJob.await().toMutableList()
            list.addAll(searchJobs.awaitAll().flatten())
            if (radioJob != null) list.addAll(radioJob.await())
            list
        }

        val seenMatcher = seenMatcherByScope.getOrPut(scopeKey) { CanonicalKey.SongMatcher() }
        val batchMatcher = CanonicalKey.SongMatcher(owned.knownNames)
        val dedupedBatch = mutableListOf<DiscoverItem>()
        for (item in fetched) {
            if (isTrackOwned(owned, item.ytId, item.title, item.artist)) continue
            if (batchMatcher.matches(item.ytId, item.title, item.artist)) continue
            batchMatcher.add(item.ytId, item.title, item.artist)
            dedupedBatch.add(item)
        }

        val unseenItems = mutableListOf<DiscoverItem>()
        val seenBeforeItems = mutableListOf<DiscoverItem>()
        synchronized(seenMatcher) {
            for (item in dedupedBatch) {
                if (seenMatcher.matches(item.ytId, item.title, item.artist)) {
                    seenBeforeItems.add(item)
                } else {
                    unseenItems.add(item)
                }
            }
        }

        fun partitionAndSelect(
            source: List<DiscoverItem>,
            targetSize: Int,
            alreadyAddedIds: MutableSet<String>,
            relatedArtistCounts: MutableMap<String, Int>
        ): List<DiscoverItem> {
            val direct = mutableListOf<DiscoverItem>()
            val related = mutableListOf<DiscoverItem>()
            for (item in source) {
                if (alreadyAddedIds.contains(item.ytId)) continue
                val isDirect = CanonicalKey.hasArtist(item.artist, cleanName) ||
                    item.title.contains(cleanName, ignoreCase = true)
                if (isDirect) direct.add(item) else related.add(item)
            }
            if (round > 0 || forceRefresh) {
                direct.shuffle()
                related.shuffle()
            }
            val out = mutableListOf<DiscoverItem>()
            for (item in direct) {
                if (out.size >= targetSize) break
                if (alreadyAddedIds.add(item.ytId)) out.add(item)
            }
            for (item in related) {
                if (out.size >= targetSize) break
                val aKey = CanonicalKey.normName(item.artist)
                val cnt = relatedArtistCounts.getOrDefault(aKey, 0)
                if (aKey.isNotEmpty() && cnt >= 2) continue
                if (alreadyAddedIds.add(item.ytId)) {
                    if (aKey.isNotEmpty()) relatedArtistCounts[aKey] = cnt + 1
                    out.add(item)
                }
            }
            return out
        }

        val chosenIds = mutableSetOf<String>()
        val relatedCounts = mutableMapOf<String, Int>()
        val result = partitionAndSelect(unseenItems, 18, chosenIds, relatedCounts).toMutableList()

        if (result.size < 8 && seenBeforeItems.isNotEmpty()) {
            synchronized(seenMatcher) { seenMatcher.clear() }
            result.addAll(partitionAndSelect(seenBeforeItems, 18 - result.size, chosenIds, relatedCounts))
        }

        recordSeenItems(scopeKey, result)
        result
    }

    /**
     * Online recommendations tailored to a Playlist's tracks and artists.
     */
    suspend fun discoverForPlaylist(
        playlistId: Long,
        playlistTracks: List<Track>,
        forceRefresh: Boolean = false
    ): List<DiscoverItem> = withContext(Dispatchers.IO) {
        if (playlistTracks.isEmpty()) {
            return@withContext discoverOnline(forceRefresh = forceRefresh)
        }

        val scopeKey = "playlist:$playlistId"
        val round = scopeSeedCounter.compute(scopeKey) { _, v -> (v ?: -1) + 1 } ?: 0

        val now = System.currentTimeMillis() / 1000
        val recent7d = store.getRecentPlayCounts(now - 7 * 86400L)
        val recent30d = store.getRecentPlayCounts(now - 30 * 86400L)
        val sortedTracks = playlistTracks.sortedByDescending {
            RecommendationEngine.scoreTrackAffinity(
                track = it,
                nowSec = now,
                recentPlays7d = recent7d[it.id] ?: 0,
                recentPlays30d = recent30d[it.id] ?: 0
            )
        }

        val seeds = mutableListOf<SeedTarget>()
        val seenSeedArtists = mutableSetOf<String>()
        val usedTrackIds = mutableSetOf<String>()
        val rotated = if (sortedTracks.size > 1) {
            val offset = round % sortedTracks.size
            sortedTracks.drop(offset) + sortedTracks.take(offset)
        } else {
            sortedTracks
        }

        for (t in rotated) {
            if (seeds.size >= 5) break
            val art = CanonicalKey.splitArtists(t.artist)
                .firstOrNull { !genericArtistKeys.contains(CanonicalKey.normName(it)) } ?: ""
            val normArt = CanonicalKey.normName(art)
            if (normArt.isNotEmpty() && !seenSeedArtists.add(normArt)) continue
            usedTrackIds.add(t.id)
            seeds.add(SeedTarget(art, t))
        }
        if (seeds.size < 5) {
            for (t in rotated) {
                if (seeds.size >= 5) break
                if (usedTrackIds.add(t.id)) {
                    val art = CanonicalKey.splitArtists(t.artist)
                        .firstOrNull { !genericArtistKeys.contains(CanonicalKey.normName(it)) } ?: ""
                    seeds.add(SeedTarget(art, t))
                }
            }
        }

        val owned = buildOwnedMatcher(extraTracks = playlistTracks)
        val searchSuffixes = listOf("Topic", "official audio", "music")

        val perSeed = coroutineScope {
            seeds.mapIndexed { idx, s ->
                async(Dispatchers.IO) {
                    val ytId = CanonicalKey.trackVideoId(s.track).takeIf { CanonicalKey.isValidYtId(it) }
                    val items = if (ytId != null) {
                        val radioEntries = fetchInnerTubeRadio(ytId, s.artist)?.first
                        val raw = if (radioEntries != null) {
                            radioEntries
                                .filter { it.verifiedMusic || (s.artist.isNotBlank() && CanonicalKey.hasArtist(it.item.artist, s.artist)) || explicitMusicTitleRe.containsMatchIn(it.item.title) }
                                .map { it.item }
                        } else if (isInitialized) {
                            fetchFlatPlaylistEntries(
                                "https://www.youtube.com/watch?v=$ytId&list=RD$ytId",
                                playlistEnd = 20,
                                fallbackArtist = s.artist,
                                allowSeedFallback = false
                            )
                        } else {
                            emptyList()
                        }
                        raw.filter { it.ytId != ytId && !isTrackOwned(owned, it.ytId, it.title, it.artist) }
                    } else {
                        emptyList()
                    }
                    if (items.size >= 3) {
                        items.take(12)
                    } else {
                        var fallback = if (s.artist.isNotBlank()) {
                            fetchYouTubeMusicSongsSearch(s.artist, 12, s.artist).map { it.item }
                        } else {
                            emptyList()
                        }
                        if (fallback.isEmpty()) {
                            val suffix = searchSuffixes[(round + idx) % searchSuffixes.size]
                            val q = if (s.artist.isNotBlank()) "${s.artist} $suffix" else s.track.displayTitle
                            fallback = fetchInnerTubeSearch(q, 12, s.artist)
                                .filter { it.verifiedMusic || (s.artist.isNotBlank() && CanonicalKey.hasArtist(it.item.artist, s.artist)) || explicitMusicTitleRe.containsMatchIn(it.item.title) }
                                .map { it.item }
                            if (fallback.isEmpty() && isInitialized) {
                                fallback = fetchFlatPlaylistEntries(
                                    "ytsearch12:$q",
                                    playlistEnd = 12,
                                    fallbackArtist = s.artist,
                                    allowSeedFallback = s.artist.isNotBlank()
                                )
                            }
                        }
                        (items + fallback.filter { !isTrackOwned(owned, it.ytId, it.title, it.artist) })
                    }
                }
            }.awaitAll().filter { it.isNotEmpty() }
        }

        interleaveAndDeduplicate(
            scopeKey = scopeKey,
            perSeed = perSeed,
            owned = owned,
            targetSize = 16,
            minBackfillSize = 8,
            allowBackfill = true,
            maxCapRelaxed = 2
        )
    }
}
