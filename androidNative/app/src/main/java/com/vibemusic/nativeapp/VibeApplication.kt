package com.vibemusic.nativeapp

import android.app.Application
import com.vibemusic.nativeapp.data.VibeStore
import com.vibemusic.nativeapp.player.PlayerManager
import com.vibemusic.nativeapp.scanner.LibraryScanner
import com.vibemusic.nativeapp.ytdlp.YtdlpManager

class VibeApplication : Application() {

    lateinit var store: VibeStore
        private set

    lateinit var scanner: LibraryScanner
        private set

    lateinit var playerManager: PlayerManager
        private set

    lateinit var ytdlpManager: YtdlpManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        store = VibeStore(this)
        scanner = LibraryScanner(this, store)
        playerManager = PlayerManager(this, store)
        ytdlpManager = YtdlpManager(this, store, scanner)

        // Initialize yt-dlp in background
        ytdlpManager.init()
    }

    override fun onTerminate() {
        playerManager.release()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: VibeApplication
            private set
    }
}
