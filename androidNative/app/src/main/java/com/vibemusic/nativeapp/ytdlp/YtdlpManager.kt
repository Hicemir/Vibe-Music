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
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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

    private val seenYtIdsByScope = ConcurrentHashMap<String, MutableSet<String>>()
    private val seenTitleKeysByScope = ConcurrentHashMap<String, MutableSet<String>>()
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

    private data class OwnedIndex(
        val ytIds: Set<String>,
        val titleKeys: Set<String>
    )

    private data class SeedTarget(
        val artist: String,
        val track: Track
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
        "music theory", "beginner's guide"
    )

    private val badChannelKeywords = listOf(
        "podcast", "reacts", "reviews", "gaming", "tutorials",
        "lessons", "synthesia", "piano tutorial", "guitar tab", "karaoke"
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
        if (badTitleKeywords.any { trimmed.contains(it, ignoreCase = true) }) {
            return true
        }
        if (channel.isNotBlank() && badChannelKeywords.any { channel.contains(it, ignoreCase = true) }) {
            return true
        }
        return false
    }

    private fun buildOwnedIndex(extraTracks: List<Track> = emptyList()): OwnedIndex {
        val allTracks = store.getAllTracks(includeHidden = true) + extraTracks
        val ytIds = mutableSetOf<String>()
        val titleKeys = mutableSetOf<String>()
        for (t in allTracks) {
            val yt = t.ytId?.takeIf { it.isNotBlank() }
                ?: if (t.id.startsWith("yt:")) t.id.removePrefix("yt:") else null
            if (!yt.isNullOrBlank()) {
                ytIds.add(yt)
            }
            if (t.title.isNotBlank()) {
                titleKeys.addAll(CanonicalKey.songIdentityKeys(t.title, t.artist))
            }
            if (t.displayTitle.isNotBlank()) {
                titleKeys.addAll(CanonicalKey.songIdentityKeys(t.displayTitle, t.displayArtist))
            }
        }
        return OwnedIndex(ytIds, titleKeys)
    }

    private fun isTrackOwned(owned: OwnedIndex, ytId: String, title: String, artist: String): Boolean {
        if (owned.ytIds.contains(ytId)) return true
        val keys = CanonicalKey.songIdentityKeys(title, artist)
        return keys.any { owned.titleKeys.contains(it) }
    }

    private fun cleanEntryArtist(
        rawChannel: String,
        title: String,
        fallbackArtist: String,
        allowSeedFallback: Boolean
    ): String {
        val cleaned = CanonicalKey.cleanArtistString(rawChannel)
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

    private suspend fun fetchFlatPlaylistEntries(
        targetUrl: String,
        playlistEnd: Int = 22,
        fallbackArtist: String = "",
        allowSeedFallback: Boolean = false
    ): List<DiscoverItem> {
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
        val seenIds = seenYtIdsByScope.getOrPut(scopeKey) { ConcurrentHashMap.newKeySet() }
        val seenTitles = seenTitleKeysByScope.getOrPut(scopeKey) { ConcurrentHashMap.newKeySet() }
        for (item in items) {
            seenIds.add(item.ytId)
            seenTitles.addAll(CanonicalKey.songIdentityKeys(item.title, item.artist))
        }
    }

    private fun interleaveAndDeduplicate(
        scopeKey: String,
        perSeed: List<List<DiscoverItem>>,
        owned: OwnedIndex,
        targetSize: Int,
        minBackfillSize: Int,
        allowBackfill: Boolean,
        maxCapRelaxed: Int
    ): List<DiscoverItem> {
        val seenIds = seenYtIdsByScope.getOrPut(scopeKey) { ConcurrentHashMap.newKeySet() }
        val seenTitles = seenTitleKeysByScope.getOrPut(scopeKey) { ConcurrentHashMap.newKeySet() }

        val out = mutableListOf<DiscoverItem>()
        val deferredArtistCap = mutableListOf<DiscoverItem>()
        val previouslySeen = mutableListOf<DiscoverItem>()
        val batchIds = mutableSetOf<String>()
        val batchTitleKeys = mutableSetOf<String>()
        val artistCounts = mutableMapOf<String, Int>()

        fun tryAdd(item: DiscoverItem, maxPerArtist: Int): Boolean {
            val idKeys = CanonicalKey.songIdentityKeys(item.title, item.artist)
            if (batchIds.contains(item.ytId) || idKeys.any { batchTitleKeys.contains(it) }) return false
            val aKey = CanonicalKey.norm(item.artist)
            val cnt = artistCounts.getOrDefault(aKey, 0)
            if (aKey.isNotEmpty() && cnt >= maxPerArtist) return false
            batchIds.add(item.ytId)
            batchTitleKeys.addAll(idKeys)
            if (aKey.isNotEmpty()) artistCounts[aKey] = cnt + 1
            out.add(item)
            return true
        }

        val maxLen = perSeed.maxOfOrNull { it.size } ?: 0
        for (rowIdx in 0 until maxLen) {
            if (out.size >= targetSize) break
            for (seedList in perSeed) {
                if (out.size >= targetSize) break
                if (rowIdx >= seedList.size) continue
                val item = seedList[rowIdx]
                if (isTrackOwned(owned, item.ytId, item.title, item.artist)) continue

                val idKeys = CanonicalKey.songIdentityKeys(item.title, item.artist)
                if (batchIds.contains(item.ytId) || idKeys.any { batchTitleKeys.contains(it) }) continue

                if (seenIds.contains(item.ytId) || idKeys.any { seenTitles.contains(it) }) {
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
            seenIds.clear()
            seenTitles.clear()
            val caps = if (maxCapRelaxed > 2) listOf(2, maxCapRelaxed) else listOf(2)
            for (cap in caps) {
                for (item in previouslySeen) {
                    if (out.size >= targetSize) break
                    tryAdd(item, cap)
                }
            }
        }

        recordSeenItems(scopeKey, out)
        return out
    }

    /**
     * Personalized Home Online Discovery:
     * - Derives all seeds strictly from the user's library, listening time, play history, and likes
     * - Queries YouTube Radio Mixes (`list=RD<vid>`) and rotated artist searches across up to 8 seeds
     * - Strictly excludes owned library tracks and canonical title duplicates
     * - Caps tracks per artist (max 2) so recommendations span many artists rather than 4
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
        if (!ensureInitialized()) return@withContext emptyList()

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

        val rankedArtists = RecommendationEngine.rankLibraryArtistsByAffinity(allTracks, recent7d, recent30d, plMap)
        val round = scopeSeedCounter.compute("home") { _, v -> (v ?: -1) + 1 } ?: 0
        val excludeNorms = excludeArtists.map { CanonicalKey.norm(it) }.filter { it.isNotEmpty() }.toSet()

        val chosenArtists = mutableListOf<RecommendationEngine.ArtistAffinity>()
        val unvisited = rankedArtists.filter {
            !excludeNorms.contains(it.normArtist) && !shownHomeArtists.contains(it.normArtist)
        }
        chosenArtists.addAll(weightedSelectArtists(unvisited, 4))

        if (chosenArtists.size < 4) {
            val pickedNorms = chosenArtists.map { it.normArtist }.toMutableSet()
            val notExcluded = rankedArtists.filter { !pickedNorms.contains(it.normArtist) && !excludeNorms.contains(it.normArtist) }
            if (notExcluded.isNotEmpty()) {
                val extra = weightedSelectArtists(notExcluded, 4 - chosenArtists.size)
                chosenArtists.addAll(extra)
                pickedNorms.addAll(extra.map { it.normArtist })
            }
            if (chosenArtists.size < 4) {
                shownHomeArtists.clear()
                val remaining = rankedArtists.filter { !pickedNorms.contains(it.normArtist) }
                chosenArtists.addAll(weightedSelectArtists(remaining, 4 - chosenArtists.size))
            }
        }
        for (art in chosenArtists) {
            shownHomeArtists.add(art.normArtist)
        }

        val selectedSeeds = mutableListOf<SeedTarget>()
        val usedTrackIds = mutableSetOf<String>()
        for ((idx, aff) in chosenArtists.withIndex()) {
            val ytTracks = aff.topTracks.filter { !it.ytId.isNullOrBlank() && !usedTrackIds.contains(it.id) }
            val pool = ytTracks.ifEmpty { aff.topTracks.filter { !usedTrackIds.contains(it.id) } }.ifEmpty { aff.topTracks }
            if (pool.isNotEmpty()) {
                val pick = pool[(round + idx) % pool.size]
                usedTrackIds.add(pick.id)
                selectedSeeds.add(SeedTarget(aff.artist, pick))
            }
        }

        if (selectedSeeds.size < 4) {
            val scoredTracks = allTracks.filter { !usedTrackIds.contains(it.id) }
                .sortedByDescending {
                    val base = RecommendationEngine.scoreTrackAffinity(
                        track = it,
                        nowSec = now,
                        recentPlays7d = recent7d[it.id] ?: 0,
                        recentPlays30d = recent30d[it.id] ?: 0,
                        playlistCount = playlistCounts[it.id] ?: 0
                    )
                    base + if (!it.ytId.isNullOrBlank()) 25.0 else 0.0
                }
            for (t in scoredTracks) {
                if (selectedSeeds.size >= 4) break
                usedTrackIds.add(t.id)
                val primaryArtist = CanonicalKey.splitArtists(t.artist)
                    .firstOrNull { !it.equals("Unknown Artist", ignoreCase = true) } ?: ""
                selectedSeeds.add(SeedTarget(primaryArtist, t))
            }
        }

        if (selectedSeeds.isEmpty()) return@withContext emptyList()

        val owned = buildOwnedIndex()
        val searchSuffixes = listOf("Topic", "official audio", "music", "mix")

        val perSeedResults: List<List<DiscoverItem>> = coroutineScope {
            val mixDeferreds = selectedSeeds.mapIndexed { idx, s ->
                async(Dispatchers.IO) {
                    val ytId = s.track.ytId?.takeIf { CanonicalKey.isValidYtId(it) }
                    val items = if (ytId != null) {
                        val mixUrl = "https://www.youtube.com/watch?v=$ytId&list=RD$ytId"
                        fetchFlatPlaylistEntries(mixUrl, playlistEnd = 22, fallbackArtist = s.artist, allowSeedFallback = false)
                            .filter { !isTrackOwned(owned, it.ytId, it.title, it.artist) }
                    } else {
                        emptyList()
                    }
                    if (items.size >= 3) {
                        items.shuffled()
                    } else {
                        val suffix = searchSuffixes[(round + idx) % searchSuffixes.size]
                        val queryTerm = if (s.artist.isNotBlank()) "${s.artist} $suffix" else s.track.displayTitle
                        val fallbackItems = fetchFlatPlaylistEntries(
                            "ytsearch12:$queryTerm",
                            playlistEnd = 12,
                            fallbackArtist = s.artist,
                            allowSeedFallback = s.artist.isNotBlank()
                        ).filter { !isTrackOwned(owned, it.ytId, it.title, it.artist) }
                        (items + fallbackItems).shuffled()
                    }
                }
            }

            // Also run up to 2 rotated direct catalog queries for top library artists
            val directArtists = chosenArtists.take(2)
            val directDeferreds = directArtists.mapIndexed { idx, aff ->
                async(Dispatchers.IO) {
                    val suffix = searchSuffixes[(round + idx + 1) % searchSuffixes.size]
                    fetchFlatPlaylistEntries(
                        "ytsearch12:${aff.artist} $suffix",
                        playlistEnd = 12,
                        fallbackArtist = aff.artist,
                        allowSeedFallback = true
                    ).filter { !isTrackOwned(owned, it.ytId, it.title, it.artist) }.shuffled()
                }
            }

            (mixDeferreds + directDeferreds).awaitAll().filter { it.isNotEmpty() }
        }

        interleaveAndDeduplicate(
            scopeKey = "home",
            perSeed = perSeedResults,
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
        if (cleanName.isEmpty() || !ensureInitialized()) return@withContext emptyList()

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

        val owned = buildOwnedIndex(extraTracks = artistTracks)
        val searchPool = listOf(
            "ytsearch25:$cleanName Topic",
            "ytsearch25:$cleanName official audio",
            "ytsearch25:$cleanName music",
            "ytsearch25:$cleanName songs",
            "ytsearch25:$cleanName album",
            "ytsearch25:$cleanName ost"
        )

        val artistTracksWithYt = artistTracks.filter { !it.ytId.isNullOrBlank() && CanonicalKey.isValidYtId(it.ytId) }
        val sources = mutableListOf<Pair<String, Boolean>>()
        sources.add(searchPool[round % searchPool.size] to true)
        sources.add(searchPool[(round + 1) % searchPool.size] to true)
        if (artistTracksWithYt.isNotEmpty()) {
            val vid = artistTracksWithYt[round % artistTracksWithYt.size].ytId!!
            sources.add("https://www.youtube.com/watch?v=$vid&list=RD$vid" to false)
        } else {
            sources.add(searchPool[(round + 2) % searchPool.size] to true)
        }

        val fetched = coroutineScope {
            sources.map { (target, allowFallback) ->
                async(Dispatchers.IO) {
                    fetchFlatPlaylistEntries(
                        targetUrl = target,
                        playlistEnd = 25,
                        fallbackArtist = cleanName,
                        allowSeedFallback = allowFallback
                    )
                }
            }.awaitAll().flatten()
        }

        val seenIds = seenYtIdsByScope.getOrPut(scopeKey) { ConcurrentHashMap.newKeySet() }
        val seenTitles = seenTitleKeysByScope.getOrPut(scopeKey) { ConcurrentHashMap.newKeySet() }

        val dedupedBatch = mutableListOf<DiscoverItem>()
        val batchIds = mutableSetOf<String>()
        val batchTitleKeys = mutableSetOf<String>()
        for (item in fetched) {
            if (isTrackOwned(owned, item.ytId, item.title, item.artist)) continue
            val keys = CanonicalKey.songIdentityKeys(item.title, item.artist)
            if (batchIds.contains(item.ytId) || keys.any { batchTitleKeys.contains(it) }) continue
            batchIds.add(item.ytId)
            batchTitleKeys.addAll(keys)
            dedupedBatch.add(item)
        }

        val unseenItems = mutableListOf<DiscoverItem>()
        val seenBeforeItems = mutableListOf<DiscoverItem>()
        for (item in dedupedBatch) {
            val keys = CanonicalKey.songIdentityKeys(item.title, item.artist)
            if (seenIds.contains(item.ytId) || keys.any { seenTitles.contains(it) }) {
                seenBeforeItems.add(item)
            } else {
                unseenItems.add(item)
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
                if (isDirect) {
                    direct.add(item)
                } else {
                    related.add(item)
                }
            }
            if (round > 0 || forceRefresh) {
                direct.shuffle()
                related.shuffle()
            }
            val out = mutableListOf<DiscoverItem>()
            for (item in direct) {
                if (out.size >= targetSize) break
                if (alreadyAddedIds.add(item.ytId)) {
                    out.add(item)
                }
            }
            for (item in related) {
                if (out.size >= targetSize) break
                val aKey = CanonicalKey.norm(item.artist)
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
            seenIds.clear()
            seenTitles.clear()
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
        if (!ensureInitialized()) return@withContext emptyList()

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
                .firstOrNull { !it.equals("Unknown Artist", ignoreCase = true) } ?: ""
            val normArt = CanonicalKey.norm(art)
            if (normArt.isNotEmpty() && !seenSeedArtists.add(normArt)) continue
            usedTrackIds.add(t.id)
            seeds.add(SeedTarget(art, t))
        }
        if (seeds.size < 5) {
            for (t in rotated) {
                if (seeds.size >= 5) break
                if (usedTrackIds.add(t.id)) {
                    val art = CanonicalKey.splitArtists(t.artist)
                        .firstOrNull { !it.equals("Unknown Artist", ignoreCase = true) } ?: ""
                    seeds.add(SeedTarget(art, t))
                }
            }
        }

        val owned = buildOwnedIndex(extraTracks = playlistTracks)
        val searchSuffixes = listOf("Topic", "official audio", "music")

        val perSeed = coroutineScope {
            seeds.mapIndexed { idx, s ->
                async(Dispatchers.IO) {
                    val ytId = s.track.ytId?.takeIf { CanonicalKey.isValidYtId(it) }
                    val items = if (ytId != null) {
                        fetchFlatPlaylistEntries(
                            "https://www.youtube.com/watch?v=$ytId&list=RD$ytId",
                            playlistEnd = 20,
                            fallbackArtist = s.artist,
                            allowSeedFallback = false
                        ).filter { !isTrackOwned(owned, it.ytId, it.title, it.artist) }
                    } else {
                        emptyList()
                    }
                    if (items.size >= 3) {
                        items.shuffled()
                    } else {
                        val suffix = searchSuffixes[(round + idx) % searchSuffixes.size]
                        val q = if (s.artist.isNotBlank()) "${s.artist} $suffix" else s.track.displayTitle
                        val fallback = fetchFlatPlaylistEntries(
                            "ytsearch12:$q",
                            playlistEnd = 12,
                            fallbackArtist = s.artist,
                            allowSeedFallback = s.artist.isNotBlank()
                        ).filter { !isTrackOwned(owned, it.ytId, it.title, it.artist) }
                        (items + fallback).shuffled()
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
