package com.vibemusic.nativeapp.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.vibemusic.nativeapp.data.Track
import com.vibemusic.nativeapp.data.VibeStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.random.Random

class PlayerManager(
    private val context: Context,
    private val store: VibeStore
) {
    val biquadEq = BiquadEqAudioProcessor()

    val exoPlayer: ExoPlayer

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    // State flows
    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow("off") // "off" | "all" | "one"
    val repeatMode: StateFlow<String> = _repeatMode.asStateFlow()

    private val _sleepTimerRemainingSec = MutableStateFlow(0)
    val sleepTimerRemainingSec: StateFlow<Int> = _sleepTimerRemainingSec.asStateFlow()

    private val _sleepTimerRemainingTracks = MutableStateFlow(0)
    val sleepTimerRemainingTracks: StateFlow<Int> = _sleepTimerRemainingTracks.asStateFlow()

    private val _activePlaylistId = MutableStateFlow(0L)
    val activePlaylistId: StateFlow<Long> = _activePlaylistId.asStateFlow()

    private val _activeCollectionKey = MutableStateFlow("")
    val activeCollectionKey: StateFlow<String> = _activeCollectionKey.asStateFlow()

    // Internal state
    private val origQueue = mutableListOf<Track>()
    private var currentPlaylistId: Long = 0L
    private var instantPreviewTrack: Track? = null
    private var playCountRecorded = false
    private var sleepTimerJob: Job? = null

    init {
        // Setup ExoPlayer with custom AudioProcessor
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf<AudioProcessor>(biquadEq))
                    .build()
            }
        }

        exoPlayer = ExoPlayer.Builder(context, renderersFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (!isPlaying) {
                    saveState()
                }
                notifyService()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    _durationMs.value = exoPlayer.duration.coerceAtLeast(0L)
                } else if (playbackState == Player.STATE_ENDED) {
                    handleTrackEnded()
                }
                notifyService()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.e("PlayerManager", "Playback error: ${error.errorCodeName}", error)
            }
        })

        // Position ticking loop
        scope.launch {
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                    _positionMs.value = pos
                    val dur = exoPlayer.duration.coerceAtLeast(0L)
                    _durationMs.value = dur

                    // Check play count record (30s or 50% played)
                    if (!playCountRecorded && _currentTrack.value != null && dur > 0) {
                        val track = _currentTrack.value!!
                        if (pos >= 30_000L || (pos >= dur / 2 && dur >= 10_000L)) {
                            playCountRecorded = true
                            launch(Dispatchers.IO) {
                                store.recordPlay(track.id, currentPlaylistId)
                            }
                        }
                    }
                }
                delay(300)
            }
        }

        // Load saved settings
        val settings = store.loadSettings()
        _isShuffle.value = settings.shuffle
        _repeatMode.value = settings.repeat
        biquadEq.setEnabled(settings.eqEnabled)
        biquadEq.setPreampDb(settings.eqPreamp)
        biquadEq.setAllBands(settings.eqBands)

        // Restore saved queue state
        val saved = store.loadQueueState()
        if (saved.trackIds.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                val all = store.getAllTracks().associateBy { it.id }
                val restored = saved.trackIds.mapNotNull { all[it] }
                if (restored.isNotEmpty()) {
                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                        _queue.value = restored
                        val actualIdx = saved.index.coerceIn(0, restored.lastIndex)
                        _currentIndex.value = actualIdx
                        val track = restored[actualIdx]
                        _currentTrack.value = track

                        // Prepare ExoPlayer with restored track so it is ready on launch
                        val path = track.resolvedPath.ifEmpty {
                            store.resolvePath(track.rootKey, track.relPath)
                        }
                        val file = File(path)
                        if (file.exists()) {
                            val coverFile = store.getCoverFile(track.id)
                            val coverUri = Uri.fromFile(coverFile)
                            val metaBuilder = MediaMetadata.Builder()
                                .setTitle(track.displayTitle)
                                .setArtist(track.displayArtist)
                                .setAlbumTitle(track.album)
                                .setArtworkUri(coverUri)
                            if (coverFile.exists() && coverFile.length() > 0) {
                                try {
                                    metaBuilder.setArtworkData(coverFile.readBytes(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                } catch (_: Exception) {}
                            }
                            val mediaItem = MediaItem.Builder()
                                .setUri(Uri.fromFile(file))
                                .setMediaId(track.id)
                                .setMediaMetadata(metaBuilder.build())
                                .build()
                            exoPlayer.setMediaItem(mediaItem)
                            exoPlayer.prepare()
                            if (saved.positionMs > 0L) {
                                exoPlayer.seekTo(saved.positionMs)
                                _positionMs.value = saved.positionMs
                            }
                            val durMs = (track.duration * 1000).toLong()
                            if (durMs > 0L) {
                                _durationMs.value = durMs
                            }
                        }
                    }
                }
            }
        }
    }

    fun saveState() {
        val ids = _queue.value.map { it.id }
        val idx = _currentIndex.value
        val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
        scope.launch(Dispatchers.IO) {
            store.saveQueueState(ids, idx, pos)
        }
    }

    // -------------------------------------------------------------
    // Playback Controls
    // -------------------------------------------------------------

    fun playTracks(
        tracks: List<Track>,
        startIndex: Int = 0,
        shuffle: Boolean = false,
        playlistId: Long = 0L,
        collectionKey: String = ""
    ) {
        if (tracks.isEmpty()) return
        instantPreviewTrack = null
        currentPlaylistId = playlistId
        _activePlaylistId.value = playlistId
        _activeCollectionKey.value = when {
            collectionKey.isNotEmpty() -> collectionKey
            playlistId != 0L -> "playlist:$playlistId"
            else -> ""
        }
        if (playlistId > 0L) {
            scope.launch(Dispatchers.IO) {
                store.touchPlaylist(playlistId)
            }
        }
        origQueue.clear()
        origQueue.addAll(tracks)

        _isShuffle.value = shuffle
        val workingQueue = if (shuffle) {
            val startTrack = tracks.getOrNull(startIndex) ?: tracks[0]
            val rest = tracks.filter { it.id != startTrack.id }.shuffled(Random.Default)
            mutableListOf(startTrack).apply { addAll(rest) }
        } else {
            tracks.toMutableList()
        }

        _queue.value = workingQueue
        val actualStart = if (shuffle) 0 else startIndex.coerceIn(0, workingQueue.lastIndex)
        playQueueIndex(actualStart)
    }

    fun playInstant(track: Track) {
        instantPreviewTrack = track
        currentPlaylistId = 0L
        _activePlaylistId.value = 0L
        _activeCollectionKey.value = ""
        playCountRecorded = false
        _currentTrack.value = track
        loadAndPlay(track)
    }

    fun playQueueIndex(index: Int) {
        val q = _queue.value
        if (index !in q.indices) return
        instantPreviewTrack = null
        _currentIndex.value = index
        val track = q[index]
        _currentTrack.value = track
        playCountRecorded = false
        saveState()
        loadAndPlay(track)
    }

    private fun loadAndPlay(track: Track) {
        val path = track.resolvedPath.ifEmpty {
            store.resolvePath(track.rootKey, track.relPath)
        }
        val file = File(path)
        if (!file.exists()) {
            android.util.Log.e("PlayerManager", "Audio file not found: $path")
            return
        }

        val coverFile = store.getCoverFile(track.id)
        val coverUri = Uri.fromFile(coverFile)
        val metaBuilder = MediaMetadata.Builder()
            .setTitle(track.displayTitle)
            .setArtist(track.displayArtist)
            .setAlbumTitle(track.album)
            .setArtworkUri(coverUri)

        // Pass raw artwork data so Samsung One UI Lockscreen and Notification shade display full cover art!
        if (coverFile.exists() && coverFile.length() > 0) {
            try {
                val bytes = coverFile.readBytes()
                metaBuilder.setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            } catch (_: Exception) {}
        }
        val metadata = metaBuilder.build()

        val mediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(track.id)
            .setMediaMetadata(metadata)
            .build()

        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
        _isPlaying.value = true
        notifyService()
    }

    fun reseedUpcoming(newMix: List<Track>) {
        val cur = _currentTrack.value
        val curIdx = _currentIndex.value
        if (cur == null || curIdx < 0) {
            playTracks(newMix, startIndex = 0, shuffle = false)
            return
        }
        currentPlaylistId = 0L
        _activePlaylistId.value = 0L
        _activeCollectionKey.value = ""
        val currentAndPast = _queue.value.take(curIdx + 1)
        val filteredNewMix = newMix.filter { it.id != cur.id }
        val updatedQueue = currentAndPast + filteredNewMix
        _queue.value = updatedQueue
        saveState()
    }

    fun clearQueue() {
        val cur = _currentTrack.value
        if (cur != null) {
            _queue.value = listOf(cur)
            _currentIndex.value = 0
        } else {
            _queue.value = emptyList()
            _currentIndex.value = -1
        }
        saveState()
    }

    fun pause() {
        exoPlayer.pause()
        _isPlaying.value = false
        notifyService()
    }

    fun resume() {
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekTo(0)
        }
        exoPlayer.play()
        _isPlaying.value = true
        notifyService()
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            resume()
        }
    }

    fun seekTo(positionMs: Long) {
        val dur = exoPlayer.duration.takeIf { it > 0 } ?: _durationMs.value
        val clamped = if (dur > 0) positionMs.coerceIn(0L, dur) else positionMs.coerceAtLeast(0L)
        exoPlayer.seekTo(clamped)
        _positionMs.value = clamped
    }

    fun seekBy(deltaMs: Long) {
        val current = exoPlayer.currentPosition.coerceAtLeast(0L)
        seekTo(current + deltaMs)
    }

    fun playNext() {
        if (instantPreviewTrack != null) {
            instantPreviewTrack = null
            if (_queue.value.isNotEmpty()) {
                playQueueIndex((_currentIndex.value).coerceAtLeast(0))
            }
            return
        }

        val q = _queue.value
        if (q.isEmpty()) return

        val nextIdx = _currentIndex.value + 1
        if (nextIdx < q.size) {
            playQueueIndex(nextIdx)
        } else if (_repeatMode.value == "all") {
            playQueueIndex(0)
        }
    }

    fun playPrevious(forcePreviousTrack: Boolean = false) {
        if (!forcePreviousTrack && exoPlayer.currentPosition > 3000L) {
            seekTo(0L)
            return
        }
        val prevIdx = _currentIndex.value - 1
        if (prevIdx >= 0) {
            playQueueIndex(prevIdx)
        } else if (_repeatMode.value == "all" && _queue.value.isNotEmpty()) {
            playQueueIndex(_queue.value.lastIndex)
        } else {
            seekTo(0L)
        }
    }

    fun peekNextTrack(): Track? {
        val q = _queue.value
        if (q.isEmpty()) return null
        val nextIdx = _currentIndex.value + 1
        return when {
            nextIdx in q.indices -> q[nextIdx]
            _repeatMode.value == "all" -> q.firstOrNull()
            else -> null
        }
    }

    fun peekPreviousTrack(): Track? {
        val q = _queue.value
        if (q.isEmpty()) return null
        val prevIdx = _currentIndex.value - 1
        return when {
            prevIdx in q.indices -> q[prevIdx]
            _repeatMode.value == "all" -> q.lastOrNull()
            else -> _currentTrack.value
        }
    }

    private fun handleTrackEnded() {
        if (_sleepTimerRemainingTracks.value > 0) {
            val left = _sleepTimerRemainingTracks.value - 1
            _sleepTimerRemainingTracks.value = left
            if (left <= 0) {
                pause()
                _isPlaying.value = false
                return
            }
        }
        when (_repeatMode.value) {
            "one" -> {
                seekTo(0L)
                exoPlayer.play()
            }
            "all" -> {
                playNext()
            }
            else -> {
                val nextIdx = _currentIndex.value + 1
                if (nextIdx < _queue.value.size) {
                    playQueueIndex(nextIdx)
                } else {
                    _isPlaying.value = false
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Queue Modification
    // -------------------------------------------------------------

    fun addToQueue(track: Track) {
        val q = _queue.value.toMutableList()
        q.add(track)
        _queue.value = q
        origQueue.add(track)
        saveState()
        if (_currentIndex.value == -1) {
            playQueueIndex(0)
        }
    }

    fun addToQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val q = _queue.value.toMutableList()
        q.addAll(tracks)
        _queue.value = q
        origQueue.addAll(tracks)
        saveState()
        if (_currentIndex.value == -1) {
            playQueueIndex(0)
        }
    }

    fun playNextInQueue(track: Track) {
        val q = _queue.value.toMutableList()
        val insertAt = (_currentIndex.value + 1).coerceAtMost(q.size)
        q.add(insertAt, track)
        _queue.value = q
        origQueue.add(track)
        saveState()
        if (_currentIndex.value == -1) {
            playQueueIndex(0)
        }
    }

    fun playNextInQueue(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val q = _queue.value.toMutableList()
        val insertAt = (_currentIndex.value + 1).coerceAtMost(q.size)
        q.addAll(insertAt, tracks)
        _queue.value = q
        origQueue.addAll(tracks)
        saveState()
        if (_currentIndex.value == -1) {
            playQueueIndex(0)
        }
    }

    fun removeFromQueue(index: Int) {
        val q = _queue.value.toMutableList()
        if (index !in q.indices) return
        val removed = q.removeAt(index)
        origQueue.remove(removed)
        val cur = _currentIndex.value
        if (index < cur) {
            _currentIndex.value = cur - 1
        } else if (index == cur) {
            if (q.isNotEmpty()) {
                val newIdx = index.coerceAtMost(q.lastIndex)
                playQueueIndex(newIdx)
            } else {
                exoPlayer.stop()
                _currentIndex.value = -1
                _currentTrack.value = null
                currentPlaylistId = 0L
                _activePlaylistId.value = 0L
                _activeCollectionKey.value = ""
            }
        }
        _queue.value = q
    }

    fun moveInQueue(from: Int, to: Int) {
        val q = _queue.value.toMutableList()
        if (from !in q.indices || to !in q.indices || from == to) return
        val item = q.removeAt(from)
        q.add(to, item)
        val cur = _currentIndex.value
        if (cur == from) {
            _currentIndex.value = to
        } else if (from < cur && to >= cur) {
            _currentIndex.value = cur - 1
        } else if (from > cur && to <= cur) {
            _currentIndex.value = cur + 1
        }
        _queue.value = q
    }

    // -------------------------------------------------------------
    // Shuffle & Repeat
    // -------------------------------------------------------------

    fun setShuffle(enabled: Boolean) {
        _isShuffle.value = enabled
        val cur = _currentTrack.value
        if (enabled) {
            val q = _queue.value.toMutableList()
            if (cur != null) {
                val rest = q.filter { it.id != cur.id }.shuffled(Random.Default)
                val newQ = mutableListOf(cur).apply { addAll(rest) }
                _queue.value = newQ
                _currentIndex.value = 0
            } else {
                _queue.value = q.shuffled(Random.Default)
            }
        } else {
            // Restore original queue order
            if (origQueue.isNotEmpty()) {
                _queue.value = origQueue.toList()
                if (cur != null) {
                    val idx = origQueue.indexOfFirst { it.id == cur.id }
                    if (idx >= 0) _currentIndex.value = idx
                }
            }
        }
        val s = store.loadSettings().copy(shuffle = enabled)
        store.saveSettings(s)
    }

    fun setRepeat(mode: String) {
        val m = when (mode) {
            "all", "one" -> mode
            else -> "off"
        }
        _repeatMode.value = m
        val s = store.loadSettings().copy(repeat = m)
        store.saveSettings(s)
    }

    fun toggleRepeat() {
        val next = when (_repeatMode.value) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        setRepeat(next)
    }

    // -------------------------------------------------------------
    // Sleep Timer
    // -------------------------------------------------------------

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingTracks.value = 0
        if (minutes <= 0) {
            _sleepTimerRemainingSec.value = 0
            return
        }
        var remaining = minutes * 60
        _sleepTimerRemainingSec.value = remaining
        sleepTimerJob = scope.launch {
            while (remaining > 0 && isActive) {
                delay(1000)
                remaining--
                _sleepTimerRemainingSec.value = remaining
            }
            if (remaining <= 0) {
                pause()
                _sleepTimerRemainingSec.value = 0
            }
        }
    }

    fun setSleepTimerTracks(tracks: Int) {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingSec.value = 0
        _sleepTimerRemainingTracks.value = tracks.coerceAtLeast(0)
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemainingSec.value = 0
        _sleepTimerRemainingTracks.value = 0
    }

    fun notifyService() {
        if (_currentTrack.value == null) return
        try {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_UPDATE
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {}
    }

    fun release() {
        sleepTimerJob?.cancel()
        exoPlayer.release()
    }
}
