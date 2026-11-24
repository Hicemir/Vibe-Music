package com.vibemusic.nativeapp

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vibemusic.nativeapp.data.AiGenreSettings
import com.vibemusic.nativeapp.data.CanonicalKey
import com.vibemusic.nativeapp.data.Playlist
import com.vibemusic.nativeapp.data.RecommendationEngine
import com.vibemusic.nativeapp.data.Track
import com.vibemusic.nativeapp.data.YtCollectionType
import com.vibemusic.nativeapp.genre.AiGenreClient
import com.vibemusic.nativeapp.genre.GenreStatus
import com.vibemusic.nativeapp.genre.GenreTaxonomy
import com.vibemusic.nativeapp.player.BiquadEqAudioProcessor
import com.vibemusic.nativeapp.player.PlaybackService
import com.vibemusic.nativeapp.sync.SyncPairPrompt
import com.vibemusic.nativeapp.sync.SyncStatus
import com.vibemusic.nativeapp.ui.BottomActionSheet
import com.vibemusic.nativeapp.ui.CoverLoader
import com.vibemusic.nativeapp.ui.DownloadJobAdapter
import com.vibemusic.nativeapp.ui.LyricsAdapter
import com.vibemusic.nativeapp.ui.OnlineDiscoverAdapter
import com.vibemusic.nativeapp.ui.PlayerSheetController
import com.vibemusic.nativeapp.ui.PlaylistAdapter
import com.vibemusic.nativeapp.ui.PullToRefreshLayout
import com.vibemusic.nativeapp.ui.QuickPicksAdapter
import com.vibemusic.nativeapp.ui.SectionReorderAdapter
import com.vibemusic.nativeapp.ui.TrackAdapter
import com.vibemusic.nativeapp.ui.WrapLayout
import com.vibemusic.nativeapp.ui.YtMusicCollectionAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val app by lazy { application as VibeApplication }
    private val store by lazy { app.store }
    private val playerManager by lazy { app.playerManager }
    private val scanner by lazy { app.scanner }
    private val ytdlpManager by lazy { app.ytdlpManager }
    private val genreDetector by lazy { app.genreDetector }
    private val syncManager by lazy { app.syncManager }

    // Root views
    private lateinit var contentFrame: FrameLayout
    private lateinit var tabsContainer: FrameLayout
    private lateinit var overlayContainer: FrameLayout
    private lateinit var miniPlayerContainer: FrameLayout
    private lateinit var nowPlayingContainer: FrameLayout

    // Persistent tab views and refresh callbacks
    private val tabViews = mutableMapOf<String, View>()
    private var refreshSearchData: (() -> Unit)? = null
    private var refreshLibraryData: (() -> Unit)? = null
    private var refreshDownloadsData: (() -> Unit)? = null
    private var refreshSettingsData: (() -> Unit)? = null
    private var refreshGenreSettingsData: ((GenreStatus) -> Unit)? = null
    private var genreSummaryTextView: TextView? = null
    private var genreModeTextView: TextView? = null
    private var syncSummaryTextView: TextView? = null
    private var libQuickSyncBtn: View? = null
    private var syncStatusCollectJob: Job? = null
    private var syncPeersCollectJob: Job? = null
    private var activePairDialog: AlertDialog? = null

    // Mini Player views
    private lateinit var miniCover: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniPlayPause: ImageView

    // Now Playing views
    private lateinit var npRoot: View
    private lateinit var npCover: ImageView
    private lateinit var npTitle: TextView
    private lateinit var npArtist: TextView
    private lateinit var npLike: ImageView
    private lateinit var npSeekbar: SeekBar
    private lateinit var npTimeCurrent: TextView
    private lateinit var npTimeTotal: TextView
    private lateinit var npShuffle: ImageView
    private lateinit var npPrev: ImageView
    private lateinit var npPlayPause: ImageView
    private lateinit var npNext: ImageView
    private lateinit var npRepeat: ImageView
    private lateinit var npQueueSlider: LinearLayout
    private lateinit var npLyricsSlider: LinearLayout
    private lateinit var npQueueRecycler: RecyclerView
    private lateinit var npLyricsRecycler: RecyclerView
    private lateinit var npLyricsEmpty: TextView
    private lateinit var lyricsAdapter: LyricsAdapter
    private lateinit var queueAdapter: TrackAdapter
    private lateinit var playerSheetController: PlayerSheetController

    private var isCollectionDetailOpen = false
    private var isHiddenSongsOpen = false
    private var isGenreSettingsOpen = false
    private var isSyncScreenOpen = false
    private var currentRadioMode = "all"
    private var currentTab = "home"
    private var isUserTrackingSeekbar = false
    private var homeScrollY = 0
    private var homeDiscoverAdapter: OnlineDiscoverAdapter? = null
    private var homePlaylistAdapter: PlaylistAdapter? = null
    private var libraryPlaylistAdapter: PlaylistAdapter? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            triggerLibraryScan()
        }
    }

    private var onCustomPathSelected: ((String) -> Unit)? = null

    private val customFolderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleCustomPathPickerResult(result)
    }

    private val customFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleCustomPathPickerResult(result)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge to edge support
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_layout)) { v, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, sysBars.top, 0, 0)
            insets
        }

        // Start playback background service
        val serviceIntent = Intent(this, PlaybackService::class.java)
        startService(serviceIntent)

        contentFrame = findViewById(R.id.content_frame)
        tabsContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        overlayContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }
        contentFrame.addView(tabsContainer)
        contentFrame.addView(overlayContainer)

        miniPlayerContainer = findViewById(R.id.mini_player_container)
        nowPlayingContainer = findViewById(R.id.now_playing_container)

        setupMiniPlayer()
        setupNowPlaying()
        setupBottomNav()

        playerSheetController = PlayerSheetController(
            context = this,
            rootLayout = findViewById(R.id.root_layout),
            bottomDock = findViewById(R.id.bottom_dock),
            bottomNavBar = findViewById(R.id.bottom_nav_bar),
            miniPlayerContainer = miniPlayerContainer,
            miniPlayerRoot = findViewById(R.id.mini_player_root),
            miniPlayPause = miniPlayPause,
            nowPlayingContainer = nowPlayingContainer,
            onRequestLyricsOpen = {
                openLyricsSlider()
                true
            },
            onMiniSwipeNext = {
                playerManager.playNext()
            },
            onMiniSwipePrev = {
                playerManager.playPrevious(forcePreviousTrack = true)
            },
            onPeekAdjacentTrack = { dir ->
                val adj = if (dir > 0) playerManager.peekNextTrack() else playerManager.peekPreviousTrack()
                if (adj != null) {
                    val bmp = CoverLoader.getCachedOrDecodeSync(store, adj.id, 192)
                    Triple(adj.displayTitle, adj.displayArtist, bmp)
                } else null
            },
            onCoverDoubleTapSeek = { deltaMs ->
                if (playerManager.currentTrack.value != null) {
                    playerManager.seekBy(deltaMs)
                }
            }
        )

        // Ensure PlayerSheetController receives initial track/play state even if StateFlow emitted before init
        run {
            val initialTrack = playerManager.currentTrack.value
            if (initialTrack != null) {
                playerSheetController.updateTrackInfo(initialTrack.displayTitle, initialTrack.displayArtist, null)
                lifecycleScope.launch {
                    val bmp = CoverLoader.loadBitmap(store, initialTrack.id, 600)
                    val palette = bmp?.let {
                        withContext(Dispatchers.IO) {
                            PlayerSheetController.computeDarkPalette(
                                it,
                                playerSheetController.defaultBgColor,
                                playerSheetController.defaultSurfaceColor
                            )
                        }
                    }
                    if (playerManager.currentTrack.value?.id == initialTrack.id && bmp != null) {
                        playerSheetController.updateTrackInfo(
                            initialTrack.displayTitle,
                            initialTrack.displayArtist,
                            bmp,
                            palette
                        )
                    }
                }
            } else {
                playerSheetController.updateTrackInfo("Play something", "Tap a track or radio to begin", null)
            }
            playerSheetController.updatePlayState(playerManager.isPlaying.value)
        }

        // Refresh library in-place when background downloads or YT Music imports complete
        ytdlpManager.onDownloadComplete = {
            genreDetector.kickAuto()
            runOnUiThread {
                refreshLibraryData?.invoke()
            }
        }

        genreDetector.onGenresUpdated = {
            runOnUiThread {
                val st = genreDetector.status.value
                updateGenreSettingsSummaryViews(st)
                refreshGenreSettingsData?.invoke(st)
                if (!st.running) {
                    if (currentTab == "home") {
                        tabViews["home"]?.let { populateHomeView(it, refreshOnlineDiscover = false) }
                    } else if (currentTab == "library") {
                        refreshLibraryData?.invoke()
                    }
                }
            }
        }

        lifecycleScope.launch {
            genreDetector.status.collectLatest { st ->
                updateGenreSettingsSummaryViews(st)
                refreshGenreSettingsData?.invoke(st)
            }
        }

        syncManager.onSyncCompleted = {
            runOnUiThread {
                val msg = syncManager.status.value.message
                if (msg.isNotEmpty()) {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
                updateSyncSummaryLabel()
                updateLibraryQuickSyncVisibility()
                tabViews["home"]?.let { populateHomeView(it, refreshOnlineDiscover = false) }
                refreshLibraryData?.invoke()
                refreshSearchData?.invoke()
                refreshSettingsData?.invoke()
            }
        }

        lifecycleScope.launch {
            syncManager.pairPrompt.collectLatest { prompt ->
                if (prompt != null) {
                    showIncomingSyncPairDialog(prompt)
                } else {
                    activePairDialog?.dismiss()
                    activePairDialog = null
                }
            }
        }

        lifecycleScope.launch {
            ytdlpManager.jobs.collectLatest { jobs ->
                homeDiscoverAdapter?.updateDownloadJobs(jobs)
            }
        }

        lifecycleScope.launch {
            combine(
                playerManager.activePlaylistId,
                playerManager.activeCollectionKey,
                playerManager.isPlaying,
                playerManager.currentTrack
            ) { pid, colKey, playing, curTrack ->
                val hasTrack = curTrack != null
                Triple(
                    if (hasTrack) pid else 0L,
                    if (hasTrack) colKey else "",
                    playing && hasTrack
                )
            }.collectLatest { (pid, colKey, playing) ->
                homePlaylistAdapter?.updatePlaybackState(pid, colKey, playing)
                libraryPlaylistAdapter?.updatePlaybackState(pid, colKey, playing)
            }
        }

        // Handle system back navigation (Close Sheet -> Collapse Player -> Exit Collection -> Back to Home)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val lyricsContainer = findViewById<View>(R.id.np_lyrics_slider_container)
                if (lyricsContainer != null && lyricsContainer.visibility == View.VISIBLE) {
                    closeLyricsSlider()
                    return
                }
                if (::playerSheetController.isInitialized && playerSheetController.handleBackPressed()) {
                    return
                }
                if (isHiddenSongsOpen) {
                    closeHiddenSongsScreen()
                    return
                }
                if (isSyncScreenOpen) {
                    closeSyncScreen()
                    return
                }
                if (isGenreSettingsOpen) {
                    closeGenreSettingsScreen()
                    return
                }
                if (isCollectionDetailOpen) {
                    closeCollectionDetail()
                    return
                }
                if (currentTab != "home") {
                    selectNavTab("home")
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        // Show Home screen initially (cached)
        showTabView("home")

        // Check permissions; only trigger initial scan automatically if library is empty
        checkPermissionsOnStartup()

        // Handle shared URL from external apps (e.g. YouTube "Share with VibeMusic")
        handleSharedIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        playerManager.saveState()
    }

    override fun onStop() {
        super.onStop()
        playerManager.saveState()
    }

    private fun handleSharedIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                closeOverlays()
                currentTab = "downloads"
                updateNavIcons("downloads")
                showTabView("downloads", prefilledDownloadUrl = sharedText.trim())
            }
        }
    }

    private fun checkPermissionsOnStartup() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (store.getAllTracks(includeHidden = true).isEmpty()) {
                    scanLibraryFiles()
                    withContext(Dispatchers.Main) {
                        tabViews["home"]?.let { populateHomeView(it, refreshOnlineDiscover = false) }
                    }
                }
            }
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private suspend fun scanLibraryFiles() = withContext(Dispatchers.IO) {
        val settings = store.loadSettings()
        val allScanDirs = mutableListOf<String>()
        allScanDirs.addAll(settings.musicDirs)
        if (settings.downloadDir.isNotBlank()) allScanDirs.add(settings.downloadDir)
        val publicMusic = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MUSIC)
        if (publicMusic != null) {
            allScanDirs.add(publicMusic.absolutePath)
            allScanDirs.add(File(publicMusic, "VibeMusic").absolutePath)
        }
        getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC)?.let { extDir ->
            allScanDirs.add(extDir.absolutePath)
            allScanDirs.add(File(extDir, "VibeMusic").absolutePath)
        }
        val existingDirs = allScanDirs.filter { it.isNotBlank() && File(it).exists() }.distinct()
        scanner.scan(existingDirs)
        genreDetector.kickAuto()
    }

    private fun triggerLibraryScan() {
        refreshTab(currentTab, scanFiles = true)
    }

    // -------------------------------------------------------------
    // Bottom Navigation & Tab Caching Setup
    // -------------------------------------------------------------

    private fun setupBottomNav() {
        val home = findViewById<LinearLayout>(R.id.nav_home)
        val search = findViewById<LinearLayout>(R.id.nav_search)
        val library = findViewById<LinearLayout>(R.id.nav_library)
        val downloads = findViewById<LinearLayout>(R.id.nav_downloads)
        val settings = findViewById<LinearLayout>(R.id.nav_settings)

        home.setOnClickListener { selectNavTab("home") }
        search.setOnClickListener { selectNavTab("search") }
        library.setOnClickListener { selectNavTab("library") }
        downloads.setOnClickListener { selectNavTab("downloads") }
        settings.setOnClickListener { selectNavTab("settings") }
    }

    private fun closeOverlays() {
        isCollectionDetailOpen = false
        isHiddenSongsOpen = false
        isGenreSettingsOpen = false
        if (isSyncScreenOpen) {
            isSyncScreenOpen = false
            syncStatusCollectJob?.cancel()
            syncPeersCollectJob?.cancel()
            syncStatusCollectJob = null
            syncPeersCollectJob = null
            syncManager.stopDiscovery()
        }
        refreshGenreSettingsData = null
        overlayContainer.removeAllViews()
        overlayContainer.visibility = View.GONE
    }

    private fun selectNavTab(tab: String) {
        if (::playerSheetController.isInitialized && playerSheetController.isExpanded) {
            playerSheetController.collapseNowPlaying()
        }

        val isSameTab = (tab == currentTab)
        val hadOverlay = isCollectionDetailOpen || isHiddenSongsOpen || isGenreSettingsOpen || isSyncScreenOpen
        if (hadOverlay) {
            closeOverlays()
        }

        currentTab = tab
        updateNavIcons(tab)

        if (isSameTab) {
            // User clicked the tab while already in that tab -> refresh the tab and scan files
            showTabView(tab)
            refreshTab(tab, scanFiles = true, scrollToTop = true)
        } else {
            // Switching from another tab -> keep existing tab state without refreshing
            val alreadyCreated = tabViews.containsKey(tab)
            showTabView(tab)
            if (alreadyCreated && tab == "settings") {
                refreshSettingsData?.invoke()
            }
        }
    }

    private fun showTabView(tab: String, prefilledDownloadUrl: String = "") {
        val existing = tabViews[tab]
        if (existing == null) {
            val created = when (tab) {
                "home" -> createHomeView()
                "search" -> createSearchView()
                "library" -> createLibraryView()
                "downloads" -> createDownloadsView(prefilledDownloadUrl)
                "settings" -> createSettingsView()
                else -> createHomeView()
            }
            tabViews[tab] = created
            tabsContainer.addView(created)
        } else if (tab == "downloads" && prefilledDownloadUrl.isNotEmpty()) {
            existing.findViewById<EditText>(R.id.dl_input_url)?.setText(prefilledDownloadUrl)
        }

        for ((key, view) in tabViews) {
            view.visibility = if (key == tab) View.VISIBLE else View.GONE
        }
    }

    private fun getTabSwipeRefresh(tab: String): PullToRefreshLayout? {
        val view = tabViews[tab] ?: return null
        return when (tab) {
            "home" -> view.findViewById(R.id.home_swipe_refresh)
            "search" -> view.findViewById(R.id.search_swipe_refresh)
            "library" -> view.findViewById(R.id.library_swipe_refresh)
            "downloads" -> view.findViewById(R.id.downloads_swipe_refresh)
            "settings" -> view.findViewById(R.id.settings_swipe_refresh)
            else -> null
        }
    }

    private fun scrollTabToTop(tab: String) {
        val view = tabViews[tab] ?: return
        when (tab) {
            "home" -> {
                homeScrollY = 0
                view.findViewById<androidx.core.widget.NestedScrollView>(R.id.home_scroll)?.smoothScrollTo(0, 0)
            }
            "search" -> view.findViewById<RecyclerView>(R.id.recycler_search_results)?.smoothScrollToPosition(0)
            "library" -> view.findViewById<RecyclerView>(R.id.recycler_library)?.smoothScrollToPosition(0)
            "downloads" -> view.findViewById<androidx.core.widget.NestedScrollView>(R.id.downloads_scroll)?.smoothScrollTo(0, 0)
            "settings" -> {
                val swipe = view.findViewById<PullToRefreshLayout>(R.id.settings_swipe_refresh)
                val scrollView = (0 until (swipe?.childCount ?: 0))
                    .mapNotNull { swipe?.getChildAt(it) as? android.widget.ScrollView }
                    .firstOrNull()
                scrollView?.smoothScrollTo(0, 0)
            }
        }
    }

    private fun refreshTab(tab: String, scanFiles: Boolean = true, scrollToTop: Boolean = false) {
        val view = tabViews[tab] ?: return
        val swipeRefresh = getTabSwipeRefresh(tab)
        swipeRefresh?.isRefreshing = true
        if (scrollToTop) {
            scrollTabToTop(tab)
        }

        lifecycleScope.launch {
            val startMs = System.currentTimeMillis()
            if (scanFiles) {
                scanLibraryFiles()
            }
            when (tab) {
                "home" -> populateHomeViewAsync(view, refreshOnlineDiscover = true)
                "search" -> refreshSearchData?.invoke()
                "library" -> refreshLibraryData?.invoke()
                "downloads" -> refreshDownloadsData?.invoke()
                "settings" -> refreshSettingsData?.invoke()
            }
            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed < 350L) {
                delay(350L - elapsed)
            }
            swipeRefresh?.isRefreshing = false
        }
    }

    private fun updateNavIcons(activeTab: String) {
        val green = ContextCompat.getColor(this, R.color.accent_green)
        val gray = ContextCompat.getColor(this, R.color.text_secondary)

        val pairs = listOf(
            Triple("home", R.id.nav_home_icon, R.id.nav_home_text),
            Triple("search", R.id.nav_search_icon, R.id.nav_search_text),
            Triple("library", R.id.nav_library_icon, R.id.nav_library_text),
            Triple("downloads", R.id.nav_downloads_icon, R.id.nav_downloads_text),
            Triple("settings", R.id.nav_settings_icon, R.id.nav_settings_text)
        )

        for ((key, iconId, textId) in pairs) {
            val icon = findViewById<ImageView>(iconId)
            val text = findViewById<TextView>(textId)
            val color = if (key == activeTab) green else gray
            icon.setColorFilter(color)
            text.setTextColor(color)
        }
    }

    // -------------------------------------------------------------
    // Floating Mini-Player Setup
    // -------------------------------------------------------------

    private fun setupMiniPlayer() {
        miniCover = findViewById(R.id.mini_cover)
        miniTitle = findViewById(R.id.mini_title)
        miniArtist = findViewById(R.id.mini_artist)
        miniPlayPause = findViewById(R.id.mini_play_pause)

        miniPlayPause.setOnClickListener {
            if (playerManager.currentTrack.value == null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val all = store.getAllTracks()
                    if (all.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            playerManager.playTracks(all, 0)
                        }
                    }
                }
            } else {
                playerManager.togglePlayPause()
            }
        }

        lifecycleScope.launch {
            playerManager.currentTrack.collectLatest { track ->
                if (track != null) {
                    miniTitle.text = track.displayTitle
                    miniArtist.text = track.displayArtist
                    CoverLoader.loadInto(miniCover, store, track.id, 192, R.drawable.ic_music_note)
                } else {
                    miniTitle.text = "Play something"
                    miniArtist.text = "Tap a track or radio to begin"
                    miniCover.tag = null
                    miniCover.setImageResource(R.drawable.ic_music_note)
                    if (::playerSheetController.isInitialized) {
                        playerSheetController.updateTrackInfo("Play something", "Tap a track or radio to begin", null)
                    }
                }
            }
        }

        lifecycleScope.launch {
            playerManager.isPlaying.collectLatest { playing ->
                miniPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                if (::playerSheetController.isInitialized) {
                    playerSheetController.updatePlayState(playing)
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Expandable Now Playing Setup
    // -------------------------------------------------------------

    private fun setupNowPlaying() {
        npRoot = findViewById(R.id.now_playing_root)
        npCover = findViewById(R.id.np_cover)
        npTitle = findViewById(R.id.np_title)
        npArtist = findViewById(R.id.np_artist)
        npLike = findViewById(R.id.np_like)
        npSeekbar = findViewById(R.id.np_seekbar)
        npTimeCurrent = findViewById(R.id.np_time_current)
        npTimeTotal = findViewById(R.id.np_time_total)
        npShuffle = findViewById(R.id.np_shuffle)
        npPrev = findViewById(R.id.np_prev)
        npPlayPause = findViewById(R.id.np_play_pause)
        npNext = findViewById(R.id.np_next)
        npRepeat = findViewById(R.id.np_repeat)

        npQueueSlider = findViewById(R.id.np_queue_slider)
        npLyricsSlider = findViewById(R.id.np_lyrics_slider)
        npQueueRecycler = findViewById(R.id.np_queue_recycler)
        npLyricsRecycler = findViewById(R.id.np_lyrics_recycler)
        npLyricsEmpty = findViewById(R.id.np_lyrics_empty)

        findViewById<View>(R.id.np_collapse).setOnClickListener {
            collapseNowPlaying()
        }

        findViewById<View>(R.id.np_equalizer).setOnClickListener {
            showEqualizerDialog()
        }

        findViewById<View>(R.id.np_more_options)?.setOnClickListener { anchor ->
            val cur = playerManager.currentTrack.value ?: return@setOnClickListener
            showTrackMenu(cur, anchor)
        }

        // Dedicated YouTube Music Action Pills
        findViewById<View>(R.id.btn_np_like)?.setOnClickListener {
            val track = playerManager.currentTrack.value ?: return@setOnClickListener
            val newLiked = !track.liked
            lifecycleScope.launch(Dispatchers.IO) {
                store.setLiked(track.id, newLiked)
                withContext(Dispatchers.Main) {
                    updateLikeIcon(newLiked)
                }
            }
        }

        findViewById<View>(R.id.btn_np_save)?.setOnClickListener {
            val track = playerManager.currentTrack.value ?: return@setOnClickListener
            showAddToPlaylistDialog(track)
        }

        findViewById<View>(R.id.btn_np_lyrics)?.setOnClickListener {
            openLyricsSlider()
        }

        val volumeContainer = findViewById<View>(R.id.np_volume_bar_container)
        findViewById<View>(R.id.btn_np_audio)?.setOnClickListener {
            if (volumeContainer.visibility == View.VISIBLE) {
                volumeContainer.visibility = View.GONE
            } else {
                volumeContainer.visibility = View.VISIBLE
            }
        }

        findViewById<View>(R.id.btn_np_share)?.setOnClickListener {
            val track = playerManager.currentTrack.value ?: return@setOnClickListener
            shareTrackFile(track)
        }

        findViewById<View>(R.id.btn_np_timer)?.setOnClickListener {
            showSleepTimerDialog()
        }

        findViewById<View>(R.id.np_queue_close)?.setOnClickListener {
            closeQueueSlider()
        }
        findViewById<View>(R.id.np_lyrics_close)?.setOnClickListener {
            closeLyricsSlider()
        }

        // Volume Slider Controls
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volumeSlider = findViewById<SeekBar>(R.id.np_volume_slider)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeSlider.max = maxVolume
        volumeSlider.progress = currentVolume
        volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<View>(R.id.np_volume_down)?.setOnClickListener {
            val newVol = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) - 1).coerceAtLeast(0)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
            volumeSlider.progress = newVol
        }
        findViewById<View>(R.id.np_volume_up)?.setOnClickListener {
            val newVol = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + 1).coerceAtMost(maxVolume)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
            volumeSlider.progress = newVol
        }

        val volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    volumeSlider.progress = vol
                }
            }
        }
        registerReceiver(volumeReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))

        // Scrim click to dismiss lyrics
        findViewById<View>(R.id.np_lyrics_scrim)?.setOnClickListener {
            closeLyricsSlider()
        }

        var lyricsStartY = 0f
        var lyricsDragging = false
        findViewById<View>(R.id.np_lyrics_drag_hitbox)?.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lyricsStartY = event.rawY
                    lyricsDragging = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (lyricsDragging) {
                        val dy = event.rawY - lyricsStartY
                        if (dy > 0) {
                            npLyricsSlider.translationY = dy
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (lyricsDragging) {
                        lyricsDragging = false
                        val dy = event.rawY - lyricsStartY
                        if (dy > 140f) {
                            closeLyricsSlider()
                        } else {
                            npLyricsSlider.animate().translationY(0f).setDuration(150).start()
                        }
                    }
                    true
                }
                else -> false
            }
        }

        // Clear Queue button
        findViewById<View>(R.id.btn_queue_clear)?.setOnClickListener {
            playerManager.clearQueue()
            Toast.makeText(this, "Queue cleared", Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.np_play_pause_bg).setOnClickListener {
            playerManager.togglePlayPause()
        }
        npPrev.setOnClickListener { playerManager.playPrevious() }
        npNext.setOnClickListener { playerManager.playNext() }
        npShuffle.setOnClickListener { playerManager.setShuffle(!playerManager.isShuffle.value) }
        npRepeat.setOnClickListener { playerManager.toggleRepeat() }

        npLike.setOnClickListener {
            val track = playerManager.currentTrack.value ?: return@setOnClickListener
            val newLiked = !track.liked
            lifecycleScope.launch(Dispatchers.IO) {
                store.setLiked(track.id, newLiked)
                val updated = track.copy(liked = newLiked)
                withContext(Dispatchers.Main) {
                    updateLikeIcon(newLiked)
                }
            }
        }

        npSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    npTimeCurrent.text = formatMs(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekbar = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserTrackingSeekbar = false
                val pos = seekBar?.progress?.toLong() ?: 0L
                playerManager.seekTo(pos)
            }
        })

        // Queue setup with dedicated "Now playing" row and upcoming tracks (excluding past tracks)
        val npQueueNowPlayingContainer = findViewById<View>(R.id.np_queue_now_playing_container)
        val npQueueCurrentCover = findViewById<ImageView>(R.id.np_queue_current_cover)
        val npQueueCurrentTitle = findViewById<TextView>(R.id.np_queue_current_title)
        val npQueueCurrentArtist = findViewById<TextView>(R.id.np_queue_current_artist)
        val npQueueEqualizer = findViewById<com.vibemusic.nativeapp.ui.EqualizerBarsView>(R.id.np_queue_equalizer)
        val npQueueNextUpTitle = findViewById<TextView>(R.id.np_queue_next_up_title)
        val btnQueueClear = findViewById<View>(R.id.btn_queue_clear)

        npQueueNowPlayingContainer?.setOnLongClickListener { anchor ->
            val cur = playerManager.currentTrack.value
            if (cur != null) {
                showTrackMenu(cur, anchor)
                true
            } else {
                false
            }
        }

        fun updateQueueDisplay() {
            val q = playerManager.queue.value
            val curIdx = playerManager.currentIndex.value
            val curTrack = playerManager.currentTrack.value

            if (curTrack != null) {
                npQueueNowPlayingContainer?.visibility = View.VISIBLE
                npQueueCurrentTitle?.text = curTrack.displayTitle
                npQueueCurrentArtist?.text = curTrack.displayArtist
                npQueueCurrentCover?.let {
                    CoverLoader.loadInto(it, store, curTrack.id, 192, R.drawable.ic_music_note)
                }
                npQueueEqualizer?.setPlaying(playerManager.isPlaying.value)
            } else {
                npQueueCurrentCover?.tag = null
                npQueueNowPlayingContainer?.visibility = View.GONE
            }

            // Exclude past tracks: only show upcoming tracks
            val upcoming = if (curIdx >= 0 && curIdx < q.size) {
                q.drop(curIdx + 1)
            } else {
                emptyList()
            }

            npQueueNextUpTitle?.text = "Next up · ${upcoming.size}"
            findViewById<TextView>(R.id.np_bottom_queue_text)?.text = "Up next • ${upcoming.size} songs"
            queueAdapter.submitList(upcoming)
        }

        btnQueueClear?.setOnClickListener {
            playerManager.clearQueue()
            Toast.makeText(this@MainActivity, "Queue cleared", Toast.LENGTH_SHORT).show()
        }

        queueAdapter = TrackAdapter(
            store,
            onTrackClick = { track ->
                val curIdx = playerManager.currentIndex.value
                val q = playerManager.queue.value
                val upcoming = if (curIdx >= 0 && curIdx < q.size) q.drop(curIdx + 1) else emptyList()
                val pos = upcoming.indexOfFirst { it.id == track.id }
                if (pos >= 0) {
                    playerManager.playQueueIndex(curIdx + 1 + pos)
                }
            },
            onMenuClick = { track, anchor ->
                val curIdx = playerManager.currentIndex.value
                val q = playerManager.queue.value
                val upcoming = if (curIdx >= 0 && curIdx < q.size) q.drop(curIdx + 1) else emptyList()
                val pos = upcoming.indexOfFirst { it.id == track.id }
                val realIdx = if (pos >= 0) curIdx + 1 + pos else -1
                showTrackMenu(track, anchor, isFromQueue = true, queueIndex = realIdx)
            }
        )
        npQueueRecycler.layoutManager = LinearLayoutManager(this)
        npQueueRecycler.adapter = queueAdapter

        // Lyrics setup
        lyricsAdapter = LyricsAdapter { line ->
            playerManager.seekTo(line.timeMs)
        }
        npLyricsRecycler.layoutManager = LinearLayoutManager(this)
        npLyricsRecycler.adapter = lyricsAdapter

        // State flows binding
        lifecycleScope.launch {
            playerManager.currentTrack.collectLatest { track ->
                updateQueueDisplay()
                if (track != null) {
                    npTitle.text = track.displayTitle
                    npArtist.text = track.displayArtist
                    updateLikeIcon(track.liked)

                    val defaultBg = if (::playerSheetController.isInitialized) {
                        playerSheetController.defaultBgColor
                    } else {
                        ContextCompat.getColor(this@MainActivity, R.color.bg_primary)
                    }
                    val defaultSurface = if (::playerSheetController.isInitialized) {
                        playerSheetController.defaultSurfaceColor
                    } else {
                        ContextCompat.getColor(this@MainActivity, R.color.bg_surface_elevated)
                    }

                    val (bmp, palette, lyrics) = withContext(Dispatchers.IO) {
                        val loadedBmp = CoverLoader.loadBitmap(store, track.id, 600)
                        val computedPalette = loadedBmp?.let {
                            PlayerSheetController.computeDarkPalette(it, defaultBg, defaultSurface)
                        }
                        val loadedLyrics = com.vibemusic.nativeapp.scanner.LyricsHelper.getLyrics(track)
                        playerManager.peekNextTrack()?.let { CoverLoader.preload(store, it.id, 192) }
                        playerManager.peekPreviousTrack()?.let { CoverLoader.preload(store, it.id, 192) }
                        Triple(loadedBmp, computedPalette, loadedLyrics)
                    }

                    if (::playerSheetController.isInitialized) {
                        playerSheetController.updateTrackInfo(track.displayTitle, track.displayArtist, bmp, palette)
                    } else if (bmp != null) {
                        npCover.setImageBitmap(bmp)
                    } else {
                        npCover.setImageResource(R.drawable.ic_music_note)
                    }

                    // Load lyrics
                    if (lyrics.lines.isNotEmpty()) {
                        lyricsAdapter.submitLines(lyrics.lines)
                        npLyricsEmpty.visibility = View.GONE
                        npLyricsRecycler.visibility = View.VISIBLE
                    } else {
                        lyricsAdapter.submitLines(emptyList())
                        npLyricsEmpty.visibility = View.VISIBLE
                        npLyricsRecycler.visibility = View.GONE
                    }
                } else {
                    npTitle.text = "Play something"
                    npArtist.text = "Tap a track or radio to begin"
                    updateLikeIcon(false)
                    if (::playerSheetController.isInitialized) {
                        playerSheetController.updateTrackInfo("Play something", "Tap a track or radio to begin", null)
                    } else {
                        npCover.setImageResource(R.drawable.ic_music_note)
                    }
                    lyricsAdapter.submitLines(emptyList())
                    npLyricsEmpty.visibility = View.VISIBLE
                    npLyricsRecycler.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            playerManager.isPlaying.collectLatest { playing ->
                npPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                npQueueEqualizer?.setPlaying(playing)
            }
        }

        lifecycleScope.launch {
            playerManager.positionMs.collectLatest { pos ->
                if (!isUserTrackingSeekbar) {
                    npSeekbar.progress = pos.toInt()
                    npTimeCurrent.text = formatMs(pos)
                    val activeIdx = lyricsAdapter.updateCurrentTime(pos)
                    if (activeIdx >= 0 && ::npLyricsSlider.isInitialized && npLyricsSlider.visibility == View.VISIBLE) {
                        npLyricsRecycler.smoothScrollToPosition(activeIdx)
                    }
                }
            }
        }

        lifecycleScope.launch {
            playerManager.durationMs.collectLatest { dur ->
                npSeekbar.max = dur.toInt()
                npTimeTotal.text = formatMs(dur)
            }
        }

        lifecycleScope.launch {
            playerManager.queue.collectLatest {
                updateQueueDisplay()
            }
        }

        lifecycleScope.launch {
            playerManager.currentIndex.collectLatest {
                updateQueueDisplay()
            }
        }

        lifecycleScope.launch {
            playerManager.isShuffle.collectLatest { shuf ->
                npShuffle.setColorFilter(
                    ContextCompat.getColor(this@MainActivity, if (shuf) R.color.accent_green else R.color.text_secondary)
                )
            }
        }

        lifecycleScope.launch {
            playerManager.repeatMode.collectLatest { rep ->
                when (rep) {
                    "one" -> {
                        npRepeat.setImageResource(R.drawable.ic_repeat_one)
                        npRepeat.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                    }
                    "all" -> {
                        npRepeat.setImageResource(R.drawable.ic_repeat)
                        npRepeat.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                    }
                    else -> {
                        npRepeat.setImageResource(R.drawable.ic_repeat)
                        npRepeat.setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                    }
                }
            }
        }

        val npTimerIcon = findViewById<ImageView>(R.id.np_timer_icon)
        val npTimerText = findViewById<TextView>(R.id.np_timer_text)
        fun updateTimerPill() {
            val sec = playerManager.sleepTimerRemainingSec.value
            val tracks = playerManager.sleepTimerRemainingTracks.value
            when {
                sec > 0 -> {
                    val color = ContextCompat.getColor(this@MainActivity, R.color.accent_green)
                    npTimerIcon?.setColorFilter(color)
                    npTimerText?.setTextColor(color)
                    npTimerText?.text = formatMs(sec * 1000L)
                }
                tracks > 0 -> {
                    val color = ContextCompat.getColor(this@MainActivity, R.color.accent_green)
                    npTimerIcon?.setColorFilter(color)
                    npTimerText?.setTextColor(color)
                    npTimerText?.text = if (tracks == 1) "1 trk" else "$tracks trk"
                }
                else -> {
                    val color = ContextCompat.getColor(this@MainActivity, R.color.text_primary)
                    npTimerIcon?.setColorFilter(color)
                    npTimerText?.setTextColor(color)
                    npTimerText?.text = "Timer"
                }
            }
        }
        lifecycleScope.launch {
            playerManager.sleepTimerRemainingSec.collectLatest {
                updateTimerPill()
            }
        }
        lifecycleScope.launch {
            playerManager.sleepTimerRemainingTracks.collectLatest {
                updateTimerPill()
            }
        }
    }

    private fun expandNowPlaying() {
        if (::playerSheetController.isInitialized) {
            playerSheetController.expandNowPlaying()
        }
    }

    private fun collapseNowPlaying() {
        val lyricsContainer = findViewById<View>(R.id.np_lyrics_slider_container)
        if (lyricsContainer != null && lyricsContainer.visibility == View.VISIBLE) {
            closeLyricsSlider(animated = false)
        }
        if (::playerSheetController.isInitialized) {
            playerSheetController.collapseNowPlaying()
        }
    }

    private fun openQueueSlider() {
        if (::playerSheetController.isInitialized) {
            playerSheetController.openQueueStage1()
        }
    }

    private fun closeQueueSlider(animated: Boolean = true) {
        if (::playerSheetController.isInitialized) {
            playerSheetController.closeQueue(animated = animated)
        }
    }

    private fun openLyricsSlider() {
        val container = findViewById<View>(R.id.np_lyrics_slider_container) ?: npLyricsSlider
        if (container.visibility != View.VISIBLE) {
            container.visibility = View.VISIBLE
            val targetHeight = (container.height.takeIf { it > 0 } ?: 2000).toFloat()
            npLyricsSlider.translationY = targetHeight
            npLyricsSlider.alpha = 1.0f
            npLyricsSlider.animate()
                .translationY(0f)
                .setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun closeLyricsSlider(animated: Boolean = true) {
        val container = findViewById<View>(R.id.np_lyrics_slider_container) ?: npLyricsSlider
        if (container.visibility == View.VISIBLE) {
            if (animated) {
                val targetHeight = (container.height.takeIf { it > 0 } ?: 2000).toFloat()
                npLyricsSlider.animate()
                    .translationY(targetHeight)
                    .alpha(0.0f)
                    .setDuration(220)
                    .withEndAction {
                        container.visibility = View.GONE
                        npLyricsSlider.translationY = 0f
                        npLyricsSlider.alpha = 1.0f
                    }
                    .start()
            } else {
                container.visibility = View.GONE
                npLyricsSlider.translationY = 0f
                npLyricsSlider.alpha = 1.0f
            }
        }
    }

    private fun updateRadioPills(mode: String) {
        currentRadioMode = mode
    }

    private fun updateLikeIcon(liked: Boolean) {
        val color = if (liked) ContextCompat.getColor(this, R.color.accent_green) else ContextCompat.getColor(this, R.color.text_primary)
        npLike.setImageResource(if (liked) R.drawable.ic_heart_filled else R.drawable.ic_heart)
        npLike.setColorFilter(color)
        findViewById<TextView>(R.id.np_like_text)?.apply {
            text = if (liked) "Liked" else "Like"
            setTextColor(color)
        }
    }

    private fun reseedRadio(mode: String) {
        val cur = playerManager.currentTrack.value ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val all = store.getAllTracks()
            val plMap = store.getAllPlaylistTracks()
            val mix = RecommendationEngine.buildRadioMix(cur, all, plMap, mode, 35)
            withContext(Dispatchers.Main) {
                playerManager.reseedUpcoming(mix)
                queueAdapter.submitList(playerManager.queue.value)
                Toast.makeText(this@MainActivity, "Radio mix: $mode", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -------------------------------------------------------------
    // Screen 1: Home Feed (3x3 Quick Picks + Removable/Orderable Segments)
    // -------------------------------------------------------------

    private fun createHomeView(): View {
        val view = layoutInflater.inflate(R.layout.screen_home, tabsContainer, false)

        val swipeRefresh = view.findViewById<PullToRefreshLayout>(R.id.home_swipe_refresh)
        swipeRefresh?.onRefreshListener = {
            refreshTab("home", scanFiles = true, scrollToTop = false)
        }

        val btnCustomize = view.findViewById<ImageView>(R.id.home_btn_customize)
        val btnRescan = view.findViewById<ImageView>(R.id.home_btn_rescan)
        btnCustomize.setOnClickListener { showHomeCustomizeDialog() }
        btnRescan.setOnClickListener {
            Toast.makeText(this@MainActivity, "Scanning device storage for music...", Toast.LENGTH_SHORT).show()
            refreshTab("home", scanFiles = true, scrollToTop = false)
        }

        val homeScroll = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.home_scroll)
        homeScroll?.setOnScrollChangeListener(androidx.core.widget.NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
            homeScrollY = scrollY
        })

        populateHomeView(view, refreshOnlineDiscover = true)
        return view
    }

    private fun applyHomeSectionOrder(view: View) {
        val settings = store.loadSettings()
        val container = view.findViewById<LinearLayout>(R.id.home_sections_container) ?: return

        val segmentMap = mapOf(
            "recentPlaylists" to view.findViewById<View>(R.id.section_recent_playlists),
            "quickPicks" to view.findViewById<View>(R.id.section_quick_picks),
            "topTracks" to view.findViewById<View>(R.id.section_top_tracks),
            "artistRadios" to view.findViewById<View>(R.id.section_artist_radios),
            "discover" to view.findViewById<View>(R.id.section_discover),
            "recentlyAdded" to view.findViewById<View>(R.id.section_recently_added)
        )

        for (key in settings.homeOrder) {
            val secView = segmentMap[key] ?: continue
            val parent = secView.parent as? ViewGroup
            parent?.removeView(secView)
            container.addView(secView)

            val isHidden = settings.homeHidden.contains(key) || (key == "discover" && !settings.enableOnlineFeatures)
            secView.visibility = if (isHidden) View.GONE else View.VISIBLE
        }
    }

    private data class HomePreparedData(
        val allTracks: List<Track>,
        val playlists: List<Playlist>,
        val plMap: Map<Long, List<String>>,
        val quickPicksTracks: List<Track>,
        val topTracks: List<Track>,
        val recentTracks: List<Track>,
        val artists: List<String>,
        val artistCoverMap: Map<String, String>
    )

    private class ArtistCardViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val coverImg: ImageView = view.findViewById(R.id.artist_cover)
        val nameTxt: TextView = view.findViewById(R.id.artist_name)
    }

    private suspend fun populateHomeViewAsync(view: View, refreshOnlineDiscover: Boolean = true) {
        val settings = withContext(Dispatchers.IO) { store.loadSettings() }
        withContext(Dispatchers.Main) {
            applyHomeSectionOrder(view)
        }

        val homeData = withContext(Dispatchers.IO) {
            val all = store.getAllTracks()
            val pls = store.getPlaylists()
            val map = store.getAllPlaylistTracks()
            val now = System.currentTimeMillis() / 1000
            val r7 = store.getRecentPlayCounts(now - 7 * 86400L)
            val r30 = store.getRecentPlayCounts(now - 30 * 86400L)
            val ranked = RecommendationEngine.rankLibraryArtistsByAffinity(all, r7, r30, map)
            val quickPicks = RecommendationEngine.buildQuickPicks(all, 0, map)
            val topTracks = all.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.take(10)
            val recentTracks = all.sortedByDescending { it.addedAt }.take(10)
            val artists = if (ranked.isNotEmpty()) {
                ranked.map { it.artist }
            } else {
                all.flatMap { CanonicalKey.splitArtists(it.artist) }
                    .distinct()
                    .filter { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
            }
            val artistCoverMap = HashMap<String, String>(artists.size)
            val artistFallbackMap = HashMap<String, String>(artists.size)
            for (t in all) {
                for (a in CanonicalKey.splitArtists(t.artist)) {
                    if (a.isBlank() || a.equals("Unknown Artist", ignoreCase = true)) continue
                    val normKey = CanonicalKey.norm(a)
                    if (normKey.isEmpty()) continue
                    if (!artistFallbackMap.containsKey(normKey)) {
                        artistFallbackMap[normKey] = t.id
                    }
                    if (t.hasCover && !artistCoverMap.containsKey(normKey)) {
                        artistCoverMap[normKey] = t.id
                    }
                }
            }
            for ((k, tid) in artistFallbackMap) {
                if (!artistCoverMap.containsKey(k)) {
                    artistCoverMap[k] = tid
                }
            }
            HomePreparedData(all, pls, map, quickPicks, topTracks, recentTracks, artists, artistCoverMap)
        }
        val allTracks = homeData.allTracks
        val playlists = homeData.playlists
        val plMap = homeData.plMap
        val quickPicksTracks = homeData.quickPicksTracks
        val topTracks = homeData.topTracks
        val recentTracks = homeData.recentTracks
        val artists = homeData.artists

        withContext(Dispatchers.Main) {
            val homeScroll = view.findViewById<androidx.core.widget.NestedScrollView>(R.id.home_scroll)

            // 1. Recent Playlists
            val plRecycler = view.findViewById<RecyclerView>(R.id.recycler_recent_playlists)
            if (plRecycler.layoutManager == null) {
                plRecycler.layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            }
            val plAdapter = PlaylistAdapter(
                store = store,
                onPlayClick = { pl -> togglePlaylistPlayback(pl) },
                onPlaylistClick = { pl ->
                    openCollectionDetail(pl.name, emptyList(), pl.id, isPlaylist = true)
                }
            )
            homePlaylistAdapter = plAdapter
            plRecycler.adapter = plAdapter
            plAdapter.submitList(playlists)
            val hasCurrentTrack = playerManager.currentTrack.value != null
            plAdapter.updatePlaybackState(
                if (hasCurrentTrack) playerManager.activePlaylistId.value else 0L,
                if (hasCurrentTrack) playerManager.activeCollectionKey.value else "",
                playerManager.isPlaying.value && hasCurrentTrack
            )

            // 2. Quick Picks (YouTube Music Horizontal Carousel of 4-song columns)
            val qpRecycler = view.findViewById<RecyclerView>(R.id.recycler_quick_picks)
            val qpLayoutManager = (qpRecycler.layoutManager as? LinearLayoutManager)
                ?: object : LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false) {
                    override fun scrollHorizontallyBy(
                        dx: Int,
                        recycler: RecyclerView.Recycler?,
                        state: RecyclerView.State?
                    ): Int {
                        val factor = if (qpRecycler.scrollState == RecyclerView.SCROLL_STATE_SETTLING) 0.68f else 0.82f
                        val dampedDx = (dx * factor).toInt().let {
                            if (it == 0 && dx != 0) dx.coerceIn(-1, 1) else it
                        }
                        val consumed = super.scrollHorizontallyBy(dampedDx, recycler, state)
                        return if (consumed == dampedDx) dx else consumed
                    }
                }.also {
                    qpRecycler.layoutManager = it
                }
            val qpAdapter = QuickPicksAdapter(
                store,
                onTrackClick = { track ->
                    val mix = RecommendationEngine.buildRadioMix(track, allTracks, plMap, "all", 25)
                    playerManager.playTracks(mix, startIndex = 0, shuffle = false)
                },
                onMoreClick = { track, anchor ->
                    showTrackMenu(track, anchor)
                }
            )
            qpRecycler.adapter = qpAdapter
            qpAdapter.submitTracks(quickPicksTracks)

            // Spawn next column seamlessly as user scrolls right
            qpRecycler.clearOnScrollListeners()
            qpRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dx > 0) {
                        val lastVisible = qpLayoutManager.findLastVisibleItemPosition()
                        val total = qpAdapter.itemCount
                        if (lastVisible >= total - 2 && qpAdapter.hasMoreColumns()) {
                            qpAdapter.appendColumn()
                        }
                    }
                }
            })

            view.findViewById<View>(R.id.btn_quick_picks_play_all)?.setOnClickListener {
                if (quickPicksTracks.isNotEmpty()) {
                    playerManager.playTracks(quickPicksTracks, startIndex = 0, shuffle = false)
                }
            }

            // 3. Most Played / Top Tracks
            val topRecycler = view.findViewById<RecyclerView>(R.id.recycler_top_tracks)
            if (topRecycler.layoutManager == null) {
                topRecycler.layoutManager = LinearLayoutManager(this@MainActivity)
            }
            val topAdapter = TrackAdapter(store, { track ->
                playerManager.playTracks(listOf(track), 0)
            }, { track, anchor -> showTrackMenu(track, anchor) })
            topRecycler.adapter = topAdapter
            topAdapter.submitList(topTracks)

            // 4. Artist Radios (with circular cover art from artist's music)
            val artistRecycler = view.findViewById<RecyclerView>(R.id.recycler_artist_radios)
            if (artistRecycler.layoutManager == null) {
                artistRecycler.layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            }
            val artistAdapter = object : RecyclerView.Adapter<ArtistCardViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArtistCardViewHolder {
                    val card = layoutInflater.inflate(R.layout.item_artist_card, parent, false)
                    return ArtistCardViewHolder(card)
                }

                override fun onBindViewHolder(holder: ArtistCardViewHolder, position: Int) {
                    val art = artists[position]
                    holder.nameTxt.text = art

                    val coverTrackId = homeData.artistCoverMap[CanonicalKey.norm(art)]
                    CoverLoader.loadInto(holder.coverImg, store, coverTrackId, 256, R.drawable.ic_music_note)

                    holder.itemView.setOnClickListener {
                        val artistOnlyTracks = allTracks.filter {
                            CanonicalKey.hasArtist(it.artist, art) || it.displayArtist.equals(art, ignoreCase = true)
                        }
                        if (artistOnlyTracks.isNotEmpty()) {
                            val radioSeed = artistOnlyTracks.maxByOrNull { it.playCount * 10 + if (it.liked) 25 else 0 }
                                ?: artistOnlyTracks.first()
                            val radioMix = RecommendationEngine.buildRadioMix(radioSeed, allTracks, plMap, "artist", 25)
                            playerManager.playTracks(
                                radioMix,
                                startIndex = 0,
                                collectionKey = "collection:$art Radio"
                            )
                            openCollectionDetail(
                                title = "$art Radio",
                                initialTracks = artistOnlyTracks,
                                playlistId = 0L,
                                isPlaylist = false,
                                isArtistRadio = true,
                                artistName = art
                            )
                        }
                    }
                }

                override fun getItemCount(): Int = artists.size
            }
            artistRecycler.adapter = artistAdapter

            // 5. Online Discover (YouTube Music Horizontal Carousel of 4-song columns)
            if (refreshOnlineDiscover && !settings.homeHidden.contains("discover")) {
                val discRecycler = view.findViewById<RecyclerView>(R.id.recycler_discover)
                val placeholder = view.findViewById<View>(R.id.layout_discover_placeholder)
                val btnMore = view.findViewById<TextView>(R.id.btn_discover_more)
                val btnRefresh = view.findViewById<ImageView>(R.id.btn_discover_refresh)
                if (discRecycler.layoutManager == null) {
                    discRecycler.layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
                }
                (discRecycler.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false

                val discAdapter = OnlineDiscoverAdapter(
                    lifecycleScope,
                    onItemClick = { item ->
                        val url = "https://www.youtube.com/watch?v=${item.ytId}"
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        try {
                            startActivity(intent)
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "Could not open link", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onDownloadClick = { item ->
                        ytdlpManager.enqueueDownload(
                            url = "https://www.youtube.com/watch?v=${item.ytId}",
                            title = item.title
                        )
                        Toast.makeText(this@MainActivity, "Downloading: ${item.title}", Toast.LENGTH_SHORT).show()
                    }
                )
                homeDiscoverAdapter = discAdapter
                discAdapter.updateDownloadJobs(ytdlpManager.jobs.value)
                discRecycler.adapter = discAdapter

                val usedArtists = mutableSetOf<String>()

                fun loadOnlineDiscover(append: Boolean = false, forceRefresh: Boolean = false) {
                    if (!settings.enableOnlineFeatures) return
                    if (!append) {
                        placeholder?.visibility = View.VISIBLE
                        discRecycler.visibility = View.GONE
                    }
                    this@MainActivity.lifecycleScope.launch(Dispatchers.IO) {
                        val items = ytdlpManager.discoverOnline(
                            excludeArtists = usedArtists,
                            append = append,
                            forceRefresh = forceRefresh
                        )
                        val newArtists = items.map { it.artist }.toSet()
                        usedArtists.addAll(newArtists)
                        withContext(Dispatchers.Main) {
                            placeholder?.visibility = View.GONE
                            discRecycler.visibility = View.VISIBLE
                            if (append) {
                                discAdapter.appendItems(items)
                                if (items.isNotEmpty() && discAdapter.itemCount > 0) {
                                    discRecycler.smoothScrollToPosition(discAdapter.itemCount - 1)
                                    Toast.makeText(this@MainActivity, "Added ${items.size} new recommendations", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                discAdapter.submitItems(items)
                                if (homeScrollY > 0) {
                                    homeScroll?.post { homeScroll.scrollTo(0, homeScrollY) }
                                }
                            }
                            btnMore?.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
                        }
                    }
                }

                btnMore?.setOnClickListener {
                    Toast.makeText(this@MainActivity, "Finding more songs...", Toast.LENGTH_SHORT).show()
                    loadOnlineDiscover(append = true, forceRefresh = false)
                }

                btnRefresh?.setOnClickListener {
                    btnRefresh.animate().rotationBy(360f).setDuration(500).start()
                    Toast.makeText(this@MainActivity, "Refreshing discover...", Toast.LENGTH_SHORT).show()
                    usedArtists.clear()
                    loadOnlineDiscover(append = false, forceRefresh = true)
                }

                loadOnlineDiscover()
            }

            // 6. Recently Added
            val recentRecycler = view.findViewById<RecyclerView>(R.id.recycler_recently_added)
            if (recentRecycler.layoutManager == null) {
                recentRecycler.layoutManager = LinearLayoutManager(this@MainActivity)
            }
            val recentAdapter = TrackAdapter(store, { track ->
                playerManager.playTracks(listOf(track), 0)
            }, { track, anchor -> showTrackMenu(track, anchor) })
            recentRecycler.adapter = recentAdapter
            recentAdapter.submitList(recentTracks)
        }
    }

    private fun populateHomeView(view: View, refreshOnlineDiscover: Boolean = true) {
        lifecycleScope.launch {
            populateHomeViewAsync(view, refreshOnlineDiscover)
        }
    }

    // -------------------------------------------------------------
    // Screen 2: Search Screen
    // -------------------------------------------------------------

    private fun createSearchView(): View {
        val view = layoutInflater.inflate(R.layout.screen_search, tabsContainer, false)

        val swipeRefresh = view.findViewById<PullToRefreshLayout>(R.id.search_swipe_refresh)
        swipeRefresh?.onRefreshListener = {
            refreshTab("search", scanFiles = true, scrollToTop = false)
        }

        val input = view.findViewById<EditText>(R.id.search_input)
        val clear = view.findViewById<ImageView>(R.id.search_clear)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_search_results)
        recycler.layoutManager = LinearLayoutManager(this)

        var filter = "all"
        var showAllDefaultTracks = false
        var currentPlaybackList: List<Track> = emptyList()
        var searchJob: Job? = null
        var cachedTracks: List<Track>? = null
        var cachedPlaylists: List<Playlist>? = null
        var cachedPlTrackMap: Map<Long, List<String>>? = null

        val trackAdapter = TrackAdapter(store, { track ->
            val list = currentPlaybackList.ifEmpty { listOf(track) }
            val idx = list.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            playerManager.playTracks(list, idx)
        }, { track, anchor -> showTrackMenu(track, anchor) })
        recycler.adapter = trackAdapter

        fun performSearch(query: String, debounceMs: Long = 0L, forceReload: Boolean = false) {
            searchJob?.cancel()
            searchJob = lifecycleScope.launch(Dispatchers.IO) {
                if (debounceMs > 0L) delay(debounceMs)
                if (forceReload || cachedTracks == null) {
                    cachedTracks = store.getAllTracks()
                    cachedPlaylists = store.getPlaylists()
                    cachedPlTrackMap = store.getAllPlaylistTracks()
                }
                val allTracks = cachedTracks ?: emptyList()
                val playlists = cachedPlaylists ?: emptyList()
                val plTrackMap = cachedPlTrackMap ?: emptyMap()

                val q = query.trim().lowercase()
                if (q.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        currentPlaybackList = allTracks
                        if (!showAllDefaultTracks && allTracks.size > 50) {
                            trackAdapter.setFooterButton("Show all (${allTracks.size} songs)") {
                                showAllDefaultTracks = true
                                performSearch(input.text.toString(), debounceMs = 0L)
                            }
                            trackAdapter.submitList(allTracks.take(50))
                        } else {
                            trackAdapter.setFooterButton(null)
                            trackAdapter.submitList(allTracks)
                        }
                    }
                } else {
                    val matchingPlaylistTrackIds = if (filter == "all" || filter == "playlists") {
                        playlists.asSequence()
                            .filter { it.name.lowercase().contains(q) }
                            .flatMap { plTrackMap[it.id]?.asSequence() ?: emptySequence() }
                            .toSet()
                    } else {
                        emptySet()
                    }
                    val filtered = allTracks.filter { t ->
                        when (filter) {
                            "songs" -> t.displayTitle.lowercase().contains(q) || t.title.lowercase().contains(q)
                            "artists" -> t.displayArtist.lowercase().contains(q) || t.artist.lowercase().contains(q)
                            "albums" -> t.album.lowercase().contains(q)
                            "playlists" -> matchingPlaylistTrackIds.contains(t.id)
                            else -> t.displayTitle.lowercase().contains(q) ||
                                t.displayArtist.lowercase().contains(q) ||
                                t.album.lowercase().contains(q) ||
                                t.relPath.lowercase().contains(q) ||
                                matchingPlaylistTrackIds.contains(t.id)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        currentPlaybackList = filtered
                        trackAdapter.setFooterButton(null)
                        trackAdapter.submitList(filtered)
                    }
                }
            }
        }

        refreshSearchData = {
            performSearch(input.text.toString(), debounceMs = 0L, forceReload = true)
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                performSearch(s?.toString() ?: "", debounceMs = 120L)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        clear.setOnClickListener {
            input.setText("")
        }

        // Chip selection
        val chips = listOf(
            Triple(view.findViewById<TextView>(R.id.chip_search_all), "all", "All"),
            Triple(view.findViewById<TextView>(R.id.chip_search_songs), "songs", "Songs"),
            Triple(view.findViewById<TextView>(R.id.chip_search_artists), "artists", "Artists"),
            Triple(view.findViewById<TextView>(R.id.chip_search_albums), "albums", "Albums"),
            Triple(view.findViewById<TextView>(R.id.chip_search_playlists), "playlists", "Playlists")
        )

        for ((chip, fKey, _) in chips) {
            chip.setOnClickListener {
                filter = fKey
                for ((c, k, _) in chips) {
                    val active = k == filter
                    c.setBackgroundResource(if (active) R.drawable.bg_pill_active else R.drawable.bg_pill)
                    c.setTextColor(ContextCompat.getColor(this, if (active) R.color.black else R.color.text_primary))
                }
                performSearch(input.text.toString(), debounceMs = 0L)
            }
        }

        performSearch("", debounceMs = 0L, forceReload = true)
        return view
    }

    // -------------------------------------------------------------
    // Screen 3: Library Screen
    // -------------------------------------------------------------

    private fun createLibraryView(): View {
        val view = layoutInflater.inflate(R.layout.screen_library, tabsContainer, false)

        val swipeRefresh = view.findViewById<PullToRefreshLayout>(R.id.library_swipe_refresh)
        swipeRefresh?.onRefreshListener = {
            refreshTab("library", scanFiles = true, scrollToTop = false)
        }

        val btnNew = view.findViewById<ImageView>(R.id.lib_btn_new_playlist)
        val btnImport = view.findViewById<ImageView>(R.id.lib_btn_import_m3u)
        libQuickSyncBtn = view.findViewById(R.id.lib_btn_quick_sync)
        libQuickSyncBtn?.setOnClickListener { showSyncScreen(autoQuickSync = true) }
        updateLibraryQuickSyncVisibility()
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_library)
        recycler.layoutManager = GridLayoutManager(this@MainActivity, 2)

        btnNew.setOnClickListener { showCreatePlaylistDialog() }
        btnImport.setOnClickListener { showImportM3uDialog() }

        val tabPlaylists = view.findViewById<TextView>(R.id.lib_tab_playlists)
        val tabAlbums = view.findViewById<TextView>(R.id.lib_tab_albums)
        val tabArtists = view.findViewById<TextView>(R.id.lib_tab_artists)
        val tabMySongs = view.findViewById<TextView>(R.id.lib_tab_my_songs)

        var selectedTab = "playlists"
        var showAllLibrarySongs = false

        fun loadLibraryContent() {
            lifecycleScope.launch(Dispatchers.IO) {
                val allTracks = store.getAllTracks()
                val playlists = store.getPlaylists()

                withContext(Dispatchers.Main) {
                    if (selectedTab == "my_songs") {
                        if (recycler.layoutManager !is LinearLayoutManager || recycler.layoutManager is GridLayoutManager) {
                            recycler.layoutManager = LinearLayoutManager(this@MainActivity)
                        }
                        recycler.setPadding(0, recycler.paddingTop, 0, 240.dpToPx())

                        val adapter = TrackAdapter(store, { track ->
                            val idx = allTracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
                            playerManager.playTracks(allTracks, idx)
                        }, { track, anchor -> showTrackMenu(track, anchor) })
                        libraryPlaylistAdapter = null
                        recycler.adapter = adapter

                        if (!showAllLibrarySongs && allTracks.size > 50) {
                            adapter.setFooterButton("Show all (${allTracks.size} songs)") {
                                showAllLibrarySongs = true
                                loadLibraryContent()
                            }
                            adapter.submitList(allTracks.take(50))
                        } else {
                            adapter.setFooterButton(null)
                            adapter.submitList(allTracks)
                        }
                        return@withContext
                    }

                    if (recycler.layoutManager !is GridLayoutManager) {
                        recycler.layoutManager = GridLayoutManager(this@MainActivity, 2)
                    }
                    recycler.setPadding(10.dpToPx(), recycler.paddingTop, 10.dpToPx(), 240.dpToPx())

                    when (selectedTab) {
                        "playlists" -> {
                            // Include Liked Songs as top item
                            val likedTracks = allTracks.filter { it.liked }
                            val plList = mutableListOf<Playlist>()
                            plList.add(
                                Playlist(
                                    id = -1L,
                                    name = "Liked Songs",
                                    trackCount = likedTracks.size,
                                    duration = likedTracks.sumOf { it.duration },
                                    coverTracks = likedTracks.take(4).map { it.id }
                                )
                            )
                            plList.addAll(playlists)

                            val adapter = PlaylistAdapter(
                                store = store,
                                isGrid = true,
                                onPlayClick = { pl -> togglePlaylistPlayback(pl) },
                                onPlaylistClick = { pl ->
                                    if (pl.id == -1L) {
                                        openCollectionDetail("Liked Songs", likedTracks, -1L, isPlaylist = true)
                                    } else {
                                        openCollectionDetail(pl.name, emptyList(), pl.id, isPlaylist = true)
                                    }
                                }
                            )
                            libraryPlaylistAdapter = adapter
                            recycler.adapter = adapter
                            adapter.submitList(plList)
                            val hasTrack = playerManager.currentTrack.value != null
                            adapter.updatePlaybackState(
                                if (hasTrack) playerManager.activePlaylistId.value else 0L,
                                if (hasTrack) playerManager.activeCollectionKey.value else "",
                                playerManager.isPlaying.value && hasTrack
                            )
                        }
                        "albums" -> {
                            val albumMap = allTracks.groupBy { it.album.ifBlank { "Unknown Album" } }
                            val albumList = albumMap.map { (name, tracks) ->
                                Playlist(
                                    id = 0L,
                                    name = name,
                                    trackCount = tracks.size,
                                    duration = tracks.sumOf { it.duration },
                                    coverTracks = tracks.take(1).map { it.id }
                                )
                            }
                            val adapter = PlaylistAdapter(
                                store = store,
                                isGrid = true,
                                onPlayClick = { pl ->
                                    val tracks = albumMap[pl.name] ?: emptyList()
                                    toggleCollectionPlayback("collection:${pl.name}", tracks)
                                },
                                onPlaylistClick = { pl ->
                                    val tracks = albumMap[pl.name] ?: emptyList()
                                    openCollectionDetail(pl.name, tracks, 0L, isPlaylist = false)
                                }
                            )
                            libraryPlaylistAdapter = adapter
                            recycler.adapter = adapter
                            adapter.submitList(albumList)
                            val hasTrack = playerManager.currentTrack.value != null
                            adapter.updatePlaybackState(
                                if (hasTrack) playerManager.activePlaylistId.value else 0L,
                                if (hasTrack) playerManager.activeCollectionKey.value else "",
                                playerManager.isPlaying.value && hasTrack
                            )
                        }
                        "artists" -> {
                            val displayByNorm = linkedMapOf<String, String>()
                            val tracksByNorm = linkedMapOf<String, MutableList<Track>>()
                            val seenPerArtist = hashMapOf<String, HashSet<String>>()

                            for (t in allTracks) {
                                val parts = CanonicalKey.splitArtists(t.artist).ifEmpty { listOf("Unknown Artist") }
                                for (rawArt in parts) {
                                    val cleanArt = rawArt.trim()
                                    if (cleanArt.isEmpty()) continue
                                    val normKey = CanonicalKey.norm(cleanArt)
                                    if (normKey.isEmpty()) continue
                                    displayByNorm.putIfAbsent(normKey, cleanArt)
                                    val seen = seenPerArtist.getOrPut(normKey) { HashSet() }
                                    if (seen.add(t.id)) {
                                        tracksByNorm.getOrPut(normKey) { mutableListOf() }.add(t)
                                    }
                                }
                            }

                            val artistList = tracksByNorm.entries
                                .map { (normKey, tracks) ->
                                    val name = displayByNorm[normKey] ?: normKey
                                    val coverCandidates = tracks.filter { it.hasCover }.ifEmpty { tracks }
                                    Playlist(
                                        id = 0L,
                                        name = name,
                                        trackCount = tracks.size,
                                        duration = tracks.sumOf { it.duration },
                                        coverTracks = coverCandidates.take(4).map { it.id }
                                    )
                                }
                                .sortedBy { it.name.lowercase() }

                            val adapter = PlaylistAdapter(
                                store = store,
                                isGrid = true,
                                onPlayClick = { pl ->
                                    val normKey = CanonicalKey.norm(pl.name)
                                    val tracks = tracksByNorm[normKey] ?: emptyList()
                                    toggleCollectionPlayback("collection:${pl.name}", tracks)
                                },
                                onPlaylistClick = { pl ->
                                    val normKey = CanonicalKey.norm(pl.name)
                                    val tracks = tracksByNorm[normKey] ?: emptyList()
                                    openCollectionDetail(pl.name, tracks, 0L, isPlaylist = false)
                                }
                            )
                            libraryPlaylistAdapter = adapter
                            recycler.adapter = adapter
                            adapter.submitList(artistList)
                            val hasTrack = playerManager.currentTrack.value != null
                            adapter.updatePlaybackState(
                                if (hasTrack) playerManager.activePlaylistId.value else 0L,
                                if (hasTrack) playerManager.activeCollectionKey.value else "",
                                playerManager.isPlaying.value && hasTrack
                            )
                        }
                    }
                }
            }
        }

        refreshLibraryData = {
            updateLibraryQuickSyncVisibility()
            loadLibraryContent()
        }

        fun updateTabs(active: String) {
            selectedTab = active
            val tabs = listOf(
                Pair(tabPlaylists, "playlists"),
                Pair(tabAlbums, "albums"),
                Pair(tabArtists, "artists"),
                Pair(tabMySongs, "my_songs")
            )
            for ((t, key) in tabs) {
                val isAct = key == active
                t.setBackgroundResource(if (isAct) R.drawable.bg_pill_active else R.drawable.bg_pill)
                t.setTextColor(ContextCompat.getColor(this, if (isAct) R.color.black else R.color.text_primary))
            }
            loadLibraryContent()
        }

        tabPlaylists.setOnClickListener { updateTabs("playlists") }
        tabAlbums.setOnClickListener { updateTabs("albums") }
        tabArtists.setOnClickListener { updateTabs("artists") }
        tabMySongs.setOnClickListener { updateTabs("my_songs") }

        loadLibraryContent()
        return view
    }

    // -------------------------------------------------------------
    // Screen 4: Collection Detail (Playlist / Album / Artist)
    // -------------------------------------------------------------

    private fun togglePlaylistPlayback(pl: Playlist) {
        val isThisActive = playerManager.currentTrack.value != null &&
            playerManager.queue.value.isNotEmpty() &&
            (playerManager.activePlaylistId.value == pl.id ||
                playerManager.activeCollectionKey.value == "playlist:${pl.id}")
        if (isThisActive) {
            playerManager.togglePlayPause()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val tracks = if (pl.id == -1L) {
                store.getAllTracks().filter { it.liked }
            } else {
                val tids = store.getPlaylistTrackIds(pl.id)
                tids.mapNotNull { store.getTrackById(it) }
            }
            withContext(Dispatchers.Main) {
                if (tracks.isNotEmpty()) {
                    playerManager.playTracks(
                        tracks = tracks,
                        startIndex = 0,
                        shuffle = false,
                        playlistId = pl.id,
                        collectionKey = "playlist:${pl.id}"
                    )
                } else {
                    Toast.makeText(this@MainActivity, "Playlist is empty", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun toggleCollectionPlayback(collectionKey: String, tracks: List<Track>, playlistId: Long = 0L) {
        val isThisActive = playerManager.currentTrack.value != null &&
            playerManager.queue.value.isNotEmpty() &&
            playerManager.activeCollectionKey.value == collectionKey
        if (isThisActive) {
            playerManager.togglePlayPause()
        } else if (tracks.isNotEmpty()) {
            playerManager.playTracks(
                tracks = tracks,
                startIndex = 0,
                shuffle = false,
                playlistId = playlistId,
                collectionKey = collectionKey
            )
        }
    }

    private fun closeCollectionDetail() {
        isCollectionDetailOpen = false
        overlayContainer.removeAllViews()
        overlayContainer.visibility = View.GONE
    }

    private fun openCollectionDetail(
        title: String,
        initialTracks: List<Track>,
        playlistId: Long = 0L,
        isPlaylist: Boolean = false,
        isArtistRadio: Boolean = false,
        artistName: String = ""
    ) {
        isCollectionDetailOpen = true
        overlayContainer.removeAllViews()
        val view = layoutInflater.inflate(R.layout.screen_collection_detail, overlayContainer, false)
        overlayContainer.addView(view)
        overlayContainer.visibility = View.VISIBLE

        val detailSwipeRefresh = view.findViewById<PullToRefreshLayout>(R.id.detail_swipe_refresh)
        detailSwipeRefresh?.onRefreshListener = {
            lifecycleScope.launch {
                val startMs = System.currentTimeMillis()
                scanLibraryFiles()
                val all = withContext(Dispatchers.IO) { store.getAllTracks() }
                val refreshedTracks = when {
                    playlistId > 0 -> emptyList()
                    playlistId == -1L -> all.filter { it.liked }
                    isArtistRadio && artistName.isNotBlank() -> all.filter {
                        CanonicalKey.hasArtist(it.artist, artistName) || it.displayArtist.equals(artistName, ignoreCase = true)
                    }
                    else -> all.filter {
                        it.album.equals(title, ignoreCase = true) ||
                            it.displayArtist.equals(title, ignoreCase = true) ||
                            CanonicalKey.hasArtist(it.artist, title)
                    }.ifEmpty { initialTracks }
                }
                val elapsed = System.currentTimeMillis() - startMs
                if (elapsed < 350L) delay(350L - elapsed)
                detailSwipeRefresh.isRefreshing = false
                openCollectionDetail(title, refreshedTracks, playlistId, isPlaylist, isArtistRadio, artistName)
            }
        }

        view.findViewById<View>(R.id.detail_back).setOnClickListener {
            closeCollectionDetail()
        }

        val titleView = view.findViewById<TextView>(R.id.detail_title)
        val subView = view.findViewById<TextView>(R.id.detail_subtitle)
        val coverView = view.findViewById<ImageView>(R.id.detail_cover)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_detail_tracks)
        val filterSortBar = view.findViewById<View>(R.id.detail_filter_sort_bar)
        val filterInput = view.findViewById<EditText>(R.id.detail_filter_input)
        val filterClear = view.findViewById<View>(R.id.detail_filter_clear)
        val filterEmpty = view.findViewById<View>(R.id.detail_filter_empty)
        val sortBtn = view.findViewById<View>(R.id.detail_sort_btn)
        val sortIcon = view.findViewById<ImageView>(R.id.detail_sort_icon)
        val sortLabel = view.findViewById<TextView>(R.id.detail_sort_label)
        val btnPlay = view.findViewById<View>(R.id.detail_btn_play)
        val btnPlayIcon = view.findViewById<ImageView>(R.id.detail_btn_play_icon)
        val btnShuffle = view.findViewById<ImageView>(R.id.detail_btn_shuffle)
        recycler.layoutManager = LinearLayoutManager(this)

        titleView.text = title

        val thisCollectionKey = if (playlistId != 0L) "playlist:$playlistId" else "collection:$title"
        fun isThisCollectionActive(): Boolean {
            if (playerManager.currentTrack.value == null || playerManager.queue.value.isEmpty()) return false
            val activeKey = playerManager.activeCollectionKey.value
            if (activeKey.isNotEmpty()) return activeKey == thisCollectionKey
            return playlistId != 0L && playerManager.activePlaylistId.value == playlistId
        }

        val defaultSortLabel = if (isPlaylist) "Custom order" else "Default order"
        sortLabel?.text = defaultSortLabel

        val sortOptions = listOf(
            "" to defaultSortLabel,
            "addedAt:-1" to if (isPlaylist) "Order added (Newest first)" else "Recently added",
            "addedAt:1" to if (isPlaylist) "Order added (First to last)" else "Oldest added",
            "title:1" to "Title (A–Z)",
            "title:-1" to "Title (Z–A)",
            "artist:1" to "Artist (A–Z)",
            "artist:-1" to "Artist (Z–A)",
            "album:1" to "Album (A–Z)",
            "duration:1" to "Duration (Shortest)",
            "duration:-1" to "Duration (Longest)"
        )

        var loadedTracks: List<Track> = initialTracks
        var displayedTracks: List<Track> = initialTracks
        var coverBitmap: android.graphics.Bitmap? = null
        var currentSortKey = ""
        var currentFilter = ""

        val trackAdapter = TrackAdapter(store, { track ->
            val idx = displayedTracks.indexOfFirst { it.id == track.id }
            playerManager.playTracks(
                displayedTracks,
                idx.coerceAtLeast(0),
                playlistId = playlistId,
                collectionKey = thisCollectionKey
            )
        }, { track, anchor ->
            showTrackMenu(track, anchor, playlistId = playlistId)
        })
        recycler.adapter = trackAdapter

        fun applyFilterAndSort() {
            val sorted = when (currentSortKey) {
                "addedAt:-1" -> if (isPlaylist) loadedTracks.reversed() else loadedTracks.sortedByDescending { it.addedAt }
                "addedAt:1" -> if (isPlaylist) loadedTracks else loadedTracks.sortedBy { it.addedAt }
                "title:1" -> loadedTracks.sortedBy { it.displayTitle.lowercase() }
                "title:-1" -> loadedTracks.sortedByDescending { it.displayTitle.lowercase() }
                "artist:1" -> loadedTracks.sortedBy { it.displayArtist.lowercase() }
                "artist:-1" -> loadedTracks.sortedByDescending { it.displayArtist.lowercase() }
                "album:1" -> loadedTracks.sortedBy { it.album.lowercase() }
                "duration:1" -> loadedTracks.sortedBy { it.duration }
                "duration:-1" -> loadedTracks.sortedByDescending { it.duration }
                else -> loadedTracks
            }
            val q = currentFilter.trim()
            displayedTracks = if (q.isEmpty()) {
                sorted
            } else {
                sorted.filter {
                    it.displayTitle.contains(q, ignoreCase = true) ||
                        it.displayArtist.contains(q, ignoreCase = true) ||
                        it.album.contains(q, ignoreCase = true)
                }
            }
            trackAdapter.submitList(displayedTracks)
            filterSortBar?.visibility = if (loadedTracks.isNotEmpty()) View.VISIBLE else View.GONE
            filterEmpty?.visibility = if (loadedTracks.isNotEmpty() && displayedTracks.isEmpty()) View.VISIBLE else View.GONE
        }

        // Keep shuffle button tint synced with playerManager.isShuffle while screen is attached
        val shuffleObserverJob = lifecycleScope.launch {
            playerManager.isShuffle.collectLatest { shuf ->
                val color = ContextCompat.getColor(
                    this@MainActivity,
                    if (shuf) R.color.accent_green else R.color.text_secondary
                )
                btnShuffle?.setColorFilter(color)
            }
        }
        val detailPlayerObserverJob = lifecycleScope.launch {
            combine(
                playerManager.isPlaying,
                playerManager.currentTrack,
                playerManager.activeCollectionKey,
                playerManager.activePlaylistId
            ) { playing, curTrack, _, _ ->
                playing && curTrack != null && isThisCollectionActive()
            }.collectLatest { isCollectionPlaying ->
                btnPlayIcon?.setImageResource(
                    if (isCollectionPlaying) R.drawable.ic_pause else R.drawable.ic_play
                )
            }
        }
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                shuffleObserverJob.cancel()
                detailPlayerObserverJob.cancel()
            }
        })

        filterInput?.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentFilter = s?.toString() ?: ""
                filterClear?.visibility = if (currentFilter.isNotEmpty()) View.VISIBLE else View.GONE
                applyFilterAndSort()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        filterClear?.setOnClickListener {
            filterInput?.setText("")
        }

        sortBtn?.setOnClickListener {
            val sortItems = sortOptions.map { (key, label) ->
                BottomActionSheet.ActionItem(
                    label = label,
                    iconRes = R.drawable.ic_sort,
                    isSelected = key == currentSortKey,
                    onClick = {
                        currentSortKey = key
                        sortLabel?.text = label
                        val active = key.isNotEmpty()
                        sortIcon?.setColorFilter(
                            ContextCompat.getColor(
                                this@MainActivity,
                                if (active) R.color.accent_green else R.color.text_secondary
                            )
                        )
                        sortLabel?.setTextColor(
                            ContextCompat.getColor(
                                this@MainActivity,
                                if (active) R.color.accent_green else R.color.text_primary
                            )
                        )
                        applyFilterAndSort()
                    }
                )
            }
            BottomActionSheet.show(
                context = this,
                title = "Sort tracks",
                subtitle = title,
                fallbackIconRes = R.drawable.ic_sort,
                items = sortItems
            )
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val tracks = if (playlistId > 0) {
                store.getPlaylistTracks(playlistId)
            } else {
                initialTracks
            }
            loadedTracks = tracks

            val totalDur = tracks.sumOf { it.duration }
            val min = (totalDur / 60).toInt()
            val loadedCover = if (tracks.isNotEmpty()) {
                CoverLoader.loadBitmap(store, tracks[0].id, 600)
            } else null

            withContext(Dispatchers.Main) {
                subView.text = "${tracks.size} tracks • $min mins"
                applyFilterAndSort()

                if (loadedCover != null) {
                    coverBitmap = loadedCover
                    coverView.setImageBitmap(coverBitmap)
                }

                btnPlay?.setOnClickListener {
                    if (isThisCollectionActive()) {
                        playerManager.togglePlayPause()
                    } else if (displayedTracks.isNotEmpty()) {
                        playerManager.playTracks(
                            displayedTracks,
                            0,
                            shuffle = false,
                            playlistId = playlistId,
                            collectionKey = thisCollectionKey
                        )
                    }
                }

                btnShuffle?.setOnClickListener {
                    if (displayedTracks.isNotEmpty()) {
                        if (playerManager.isShuffle.value && isThisCollectionActive()) {
                            playerManager.setShuffle(false)
                        } else {
                            playerManager.playTracks(
                                displayedTracks,
                                0,
                                shuffle = true,
                                playlistId = playlistId,
                                collectionKey = thisCollectionKey
                            )
                        }
                    }
                }

                // Add to library / Save as playlist action button
                val btnSaveLib = view.findViewById<View>(R.id.detail_btn_save_library)
                if (isArtistRadio || !isPlaylist) {
                    btnSaveLib?.visibility = View.VISIBLE
                    btnSaveLib?.setOnClickListener {
                        val plName = if (isArtistRadio) "Radio: $title" else title
                        lifecycleScope.launch(Dispatchers.IO) {
                            val newPlId = store.createPlaylist(plName)
                            if (loadedTracks.isNotEmpty()) {
                                store.addToPlaylist(newPlId, loadedTracks.map { it.id })
                            }
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Saved to library as \"$plName\"", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else {
                    btnSaveLib?.visibility = View.GONE
                }

                // Single 3-dots more menu in action bar opening clean BottomActionSheet
                view.findViewById<View>(R.id.detail_btn_more)?.setOnClickListener {
                    val totalMins = (loadedTracks.sumOf { it.duration } / 60).toInt()
                    val actions = mutableListOf<BottomActionSheet.ActionItem>()
                    actions += BottomActionSheet.ActionItem("Play next", R.drawable.ic_skip_next) {
                        if (loadedTracks.isNotEmpty()) {
                            playerManager.playNextInQueue(loadedTracks)
                            Toast.makeText(this@MainActivity, "Playing next: ${loadedTracks.size} songs", Toast.LENGTH_SHORT).show()
                        }
                    }
                    actions += BottomActionSheet.ActionItem("Add to queue", R.drawable.ic_queue) {
                        if (loadedTracks.isNotEmpty()) {
                            playerManager.addToQueue(loadedTracks)
                            Toast.makeText(this@MainActivity, "Added ${loadedTracks.size} songs to queue", Toast.LENGTH_SHORT).show()
                        }
                    }
                    if (isArtistRadio) {
                        actions += BottomActionSheet.ActionItem("Save radio as playlist", R.drawable.ic_playlist_add) {
                            lifecycleScope.launch(Dispatchers.IO) {
                                val newPlId = store.createPlaylist("Radio: $title")
                                if (loadedTracks.isNotEmpty()) {
                                    store.addToPlaylist(newPlId, loadedTracks.map { it.id })
                                }
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Saved \"Radio: $title\" to library!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    if (playlistId > 0) {
                        actions += BottomActionSheet.ActionItem("Rename playlist", R.drawable.ic_edit) {
                            showRenamePlaylistDialog(playlistId, title)
                        }
                    }
                    actions += BottomActionSheet.ActionItem("Export as M3U8", R.drawable.ic_download) {
                        val exportFile = File(getExternalFilesDir(null), "$title.m3u8")
                        if (playlistId > 0) {
                            val ok = store.exportPlaylistToM3u8(playlistId, exportFile)
                            Toast.makeText(this@MainActivity, if (ok) "Exported: ${exportFile.name}" else "Export failed", Toast.LENGTH_SHORT).show()
                        } else {
                            try {
                                exportFile.parentFile?.mkdirs()
                                java.io.FileWriter(exportFile).use { writer ->
                                    writer.write("#EXTM3U\n")
                                    for (t in loadedTracks) {
                                        writer.write("#EXTINF:${t.duration.toInt()},${t.artist} - ${t.title}\n")
                                        writer.write("${t.relPath}\n")
                                    }
                                }
                                Toast.makeText(this@MainActivity, "Exported: ${exportFile.name}", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    if (playlistId > 0) {
                        actions += BottomActionSheet.ActionItem("Delete playlist", R.drawable.ic_close, isDestructive = true) {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Delete Playlist")
                                .setMessage("Are you sure you want to delete '$title'?")
                                .setPositiveButton("Delete") { _, _ ->
                                    store.deletePlaylist(playlistId)
                                    closeCollectionDetail()
                                    refreshLibraryData?.invoke()
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                    BottomActionSheet.show(
                        context = this@MainActivity,
                        title = title,
                        subtitle = "${loadedTracks.size} tracks • $totalMins mins",
                        coverBitmap = coverBitmap,
                        fallbackIconRes = R.drawable.ic_library,
                        items = actions
                    )
                }
            }

            // Playlist-specific search bar & suggestions
            if (isPlaylist && playlistId > 0) {
                val allTracks = store.getAllTracks()
                val plMap = store.getAllPlaylistTracks()
                var suggestionSeed = 0
                var currentSuggestions = RecommendationEngine.recommendForPlaylist(tracks, allTracks, suggestionSeed, 6, plMap)

                withContext(Dispatchers.Main) {
                    val suggContainer = view.findViewById<View>(R.id.detail_suggestions_container)
                    suggContainer?.visibility = View.VISIBLE
                    val suggRecycler = view.findViewById<RecyclerView>(R.id.recycler_detail_suggestions)
                    val txtSuggTitle = view.findViewById<TextView>(R.id.detail_suggestions_title)
                    val btnSuggRefresh = view.findViewById<ImageView>(R.id.btn_suggestions_refresh)
                    val searchInput = view.findViewById<EditText>(R.id.detail_search_input)

                    suggRecycler?.layoutManager = LinearLayoutManager(this@MainActivity)
                    val suggAdapter = TrackAdapter(
                        store,
                        onTrackClick = { t -> playerManager.playTracks(listOf(t), 0) },
                        onMenuClick = { track, anchor -> showTrackMenu(track, anchor, playlistId = playlistId) },
                        onAddClick = { track, btnAdd ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                store.addToPlaylist(playlistId, listOf(track.id))
                                val updatedTids = store.getPlaylistTrackIds(playlistId)
                                val updatedTracks = updatedTids.mapNotNull { store.getTrackById(it) }
                                loadedTracks = updatedTracks
                                withContext(Dispatchers.Main) {
                                    btnAdd.text = "✓ Added"
                                    btnAdd.isEnabled = false
                                    btnAdd.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
                                    applyFilterAndSort()
                                    subView.text = "${updatedTracks.size} tracks • ${(updatedTracks.sumOf { it.duration } / 60).toInt()} mins"
                                    Toast.makeText(this@MainActivity, "Added \"${track.displayTitle}\" to playlist", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    )
                    suggRecycler?.adapter = suggAdapter
                    suggAdapter.submitList(currentSuggestions)

                    btnSuggRefresh?.setOnClickListener {
                        btnSuggRefresh.animate().rotationBy(360f).setDuration(500).start()
                        suggestionSeed++
                        lifecycleScope.launch(Dispatchers.IO) {
                            val latestAll = store.getAllTracks()
                            val latestPlMap = store.getAllPlaylistTracks()
                            val refreshed = RecommendationEngine.recommendForPlaylist(
                                loadedTracks,
                                latestAll,
                                suggestionSeed,
                                6,
                                latestPlMap
                            )
                            currentSuggestions = refreshed
                            withContext(Dispatchers.Main) {
                                if (searchInput?.text?.toString()?.trim().isNullOrEmpty()) {
                                    txtSuggTitle?.text = "Suggested songs"
                                    suggAdapter.submitList(currentSuggestions)
                                }
                            }
                        }
                    }

                    searchInput?.addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(s: android.text.Editable?) {
                            val q = s?.toString()?.trim() ?: ""
                            if (q.isBlank()) {
                                txtSuggTitle?.text = "Suggested songs"
                                btnSuggRefresh?.visibility = View.VISIBLE
                                suggAdapter.submitList(currentSuggestions)
                            } else {
                                btnSuggRefresh?.visibility = View.GONE
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val curIds = loadedTracks.map { it.id }.toSet()
                                    val matches = allTracks.filter {
                                        !curIds.contains(it.id) &&
                                        (it.displayTitle.contains(q, ignoreCase = true) || it.displayArtist.contains(q, ignoreCase = true))
                                    }.take(20)
                                    withContext(Dispatchers.Main) {
                                        txtSuggTitle?.text = "Search results (${matches.size})"
                                        suggAdapter.submitList(matches)
                                    }
                                }
                            }
                        }
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })
                }
            }

            // Online recommendations (for Artist Radios and normal Playlists when online features enabled)
            val settings = store.loadSettings()
            if (settings.enableOnlineFeatures && (isArtistRadio || isPlaylist)) {
                val seedArtist = if (isArtistRadio && artistName.isNotBlank()) {
                    artistName
                } else {
                    tracks.map { it.displayArtist }
                        .filter { it.isNotBlank() && it != "Unknown Artist" }
                        .groupingBy { it }.eachCount()
                        .maxByOrNull { it.value }?.key ?: ""
                }

                withContext(Dispatchers.Main) {
                    val onlineContainer = view.findViewById<View>(R.id.detail_online_radio_container)
                    val onlineRecycler = view.findViewById<RecyclerView>(R.id.recycler_detail_online_radio)
                    val txtLoading = view.findViewById<View>(R.id.txt_radio_loading)
                    val btnDownloadAll = view.findViewById<View>(R.id.btn_radio_download_all)
                    val btnRefresh = view.findViewById<ImageView>(R.id.btn_radio_refresh)
                    val txtSectionTitle = view.findViewById<TextView>(R.id.txt_online_section_title)

                    if (isArtistRadio) {
                        txtSectionTitle?.text = "Artist radio discoveries"
                    } else {
                        txtSectionTitle?.text = "Online recommendations"
                    }

                    onlineContainer?.visibility = View.VISIBLE
                    onlineRecycler?.layoutManager = LinearLayoutManager(this@MainActivity)
                    (onlineRecycler?.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false

                    val targetPlId = if (isPlaylist && playlistId > 0) playlistId else null
                    val onlineAdapter = com.vibemusic.nativeapp.ui.OnlineTrackListAdapter(
                        lifecycleScope,
                        onTrackClick = { item ->
                            val url = "https://www.youtube.com/watch?v=${item.ytId}"
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            try {
                                startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(this@MainActivity, "Could not open link", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDownloadClick = { item ->
                            ytdlpManager.enqueueDownload(
                                url = "https://www.youtube.com/watch?v=${item.ytId}",
                                targetPlaylistId = targetPlId,
                                title = item.title
                            )
                            Toast.makeText(this@MainActivity, "Downloading: ${item.title}", Toast.LENGTH_SHORT).show()
                        },
                        onAddClick = { item, btnAdd ->
                            if (item.isOwned && playlistId > 0) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val all = store.getAllTracks()
                                    val match = all.firstOrNull { it.ytId == item.ytId || it.displayTitle.equals(item.title, ignoreCase = true) }
                                    if (match != null) {
                                        store.addToPlaylist(playlistId, listOf(match.id))
                                        val updatedTids = store.getPlaylistTrackIds(playlistId)
                                        val updatedTracks = updatedTids.mapNotNull { store.getTrackById(it) }
                                        loadedTracks = updatedTracks
                                        withContext(Dispatchers.Main) {
                                            btnAdd.text = "✓ Added"
                                            btnAdd.isEnabled = false
                                            applyFilterAndSort()
                                            subView.text = "${updatedTracks.size} tracks • ${(updatedTracks.sumOf { it.duration } / 60).toInt()} mins"
                                            Toast.makeText(this@MainActivity, "Added to playlist!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            } else {
                                ytdlpManager.enqueueDownload(
                                    url = "https://www.youtube.com/watch?v=${item.ytId}",
                                    targetPlaylistId = targetPlId,
                                    title = item.title
                                )
                                btnAdd.text = "Queued"
                                btnAdd.isEnabled = false
                                Toast.makeText(this@MainActivity, "Downloading: ${item.title}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    onlineAdapter.updateDownloadJobs(ytdlpManager.jobs.value)
                    onlineRecycler?.adapter = onlineAdapter

                    val onlineJobsObserverJob = lifecycleScope.launch {
                        ytdlpManager.jobs.collectLatest { jobs ->
                            onlineAdapter.updateDownloadJobs(jobs)
                        }
                    }
                    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {}
                        override fun onViewDetachedFromWindow(v: View) {
                            onlineJobsObserverJob.cancel()
                        }
                    })

                    fun loadRadioDiscoveries(forceRefresh: Boolean = false) {
                        txtLoading?.visibility = View.VISIBLE
                        lifecycleScope.launch(Dispatchers.IO) {
                            val discoveries = if (isArtistRadio) {
                                ytdlpManager.discoverForArtist(
                                    artistName = artistName.ifBlank { seedArtist },
                                    forceRefresh = forceRefresh
                                )
                            } else {
                                ytdlpManager.discoverForPlaylist(
                                    playlistId = playlistId,
                                    playlistTracks = tracks,
                                    forceRefresh = forceRefresh
                                )
                            }
                            withContext(Dispatchers.Main) {
                                txtLoading?.visibility = View.GONE
                                onlineAdapter.submitList(discoveries)
                            }
                        }
                    }

                    btnDownloadAll?.setOnClickListener {
                        val items = onlineAdapter.getItems().filter { !it.isOwned }
                        if (items.isNotEmpty()) {
                            for (item in items) {
                                ytdlpManager.enqueueDownload(
                                    url = "https://www.youtube.com/watch?v=${item.ytId}",
                                    targetPlaylistId = targetPlId,
                                    title = item.title
                                )
                            }
                            Toast.makeText(this@MainActivity, "Downloading ${items.size} songs", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@MainActivity, "No new tracks to download", Toast.LENGTH_SHORT).show()
                        }
                    }

                    btnRefresh?.setOnClickListener {
                        btnRefresh.animate().rotationBy(360f).setDuration(500).start()
                        loadRadioDiscoveries(forceRefresh = true)
                    }

                    loadRadioDiscoveries()
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Screen 5: Downloads Screen
    // -------------------------------------------------------------

    private fun createDownloadsView(prefilledUrl: String = ""): View {
        val view = layoutInflater.inflate(R.layout.screen_downloads, tabsContainer, false)

        val swipeRefresh = view.findViewById<PullToRefreshLayout>(R.id.downloads_swipe_refresh)
        swipeRefresh?.onRefreshListener = {
            refreshTab("downloads", scanFiles = true, scrollToTop = false)
        }

        val ytmStatusText = view.findViewById<TextView>(R.id.dl_ytm_account_status)
        val btnYtmLogout = view.findViewById<TextView>(R.id.dl_btn_ytm_logout)
        val btnYtmImport = view.findViewById<TextView>(R.id.dl_btn_ytm_import)
        val btnYtmSync = view.findViewById<TextView>(R.id.dl_btn_ytm_sync)

        fun updateYtMusicStatusUi() {
            val loggedIn = ytdlpManager.authManager.isLoggedIn()
            if (loggedIn) {
                ytmStatusText?.text = "Connected • Import or sync Liked Songs, Playlists & Albums"
                btnYtmLogout?.visibility = View.VISIBLE
            } else {
                ytmStatusText?.text = "Sign in to import your Liked Songs, Playlists & Albums"
                btnYtmLogout?.visibility = View.GONE
            }
        }

        updateYtMusicStatusUi()

        btnYtmImport?.setOnClickListener {
            if (!ytdlpManager.authManager.isLoggedIn()) {
                showYtMusicLoginDialog {
                    updateYtMusicStatusUi()
                    showYtMusicImportDialog()
                }
            } else {
                showYtMusicImportDialog()
            }
        }

        btnYtmSync?.setOnClickListener {
            if (!ytdlpManager.authManager.isLoggedIn()) {
                showYtMusicLoginDialog {
                    updateYtMusicStatusUi()
                    triggerYtMusicSync()
                }
            } else {
                triggerYtMusicSync()
            }
        }

        btnYtmLogout?.setOnClickListener {
            ytdlpManager.authManager.logout()
            updateYtMusicStatusUi()
            Toast.makeText(this, "Signed out of YouTube Music", Toast.LENGTH_SHORT).show()
        }

        val input = view.findViewById<EditText>(R.id.dl_input_url)
        val modeTrack = view.findViewById<TextView>(R.id.dl_mode_track)
        val modePlaylist = view.findViewById<TextView>(R.id.dl_mode_playlist)
        val btnStart = view.findViewById<Button>(R.id.dl_btn_start)
        val btnUpdate = view.findViewById<TextView>(R.id.dl_btn_update_ytdlp)
        val statusMsg = view.findViewById<TextView>(R.id.dl_status_msg)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_download_jobs)

        if (prefilledUrl.isNotEmpty()) {
            input.setText(prefilledUrl)
        }

        var downloadMode = "track"

        fun updateMode(mode: String) {
            downloadMode = mode
            val isTrack = mode == "track"
            modeTrack.setBackgroundResource(if (isTrack) R.drawable.bg_pill_active else R.drawable.bg_pill)
            modeTrack.setTextColor(ContextCompat.getColor(this, if (isTrack) R.color.black else R.color.text_primary))
            modePlaylist.setBackgroundResource(if (!isTrack) R.drawable.bg_pill_active else R.drawable.bg_pill)
            modePlaylist.setTextColor(ContextCompat.getColor(this, if (!isTrack) R.color.black else R.color.text_primary))
        }

        modeTrack.setOnClickListener { updateMode("track") }
        modePlaylist.setOnClickListener { updateMode("playlist") }

        btnStart.setOnClickListener {
            val url = input.text.toString().trim()
            if (url.isNotEmpty()) {
                ytdlpManager.enqueueDownload(url, downloadMode)
                input.setText("")
                Toast.makeText(this, "Queued download ($downloadMode)", Toast.LENGTH_SHORT).show()
            }
        }

        btnUpdate.setOnClickListener {
            ytdlpManager.updateYtdlp()
        }

        view.findViewById<View>(R.id.dl_btn_scan_now)?.setOnClickListener {
            Toast.makeText(this, "Scanning library...", Toast.LENGTH_SHORT).show()
            refreshTab("downloads", scanFiles = true, scrollToTop = false)
        }

        view.findViewById<View>(R.id.dl_btn_clear_history)?.setOnClickListener {
            ytdlpManager.clearHistory()
            Toast.makeText(this, "Download history cleared", Toast.LENGTH_SHORT).show()
        }

        recycler.layoutManager = LinearLayoutManager(this)
        (recycler.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        val jobAdapter = DownloadJobAdapter(
            onCancelClick = { job ->
                if (job.status == "downloading" || job.status == "pending") {
                    ytdlpManager.cancelJob(job.id)
                    Toast.makeText(this@MainActivity, "Cancelled download", Toast.LENGTH_SHORT).show()
                } else {
                    ytdlpManager.removeJob(job.id)
                }
            },
            onRetryClick = { job ->
                ytdlpManager.retryJob(job.id)
                Toast.makeText(this@MainActivity, "Retrying download", Toast.LENGTH_SHORT).show()
            }
        )
        recycler.adapter = jobAdapter

        refreshDownloadsData = {
            updateYtMusicStatusUi()
            jobAdapter.submitList(ytdlpManager.jobs.value)
            statusMsg.text = ytdlpManager.statusMessage.value
        }

        lifecycleScope.launch {
            ytdlpManager.jobs.collectLatest { list ->
                jobAdapter.submitList(list)
            }
        }

        lifecycleScope.launch {
            ytdlpManager.statusMessage.collectLatest { msg ->
                statusMsg.text = msg
            }
        }

        lifecycleScope.launch {
            ytdlpManager.isSyncingYtMusic.collectLatest { syncing ->
                btnYtmSync?.text = if (syncing) "Syncing..." else "Sync with YT Music"
                btnYtmSync?.isEnabled = !syncing
                btnYtmImport?.isEnabled = !syncing
            }
        }

        return view
    }

    private fun triggerYtMusicSync() {
        Toast.makeText(this, "Syncing with YouTube Music...", Toast.LENGTH_SHORT).show()
        ytdlpManager.syncYtMusicLibrary { summary ->
            Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
            refreshLibraryData?.invoke()
        }
    }

    private fun showYtMusicLoginDialog(onConnected: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg_primary))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.bg_surface))
        }

        val titleView = TextView(this).apply {
            text = "Sign in to YouTube Music"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val doneBtn = TextView(this).apply {
            text = "Done"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.black))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_pill_active)
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8.dpToPx() }
        }

        val closeBtn = TextView(this).apply {
            text = "Cancel"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 12f
            setBackgroundResource(R.drawable.bg_thin_button)
            setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
        }

        header.addView(titleView)
        header.addView(doneBtn)
        header.addView(closeBtn)

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.72).toInt()
            )
            isFocusable = true
            isFocusableInTouchMode = true
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        container.addView(header)
        container.addView(webView)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        var handled = false
        fun tryFinishLogin(requireMusicHost: Boolean, url: String?) {
            if (handled) return
            val onMusicHost = url?.contains("music.youtube.com") == true
            if (requireMusicHost && !onMusicHost) return
            if (ytdlpManager.authManager.extractAndSaveCookies()) {
                handled = true
                dialog.dismiss()
                Toast.makeText(this, "Connected to YouTube Music", Toast.LENGTH_SHORT).show()
                onConnected()
            } else if (!requireMusicHost) {
                Toast.makeText(this, "Please finish signing in first", Toast.LENGTH_SHORT).show()
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                tryFinishLogin(requireMusicHost = true, url = url)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                tryFinishLogin(requireMusicHost = true, url = url)
            }
        }

        doneBtn.setOnClickListener { tryFinishLogin(requireMusicHost = false, url = webView.url) }
        closeBtn.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener {
            webView.stopLoading()
            webView.destroy()
        }

        dialog.show()
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        webView.loadUrl("https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&continue=https%3A%2F%2Fmusic.youtube.com%2F")
    }

    private fun showYtMusicImportDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ytm_import, null)
        val subtitleView = dialogView.findViewById<TextView>(R.id.ytm_import_subtitle)
        val filterAll = dialogView.findViewById<TextView>(R.id.ytm_filter_all)
        val filterPlaylists = dialogView.findViewById<TextView>(R.id.ytm_filter_playlists)
        val filterAlbums = dialogView.findViewById<TextView>(R.id.ytm_filter_albums)
        val btnSelectAll = dialogView.findViewById<TextView>(R.id.ytm_btn_select_all)
        val loadingContainer = dialogView.findViewById<View>(R.id.ytm_loading_container)
        val emptyText = dialogView.findViewById<TextView>(R.id.ytm_empty_text)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.ytm_collections_recycler)
        val btnCancel = dialogView.findViewById<TextView>(R.id.ytm_btn_cancel)
        val btnConfirm = dialogView.findViewById<TextView>(R.id.ytm_btn_confirm)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        var adapterRef: YtMusicCollectionAdapter? = null
        fun updateConfirmButton(selectedCount: Int) {
            btnConfirm.text = if (selectedCount > 0) "Import Selected ($selectedCount)" else "Import Selected"
            btnConfirm.alpha = if (selectedCount > 0) 1.0f else 0.5f
            btnConfirm.isEnabled = selectedCount > 0
            btnSelectAll.text = if (adapterRef?.areAllVisibleSelected() == true) {
                "Deselect All"
            } else {
                "Select All"
            }
        }

        val adapter = YtMusicCollectionAdapter(lifecycleScope) { count ->
            updateConfirmButton(count)
        }
        adapterRef = adapter
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        updateConfirmButton(0)

        val filterPills = listOf(
            Triple(filterAll, "all", "All"),
            Triple(filterPlaylists, "playlists", "Playlists"),
            Triple(filterAlbums, "albums", "Albums")
        )
        for ((pill, key, _) in filterPills) {
            pill.setOnClickListener {
                for ((p, k, _) in filterPills) {
                    val active = k == key
                    p.setBackgroundResource(if (active) R.drawable.bg_pill_active else R.drawable.bg_pill)
                    p.setTextColor(ContextCompat.getColor(this, if (active) R.color.black else R.color.text_primary))
                }
                adapter.applyFilter(key)
                btnSelectAll.text = if (adapter.areAllVisibleSelected()) "Deselect All" else "Select All"
            }
        }

        btnSelectAll.setOnClickListener {
            adapter.toggleSelectAllVisible()
            btnSelectAll.text = if (adapter.areAllVisibleSelected()) "Deselect All" else "Select All"
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val selected = adapter.getSelectedCollections()
            if (selected.isEmpty()) return@setOnClickListener
            dialog.dismiss()
            Toast.makeText(
                this,
                "Importing ${selected.size} YouTube Music collection(s)...",
                Toast.LENGTH_SHORT
            ).show()
            ytdlpManager.importYtMusicCollections(selected) { summary ->
                Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
                refreshLibraryData?.invoke()
            }
        }

        dialog.show()

        lifecycleScope.launch {
            val collections = ytdlpManager.ytMusicClient.fetchUserLibrary()
            loadingContainer.visibility = View.GONE
            if (collections.isEmpty()) {
                emptyText.visibility = View.VISIBLE
                recycler.visibility = View.GONE
                subtitleView.text = "Could not load library"
            } else {
                emptyText.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                val savedDefaults = store.loadSyncedYtCollections() + "LM"
                adapter.submitCollections(collections, defaultSelectedIds = savedDefaults)
                val plCount = collections.count { it.type == YtCollectionType.PLAYLIST }
                val alCount = collections.count { it.type == YtCollectionType.ALBUM }
                subtitleView.text = "Found Liked Music, $plCount playlist(s) & $alCount saved album(s)"
            }
        }
    }

    // -------------------------------------------------------------
    // Screen 6: Settings Screen
    // -------------------------------------------------------------

    private fun createSettingsView(): View {
        val view = layoutInflater.inflate(R.layout.screen_settings, tabsContainer, false)

        val swipeRefresh = view.findViewById<PullToRefreshLayout>(R.id.settings_swipe_refresh)
        swipeRefresh?.onRefreshListener = {
            refreshTab("settings", scanFiles = true, scrollToTop = false)
        }

        val settings = store.loadSettings()
        val containerDirs = view.findViewById<LinearLayout>(R.id.settings_dirs_container)
        val btnAddFolder = view.findViewById<View>(R.id.settings_btn_add_folder)
        val btnRescan = view.findViewById<View>(R.id.settings_btn_rescan)
        val btnAllFiles = view.findViewById<View>(R.id.settings_btn_all_files)
        val hiddenBtn = view.findViewById<View>(R.id.settings_btn_hidden_songs)
        val hiddenCountText = view.findViewById<TextView>(R.id.settings_hidden_count_text)
        val storageUsedText = view.findViewById<TextView>(R.id.settings_storage_used_text)
        val storageDetailText = view.findViewById<TextView>(R.id.settings_storage_detail_text)
        val storageBar = view.findViewById<ProgressBar>(R.id.settings_storage_bar)
        val eqPresetLabel = view.findViewById<TextView>(R.id.settings_eq_preset_label)
        val versionText = view.findViewById<TextView>(R.id.settings_version_text)
        genreSummaryTextView = view.findViewById(R.id.settings_genre_summary_text)
        genreModeTextView = view.findViewById(R.id.settings_genre_mode_text)
        syncSummaryTextView = view.findViewById(R.id.settings_sync_summary_text)
        updateSyncSummaryLabel()

        view.findViewById<View>(R.id.settings_btn_open_sync)?.setOnClickListener {
            showSyncScreen(autoQuickSync = false)
        }

        val pkgVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0.7.0"
        } catch (_: Exception) {
            "0.7.0"
        }
        versionText?.text = "v$pkgVersion"
        updateGenreSettingsSummaryViews(genreDetector.refreshStatus())

        fun refreshDirsList() {
            containerDirs.removeAllViews()
            val currentSettings = store.loadSettings()
            for (dir in currentSettings.musicDirs) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 4.dpToPx(), 0, 4.dpToPx()) }
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(10.dpToPx(), 8.dpToPx(), 10.dpToPx(), 8.dpToPx())
                    setBackgroundResource(R.drawable.bg_thin_button)
                }

                val pathText = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text = dir
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    textSize = 12f
                    maxLines = 2
                }

                val deleteBtn = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(28.dpToPx(), 28.dpToPx())
                    setPadding(4.dpToPx(), 4.dpToPx(), 4.dpToPx(), 4.dpToPx())
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setColorFilter(ContextCompat.getColor(this@MainActivity, R.color.accent_red))
                    setOnClickListener {
                        val newDirs = store.loadSettings().musicDirs.filter { it != dir }
                        store.saveSettings(store.loadSettings().copy(musicDirs = newDirs))
                        refreshDirsList()
                        Toast.makeText(this@MainActivity, "Folder removed", Toast.LENGTH_SHORT).show()
                    }
                }

                row.addView(pathText)
                row.addView(deleteBtn)
                containerDirs.addView(row)
            }
        }

        fun refreshHiddenCountAndLabels() {
            val cur = store.loadSettings()
            val eqStatus = if (cur.eqEnabled) "On" else "Off"
            eqPresetLabel?.text = "$eqStatus • Preset: ${cur.eqPreset}"
            lifecycleScope.launch(Dispatchers.IO) {
                val count = store.getHiddenTrackIds().size
                val allTracks = store.getAllTracks(includeHidden = true)
                val seenPaths = HashSet<String>()
                var musicBytes = 0L
                var uniqueTrackCount = 0
                for (t in allTracks) {
                    val path = t.resolvedPath.ifEmpty { store.resolvePath(t.rootKey, t.relPath) }
                    val key = path.ifEmpty { t.id }
                    if (seenPaths.add(key)) {
                        uniqueTrackCount++
                        val fileLen = if (t.fileSize > 0L) {
                            t.fileSize
                        } else if (path.isNotEmpty()) {
                            val f = File(path)
                            if (f.exists()) f.length() else 0L
                        } else {
                            0L
                        }
                        musicBytes += fileLen
                    }
                }

                val coversDir = File(filesDir, "covers")
                var coversBytes = 0L
                if (coversDir.exists() && coversDir.isDirectory) {
                    coversDir.listFiles()?.forEach { f ->
                        if (f.isFile) coversBytes += f.length()
                    }
                }

                var totalDeviceBytes = 0L
                var availDeviceBytes = 0L
                try {
                    val stat = StatFs(Environment.getExternalStorageDirectory().absolutePath)
                    totalDeviceBytes = stat.totalBytes
                    availDeviceBytes = stat.availableBytes
                } catch (_: Exception) {}

                val totalAppMusicBytes = musicBytes + coversBytes
                val barMax = 1000
                val progress = if (totalDeviceBytes > 0L) {
                    ((totalAppMusicBytes.toDouble() / totalDeviceBytes.toDouble()) * barMax)
                        .toInt()
                        .let { p -> if (totalAppMusicBytes > 0L && p == 0) 8 else p }
                        .coerceIn(0, barMax)
                } else {
                    0
                }

                withContext(Dispatchers.Main) {
                    hiddenCountText?.text = "$count hidden tracks • Tap to manage"
                    storageUsedText?.text = formatBytes(musicBytes)
                    storageBar?.max = barMax
                    storageBar?.progress = progress
                    val coversPart = if (coversBytes > 0L) " • ${formatBytes(coversBytes)} covers" else ""
                    val freePart = if (totalDeviceBytes > 0L) {
                        " • ${formatBytes(availDeviceBytes)} free of ${formatBytes(totalDeviceBytes)}"
                    } else {
                        ""
                    }
                    storageDetailText?.text = "$uniqueTrackCount songs$coversPart$freePart"
                }
            }
        }

        refreshSettingsData = {
            refreshDirsList()
            refreshHiddenCountAndLabels()
            updateGenreSettingsSummaryViews(genreDetector.refreshStatus())
            updateSyncSummaryLabel()
        }

        refreshDirsList()
        refreshHiddenCountAndLabels()

        btnAddFolder.setOnClickListener {
            val presets = arrayOf(
                "/storage/emulated/0/Music",
                "/storage/emulated/0/Music/VibeMusic",
                "/storage/emulated/0/Download",
                "/storage/emulated/0/Documents",
                "Custom path..."
            )
            AlertDialog.Builder(this)
                .setTitle("Add Music Folder")
                .setItems(presets) { _, which ->
                    if (which == presets.lastIndex) {
                        onCustomPathSelected = { selectedPath ->
                            val current = store.loadSettings()
                            val updated = (current.musicDirs + selectedPath).distinct()
                            store.saveSettings(current.copy(musicDirs = updated))
                            refreshDirsList()
                            Toast.makeText(this, "Added $selectedPath", Toast.LENGTH_SHORT).show()
                            refreshTab("settings", scanFiles = true, scrollToTop = false)
                        }
                        launchCustomPathPicker()
                    } else {
                        val selected = presets[which]
                        val current = store.loadSettings()
                        val updated = (current.musicDirs + selected).distinct()
                        store.saveSettings(current.copy(musicDirs = updated))
                        refreshDirsList()
                        Toast.makeText(this, "Added $selected", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnRescan.setOnClickListener {
            Toast.makeText(this, "Scanning library...", Toast.LENGTH_SHORT).show()
            refreshTab("settings", scanFiles = true, scrollToTop = false)
        }

        btnAllFiles.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val fallback = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(fallback)
                }
            } else {
                Toast.makeText(this, "All Files Access is only needed on Android 11+", Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<View>(R.id.settings_btn_genre_detection)?.setOnClickListener {
            showGenreSettingsScreen()
        }

        view.findViewById<View>(R.id.settings_btn_home_sections).setOnClickListener {
            showHomeCustomizeDialog()
        }

        view.findViewById<View>(R.id.settings_btn_open_eq).setOnClickListener {
            showEqualizerDialog()
        }

        val switchReplayGain = view.findViewById<SwitchCompat>(R.id.settings_switch_replaygain)
        switchReplayGain.isChecked = settings.replayGain
        switchReplayGain.setOnCheckedChangeListener { _, isChecked ->
            store.saveSettings(store.loadSettings().copy(replayGain = isChecked))
        }

        val switchAutoUpdate = view.findViewById<SwitchCompat>(R.id.settings_switch_autoupdate)
        switchAutoUpdate.isChecked = settings.autoUpdateYtdlp
        switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            store.saveSettings(store.loadSettings().copy(autoUpdateYtdlp = isChecked))
        }

        val switchOnlineFeatures = view.findViewById<SwitchCompat>(R.id.settings_switch_online_features)
        switchOnlineFeatures?.isChecked = settings.enableOnlineFeatures
        switchOnlineFeatures?.setOnCheckedChangeListener { _, isChecked ->
            store.saveSettings(store.loadSettings().copy(enableOnlineFeatures = isChecked))
            tabViews["home"]?.let { applyHomeSectionOrder(it) }
            Toast.makeText(this, if (isChecked) "Online features enabled" else "Online features disabled (Offline Mode)", Toast.LENGTH_SHORT).show()
        }

        // Audio format chips
        val fmtM4a = view.findViewById<TextView>(R.id.fmt_m4a)
        val fmtOpus = view.findViewById<TextView>(R.id.fmt_opus)
        val fmtMp3 = view.findViewById<TextView>(R.id.fmt_mp3)
        val fmtBest = view.findViewById<TextView>(R.id.fmt_best)
        val fmtList = listOf(
            Pair(fmtM4a, "m4a"),
            Pair(fmtOpus, "opus"),
            Pair(fmtMp3, "mp3"),
            Pair(fmtBest, "best")
        )

        fun updateFmt(fmt: String) {
            for ((t, key) in fmtList) {
                val isAct = key == fmt
                t.setBackgroundResource(if (isAct) R.drawable.bg_pill_active else R.drawable.bg_pill)
                t.setTextColor(ContextCompat.getColor(this, if (isAct) R.color.black else R.color.text_primary))
            }
            store.saveSettings(store.loadSettings().copy(downloadFormat = fmt))
        }

        for ((t, key) in fmtList) {
            t.setOnClickListener { updateFmt(key) }
        }
        updateFmt(settings.downloadFormat)

        hiddenBtn?.setOnClickListener {
            showHiddenSongsScreen()
        }

        return view
    }

    private fun updateLibraryQuickSyncVisibility() {
        val btn = libQuickSyncBtn ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val synced = store.hasSyncedBefore()
            withContext(Dispatchers.Main) {
                btn.visibility = if (synced) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateSyncSummaryLabel() {
        val tv = syncSummaryTextView ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val peers = store.listTrustedSyncPeers()
            val text = if (peers.isEmpty()) {
                "Transfer songs, playlists, favorites & play counts directly over Wi‑Fi"
            } else {
                val p = peers.first()
                val whenStr = if (p.lastSyncAt > 0L) {
                    val diff = ((System.currentTimeMillis() / 1000L) - p.lastSyncAt).coerceAtLeast(0L)
                    when {
                        diff < 60 -> "just now"
                        diff < 3600 -> "${diff / 60}m ago"
                        diff < 86400 -> "${diff / 3600}h ago"
                        else -> "${diff / 86400}d ago"
                    }
                } else "paired"
                "Paired with ${p.deviceName.ifEmpty { "PC" }} • $whenStr"
            }
            withContext(Dispatchers.Main) {
                tv.text = text
            }
        }
    }

    private fun showIncomingSyncPairDialog(prompt: SyncPairPrompt) {
        activePairDialog?.dismiss()
        val pinFormatted = if (prompt.pin.length == 6) {
            "${prompt.pin.substring(0, 3)} ${prompt.pin.substring(3)}"
        } else {
            prompt.pin
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = 20.dpToPx()
            setPadding(pad, 12.dpToPx(), pad, 4.dpToPx())
        }
        val desc = TextView(this).apply {
            val countPart = if (prompt.trackCount > 0) " (${prompt.trackCount} songs)" else ""
            text = "${prompt.deviceName}$countPart wants to sync with your library.\n\nVerify this 6-digit PIN matches:"
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 13f
        }
        val pinTxt = TextView(this).apply {
            text = pinFormatted
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
            textSize = 28f
            typeface = android.graphics.Typeface.MONOSPACE
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 14.dpToPx(), 0, 14.dpToPx())
        }
        val trustCb = CheckBox(this).apply {
            text = "Trust this device automatically (1-tap sync next time)"
            isChecked = prompt.trustedDefault
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 13f
        }
        box.addView(desc)
        box.addView(pinTxt)
        box.addView(trustCb)

        var handled = false
        val dialog = AlertDialog.Builder(this)
            .setTitle("LAN Sync Request")
            .setView(box)
            .setPositiveButton("Accept & Sync") { _, _ ->
                handled = true
                syncManager.respondPair(prompt.pairId, accept = true, trust = trustCb.isChecked)
            }
            .setNegativeButton("Decline") { _, _ ->
                handled = true
                syncManager.respondPair(prompt.pairId, accept = false, trust = false)
            }
            .setOnDismissListener {
                if (!handled) {
                    syncManager.respondPair(prompt.pairId, accept = false, trust = false)
                }
                if (activePairDialog === it) {
                    activePairDialog = null
                }
            }
            .create()
        activePairDialog = dialog
        dialog.show()
    }

    private fun closeSyncScreen() {
        isSyncScreenOpen = false
        syncStatusCollectJob?.cancel()
        syncPeersCollectJob?.cancel()
        syncStatusCollectJob = null
        syncPeersCollectJob = null
        syncManager.stopDiscovery()
        overlayContainer.removeAllViews()
        overlayContainer.visibility = View.GONE
        refreshSettingsData?.invoke()
        updateLibraryQuickSyncVisibility()
    }

    private fun showSyncScreen(autoQuickSync: Boolean) {
        isSyncScreenOpen = true
        syncStatusCollectJob?.cancel()
        syncPeersCollectJob?.cancel()
        overlayContainer.removeAllViews()
        val view = layoutInflater.inflate(R.layout.screen_sync, overlayContainer, false)
        overlayContainer.addView(view)
        overlayContainer.visibility = View.VISIBLE

        val backBtn = view.findViewById<View>(R.id.sync_back_btn)
        val connectIpBtn = view.findViewById<View>(R.id.sync_connect_ip_btn)
        val selfNameTv = view.findViewById<TextView>(R.id.sync_self_name)
        val selfInfoTv = view.findViewById<TextView>(R.id.sync_self_info)
        val renameBtn = view.findViewById<View>(R.id.sync_rename_btn)
        val switchDeletions = view.findViewById<SwitchCompat>(R.id.sync_switch_deletions)

        val statusCard = view.findViewById<View>(R.id.sync_status_card)
        val statusTitle = view.findViewById<TextView>(R.id.sync_status_title)
        val statusPhase = view.findViewById<TextView>(R.id.sync_status_phase)
        val cancelBtn = view.findViewById<View>(R.id.sync_cancel_btn)
        val pinContainer = view.findViewById<View>(R.id.sync_pin_container)
        val pinCodeTv = view.findViewById<TextView>(R.id.sync_pin_code)
        val progressBar = view.findViewById<ProgressBar>(R.id.sync_progress_bar)
        val progressDetail = view.findViewById<TextView>(R.id.sync_progress_detail)

        val peersEmpty = view.findViewById<View>(R.id.sync_peers_empty)
        val peersContainer = view.findViewById<LinearLayout>(R.id.sync_peers_container)
        val trustedSection = view.findViewById<View>(R.id.sync_trusted_section)
        val trustedContainer = view.findViewById<LinearLayout>(R.id.sync_trusted_container)

        fun refreshSelfHeader() {
            lifecycleScope.launch(Dispatchers.IO) {
                val info = syncManager.getSelfInfo()
                val ipPart = if (info.localIps.isNotEmpty()) " • ${info.localIps.first()}:${info.port}" else ""
                withContext(Dispatchers.Main) {
                    selfNameTv.text = info.deviceName
                    selfInfoTv.text = "${info.trackCount} songs • ${info.playlistCount} playlists$ipPart"
                }
            }
        }

        fun refreshTrustedList() {
            lifecycleScope.launch(Dispatchers.IO) {
                val trusted = store.listTrustedSyncPeers()
                withContext(Dispatchers.Main) {
                    trustedContainer.removeAllViews()
                    if (trusted.isEmpty()) {
                        trustedSection.visibility = View.GONE
                        return@withContext
                    }
                    trustedSection.visibility = View.VISIBLE
                    for (tp in trusted) {
                        val row = layoutInflater.inflate(R.layout.item_sync_peer, trustedContainer, false)
                        val icon = row.findViewById<ImageView>(R.id.sync_peer_icon)
                        val nameTv = row.findViewById<TextView>(R.id.sync_peer_name)
                        val trustedIcon = row.findViewById<ImageView>(R.id.sync_peer_trusted_icon)
                        val metaTv = row.findViewById<TextView>(R.id.sync_peer_meta)
                        val btn = row.findViewById<TextView>(R.id.sync_peer_btn)

                        icon.setImageResource(if (tp.platform == "android") R.drawable.ic_album else R.drawable.ic_devices)
                        nameTv.text = tp.deviceName.ifEmpty { tp.deviceId.take(8) }
                        trustedIcon.visibility = View.VISIBLE
                        val whenStr = if (tp.lastSyncAt > 0L) {
                            val diff = ((System.currentTimeMillis() / 1000L) - tp.lastSyncAt).coerceAtLeast(0L)
                            when {
                                diff < 60 -> "Just now"
                                diff < 3600 -> "${diff / 60}m ago"
                                diff < 86400 -> "${diff / 3600}h ago"
                                else -> "${diff / 86400}d ago"
                            }
                        } else "Paired, never synced"
                        metaTv.text = "${tp.platform} • Last synced: $whenStr"
                        btn.text = "Forget"
                        btn.setBackgroundResource(R.drawable.bg_thin_button)
                        btn.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_red))
                        btn.setOnClickListener {
                            syncManager.removeTrustedPeer(tp.deviceId)
                            refreshTrustedList()
                            updateSyncSummaryLabel()
                            updateLibraryQuickSyncVisibility()
                        }
                        trustedContainer.addView(row)
                    }
                }
            }
        }

        fun renderStatus(st: SyncStatus) {
            val busy = st.state == "syncing" || st.state == "pairing"
            switchDeletions.isEnabled = !busy
            if (st.state == "idle" || st.state == "discovering") {
                statusCard.visibility = View.GONE
                return
            }
            statusCard.visibility = View.VISIBLE
            statusTitle.text = when (st.state) {
                "pairing" -> "Pairing with ${st.peerName}"
                "syncing" -> "Syncing with ${st.peerName}"
                "done" -> "Sync Complete"
                "error" -> "Sync Failed"
                else -> st.peerName
            }
            statusTitle.setTextColor(
                ContextCompat.getColor(
                    this,
                    when (st.state) {
                        "done" -> R.color.accent_green
                        "error" -> R.color.accent_red
                        else -> R.color.text_primary
                    }
                )
            )
            statusPhase.text = when {
                st.state == "error" && st.error.isNotEmpty() -> st.error
                st.state == "done" && st.message.isNotEmpty() -> st.message
                else -> st.phase
            }
            cancelBtn.visibility = if (busy) View.VISIBLE else View.GONE

            if (st.state == "pairing" && st.pin.isNotEmpty()) {
                pinContainer.visibility = View.VISIBLE
                pinCodeTv.text = if (st.pin.length == 6) "${st.pin.substring(0, 3)} ${st.pin.substring(3)}" else st.pin
            } else {
                pinContainer.visibility = View.GONE
            }

            if (st.state == "syncing") {
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = st.totalBytes <= 0L && st.filesTotal <= 0
                progressBar.progress = st.percent.coerceIn(0, 100)
                val parts = mutableListOf<String>()
                if (st.currentFile.isNotEmpty()) parts.add(st.currentFile)
                val metrics = mutableListOf<String>()
                if (st.totalBytes > 0L) metrics.add("${formatBytes(st.transferredBytes)} / ${formatBytes(st.totalBytes)}")
                if (st.filesTotal > 0) metrics.add("${st.filesDone}/${st.filesTotal} files")
                if (st.speedBps > 0L) metrics.add("${formatBytes(st.speedBps)}/s")
                if (metrics.isNotEmpty()) parts.add(metrics.joinToString(" • "))
                if (parts.isNotEmpty()) {
                    progressDetail.visibility = View.VISIBLE
                    progressDetail.text = parts.joinToString("\n")
                } else {
                    progressDetail.visibility = View.GONE
                }
            } else {
                progressBar.visibility = View.GONE
                progressDetail.visibility = View.GONE
            }

            if (st.state == "done") {
                refreshSelfHeader()
                refreshTrustedList()
            }
        }

        backBtn.setOnClickListener { closeSyncScreen() }
        cancelBtn.setOnClickListener { syncManager.cancelSync() }

        renameBtn.setOnClickListener {
            val input = EditText(this).apply {
                setText(selfNameTv.text.toString())
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setSelection(text.length)
            }
            AlertDialog.Builder(this)
                .setTitle("Rename This Device")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val clean = input.text.toString().trim()
                    if (clean.isNotEmpty()) {
                        syncManager.renameDevice(clean)
                        refreshSelfHeader()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        connectIpBtn.setOnClickListener {
            val input = EditText(this).apply {
                hint = "PC IP (e.g. 192.168.1.10)"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            }
            AlertDialog.Builder(this)
                .setTitle("Connect by IP")
                .setView(input)
                .setPositiveButton("Find Device") { _, _ ->
                    val addr = input.text.toString().trim()
                    if (addr.isNotEmpty()) {
                        lifecycleScope.launch {
                            val res = syncManager.connectManualIp(addr)
                            res.onSuccess { p ->
                                Toast.makeText(this@MainActivity, "Found ${p.deviceName}", Toast.LENGTH_SHORT).show()
                            }.onFailure { err ->
                                Toast.makeText(this@MainActivity, "Connect failed: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        syncStatusCollectJob = lifecycleScope.launch {
            syncManager.status.collectLatest { st ->
                renderStatus(st)
            }
        }

        syncPeersCollectJob = lifecycleScope.launch {
            syncManager.peers.collectLatest { peers ->
                peersContainer.removeAllViews()
                if (peers.isEmpty()) {
                    peersEmpty.visibility = View.VISIBLE
                    return@collectLatest
                }
                peersEmpty.visibility = View.GONE
                val busy = syncManager.status.value.let { it.state == "syncing" || it.state == "pairing" }
                for (peer in peers) {
                    val row = layoutInflater.inflate(R.layout.item_sync_peer, peersContainer, false)
                    val icon = row.findViewById<ImageView>(R.id.sync_peer_icon)
                    val nameTv = row.findViewById<TextView>(R.id.sync_peer_name)
                    val trustedIcon = row.findViewById<ImageView>(R.id.sync_peer_trusted_icon)
                    val metaTv = row.findViewById<TextView>(R.id.sync_peer_meta)
                    val btn = row.findViewById<TextView>(R.id.sync_peer_btn)

                    icon.setImageResource(if (peer.platform == "android") R.drawable.ic_album else R.drawable.ic_devices)
                    nameTv.text = peer.deviceName.ifEmpty { "VibeMusic Device" }
                    trustedIcon.visibility = if (peer.trusted) View.VISIBLE else View.GONE
                    metaTv.text = "${peer.trackCount} songs • ${peer.playlistCount} playlists • ${peer.ip}:${peer.port}"
                    btn.isEnabled = !busy
                    btn.alpha = if (busy) 0.5f else 1.0f
                    btn.text = "Sync"
                    btn.setOnClickListener {
                        val err = syncManager.startPeerSync(peer.deviceId, switchDeletions.isChecked)
                        if (err.isNotEmpty()) {
                            Toast.makeText(this@MainActivity, err, Toast.LENGTH_SHORT).show()
                        }
                    }
                    peersContainer.addView(row)
                }
            }
        }

        refreshSelfHeader()
        refreshTrustedList()
        syncManager.startDiscovery(autoQuickSync = autoQuickSync, syncDeletions = switchDeletions.isChecked)
        lifecycleScope.launch {
            delay(200L)
            if (isSyncScreenOpen) refreshSelfHeader()
        }
    }

    private fun closeHiddenSongsScreen() {
        isHiddenSongsOpen = false
        overlayContainer.removeAllViews()
        overlayContainer.visibility = View.GONE
        refreshSettingsData?.invoke()
    }

    private fun updateGenreSettingsSummaryViews(st: GenreStatus) {
        val summaryView = genreSummaryTextView ?: return
        val modeView = genreModeTextView
        summaryView.text = if (st.running) {
            val pct = if (st.total > 0) " (${(st.done * 100 / st.total).coerceIn(0, 100)}%)" else ""
            "${st.phase.ifEmpty { "Working" }}$pct"
        } else {
            "${st.identified} / ${st.totalTracks} songs have genres" +
                if (st.aiIdentified > 0) " • ${st.aiIdentified} AI-tagged" else ""
        }
        val ai = store.loadSettings().ai
        val preset = AiGenreClient.findPreset(ai.provider)
        val modelLabel = ai.model.ifBlank { preset?.defaultModel.orEmpty() }
        modeView?.text = when {
            ai.enabled && preset != null -> "AI: ${preset.name}${if (modelLabel.isNotEmpty()) " ($modelLabel)" else ""}"
            else -> "Standard (No AI) + Bring Your Own AI"
        }
    }

    private fun closeGenreSettingsScreen() {
        isGenreSettingsOpen = false
        refreshGenreSettingsData = null
        overlayContainer.removeAllViews()
        overlayContainer.visibility = View.GONE
        refreshSettingsData?.invoke()
    }

    private fun showGenreSettingsScreen() {
        isGenreSettingsOpen = true
        overlayContainer.removeAllViews()
        val view = layoutInflater.inflate(R.layout.screen_genre_settings, overlayContainer, false)
        overlayContainer.addView(view)
        overlayContainer.visibility = View.VISIBLE

        val statsText = view.findViewById<TextView>(R.id.genre_stats_text)
        val progressRow = view.findViewById<View>(R.id.genre_progress_row)
        val progressText = view.findViewById<TextView>(R.id.genre_progress_text)
        val progressBar = view.findViewById<ProgressBar>(R.id.genre_progress_bar)
        val btnStop = view.findViewById<TextView>(R.id.genre_btn_stop)
        val statusMsg = view.findViewById<TextView>(R.id.genre_status_msg)

        val btnClassicMissing = view.findViewById<TextView>(R.id.genre_btn_classic_missing)
        val btnClassicAll = view.findViewById<TextView>(R.id.genre_btn_classic_all)

        val providerPicker = view.findViewById<View>(R.id.genre_provider_picker)
        val providerNameTxt = view.findViewById<TextView>(R.id.genre_provider_name)
        val providerBadgeTxt = view.findViewById<TextView>(R.id.genre_provider_badge)
        val providerNoteTxt = view.findViewById<TextView>(R.id.genre_provider_note)

        val overrideUrlRow = view.findViewById<View>(R.id.genre_override_url_row)
        val defaultEndpointTxt = view.findViewById<TextView>(R.id.genre_default_endpoint_text)
        val switchOverrideUrl = view.findViewById<SwitchCompat>(R.id.genre_switch_override_url)
        val urlGroup = view.findViewById<View>(R.id.genre_url_group)
        val inputBaseUrl = view.findViewById<EditText>(R.id.genre_input_base_url)

        val keyOptionalHint = view.findViewById<View>(R.id.genre_key_optional_hint)
        val inputApiKey = view.findViewById<EditText>(R.id.genre_input_api_key)
        val btnToggleKey = view.findViewById<TextView>(R.id.genre_btn_toggle_key)
        val inputModel = view.findViewById<EditText>(R.id.genre_input_model)

        val btnTest = view.findViewById<TextView>(R.id.genre_btn_test)
        val testResultTxt = view.findViewById<TextView>(R.id.genre_test_result)

        val switchWebSearch = view.findViewById<SwitchCompat>(R.id.genre_switch_web_search)
        val groundingRow = view.findViewById<View>(R.id.genre_gemini_grounding_row)
        val switchGrounding = view.findViewById<SwitchCompat>(R.id.genre_switch_gemini_grounding)
        val switchAutoAi = view.findViewById<SwitchCompat>(R.id.genre_switch_auto_ai)
        val switchCustomGenres = view.findViewById<SwitchCompat>(R.id.genre_switch_custom_genres)

        val resumeRow = view.findViewById<View>(R.id.genre_resume_row)
        val btnResume = view.findViewById<TextView>(R.id.genre_btn_ai_resume)
        val btnClearPending = view.findViewById<TextView>(R.id.genre_btn_clear_pending)
        val btnAiMissing = view.findViewById<TextView>(R.id.genre_btn_ai_missing)
        val btnAiAll = view.findViewById<TextView>(R.id.genre_btn_ai_all)

        var currentAi = store.loadSettings().ai
        var showKey = false
        var overrideUrl = currentAi.baseUrl.isNotBlank()
        var testing = false
        var suppressWatchers = false
        var saveJob: Job? = null

        fun applyProviderLayout(ai: AiGenreSettings) {
            val preset = AiGenreClient.findPreset(ai.provider) ?: AiGenreClient.findPreset("gemini")!!
            providerNameTxt.text = preset.name
            providerBadgeTxt.text = if (preset.selfHosted) "Self-hosted Server" else "Cloud API"
            if (preset.note.isNotEmpty()) {
                providerNoteTxt.text = preset.note
                providerNoteTxt.visibility = View.VISIBLE
            } else {
                providerNoteTxt.visibility = View.GONE
            }

            defaultEndpointTxt.text = preset.defaultBaseUrl
            inputBaseUrl.hint = preset.defaultBaseUrl.ifEmpty { "https://your-server/v1" }
            inputModel.hint = preset.defaultModel.ifEmpty { "model-name" }
            keyOptionalHint.visibility = if (preset.selfHosted) View.VISIBLE else View.GONE
            inputApiKey.hint = if (preset.needsKey) "Paste your API key" else "Optional API key"

            if (preset.selfHosted) {
                overrideUrlRow.visibility = View.GONE
                urlGroup.visibility = View.VISIBLE
            } else {
                overrideUrlRow.visibility = View.VISIBLE
                val showCustom = overrideUrl || ai.baseUrl.isNotBlank()
                urlGroup.visibility = if (showCustom) View.VISIBLE else View.GONE
                suppressWatchers = true
                switchOverrideUrl.isChecked = showCustom
                suppressWatchers = false
            }

            groundingRow.visibility = if (preset.id == "gemini") View.VISIBLE else View.GONE
        }

        fun loadFieldsFromSettings(ai: AiGenreSettings) {
            suppressWatchers = true
            inputBaseUrl.setText(ai.baseUrl)
            inputApiKey.setText(ai.apiKey)
            inputModel.setText(ai.model)
            switchWebSearch.isChecked = ai.webSearch
            switchGrounding.isChecked = ai.geminiGrounding
            switchAutoAi.isChecked = ai.autoOnNew
            switchCustomGenres.isChecked = ai.allowCustomGenres
            suppressWatchers = false
            applyProviderLayout(ai)
        }

        fun persistAi(updated: AiGenreSettings) {
            currentAi = updated
            val cur = store.loadSettings()
            store.saveSettings(cur.copy(ai = updated))
        }

        fun flushTextInputs(): AiGenreSettings {
            saveJob?.cancel()
            val updated = currentAi.copy(
                baseUrl = inputBaseUrl.text.toString().trim(),
                apiKey = inputApiKey.text.toString().trim(),
                model = inputModel.text.toString().trim()
            )
            persistAi(updated)
            return updated
        }

        fun scheduleTextSave() {
            if (suppressWatchers) return
            testResultTxt.visibility = View.GONE
            saveJob?.cancel()
            saveJob = lifecycleScope.launch {
                delay(250)
                flushTextInputs()
            }
        }

        fun renderStatus(st: GenreStatus) {
            statsText.text = buildString {
                append("${st.identified} / ${st.totalTracks} songs have genres")
                if (st.aiIdentified > 0) append(" • ${st.aiIdentified} AI")
                if (st.unidentified > 0) append(" • ${st.unidentified} unidentified")
            }

            if (st.running) {
                progressRow.visibility = View.VISIBLE
                val pct = if (st.total > 0) ((st.done * 100) / st.total).coerceIn(0, 100) else 30
                progressText.text = if (st.total > 0) {
                    "${st.phase.ifEmpty { "Working" }}... ${st.done}/${st.total} ($pct%)"
                } else {
                    "${st.phase.ifEmpty { "Working" }}..."
                }
                progressBar.progress = pct
                statusMsg.visibility = View.GONE
            } else {
                progressRow.visibility = View.GONE
                when {
                    st.error.isNotEmpty() -> {
                        statusMsg.text = st.error
                        statusMsg.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
                        statusMsg.visibility = View.VISIBLE
                    }
                    st.message.isNotEmpty() -> {
                        statusMsg.text = st.message
                        statusMsg.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                        statusMsg.visibility = View.VISIBLE
                    }
                    else -> statusMsg.visibility = View.GONE
                }
            }

            if (!st.running && st.pendingAi > 0) {
                resumeRow.visibility = View.VISIBLE
                btnResume.text = "Resume AI (${st.pendingAi} left)"
            } else {
                resumeRow.visibility = View.GONE
            }

            val busy = st.running
            val alpha = if (busy) 0.45f else 1.0f
            btnClassicMissing.isEnabled = !busy
            btnClassicAll.isEnabled = !busy
            btnAiMissing.isEnabled = !busy
            btnAiAll.isEnabled = !busy
            btnTest.isEnabled = !busy && !testing
            btnClassicMissing.alpha = alpha
            btnClassicAll.alpha = alpha
            btnAiMissing.alpha = alpha
            btnAiAll.alpha = alpha
        }

        refreshGenreSettingsData = { st ->
            renderStatus(st)
        }

        view.findViewById<View>(R.id.genre_settings_back).setOnClickListener {
            flushTextInputs()
            closeGenreSettingsScreen()
        }

        btnStop.setOnClickListener {
            genreDetector.cancel()
        }

        btnClassicMissing.setOnClickListener {
            flushTextInputs()
            genreDetector.startClassic(redetectAll = false)
        }

        btnClassicAll.setOnClickListener {
            flushTextInputs()
            AlertDialog.Builder(this)
                .setTitle("Re-detect all genres?")
                .setMessage("Discards automatically detected genres and runs standard detection (file tags, MusicBrainz + Deezer, hints) again without AI. Hand-set genres are kept.")
                .setPositiveButton("Re-detect") { _, _ ->
                    genreDetector.startClassic(redetectAll = true)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        providerPicker.setOnClickListener {
            val allPresets = AiGenreClient.presets
            val labels = allPresets.map { p ->
                val tag = if (p.selfHosted) "[Self-hosted]" else "[Cloud]"
                "$tag ${p.name}"
            }.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("Choose AI Provider")
                .setItems(labels) { _, which ->
                    val chosen = allPresets[which]
                    overrideUrl = false
                    testResultTxt.visibility = View.GONE
                    val nextAi = currentAi.copy(
                        provider = chosen.id,
                        baseUrl = if (chosen.selfHosted) chosen.defaultBaseUrl else "",
                        model = chosen.defaultModel
                    )
                    persistAi(nextAi)
                    loadFieldsFromSettings(nextAi)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        switchOverrideUrl.setOnCheckedChangeListener { _, isChecked ->
            if (suppressWatchers) return@setOnCheckedChangeListener
            overrideUrl = isChecked
            if (!isChecked) {
                suppressWatchers = true
                inputBaseUrl.setText("")
                suppressWatchers = false
                persistAi(currentAi.copy(baseUrl = ""))
            }
            applyProviderLayout(currentAi)
        }

        btnToggleKey.setOnClickListener {
            showKey = !showKey
            val sel = inputApiKey.selectionEnd
            inputApiKey.inputType = if (showKey) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            if (sel >= 0) inputApiKey.setSelection(sel.coerceAtMost(inputApiKey.text.length))
            btnToggleKey.text = if (showKey) "Hide" else "Show"
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { scheduleTextSave() }
        }
        inputBaseUrl.addTextChangedListener(watcher)
        inputApiKey.addTextChangedListener(watcher)
        inputModel.addTextChangedListener(watcher)

        switchWebSearch.setOnCheckedChangeListener { _, checked ->
            if (!suppressWatchers) persistAi(currentAi.copy(webSearch = checked))
        }
        switchGrounding.setOnCheckedChangeListener { _, checked ->
            if (!suppressWatchers) persistAi(currentAi.copy(geminiGrounding = checked))
        }
        switchAutoAi.setOnCheckedChangeListener { _, checked ->
            if (!suppressWatchers) {
                persistAi(currentAi.copy(autoOnNew = checked, enabled = if (checked) true else currentAi.enabled))
            }
        }
        switchCustomGenres.setOnCheckedChangeListener { _, checked ->
            if (!suppressWatchers) persistAi(currentAi.copy(allowCustomGenres = checked))
        }

        btnTest.setOnClickListener {
            if (testing) return@setOnClickListener
            val cfg = flushTextInputs()
            testing = true
            btnTest.text = "Testing..."
            btnTest.isEnabled = false
            testResultTxt.visibility = View.GONE
            lifecycleScope.launch {
                val res = withContext(Dispatchers.IO) { AiGenreClient.testConnection(cfg) }
                testing = false
                btnTest.text = "Test connection"
                renderStatus(genreDetector.status.value)
                testResultTxt.text = res.message
                testResultTxt.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (res.ok) R.color.accent_green else R.color.accent_red
                    )
                )
                testResultTxt.visibility = View.VISIBLE
            }
        }

        fun promptAiDetect(mode: String) {
            if (genreDetector.status.value.running) return
            val cfg = flushTextInputs()
            try {
                AiGenreClient.resolveConfig(cfg)
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "Check AI settings", Toast.LENGTH_LONG).show()
                return
            }
            val targets = genreDetector.getTargets()
            val count = if (mode == "missing") targets.missing else targets.allAuto
            if (count == 0) {
                Toast.makeText(
                    this,
                    if (mode == "missing") "All songs already have genres" else "No eligible songs (all hand-set or file-tagged)",
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            val preset = AiGenreClient.findPreset(cfg.provider)
            val batchSize = when {
                cfg.provider == "ollama" || cfg.provider == "lmstudio" || cfg.provider == "custom" -> if (cfg.webSearch) 8 else 10
                cfg.provider == "gemini" && cfg.geminiGrounding -> 8
                cfg.webSearch -> 12
                else -> 15
            }
            val batches = ceil(count.toDouble() / batchSize.toDouble()).toInt().coerceAtLeast(1)
            val modelLabel = cfg.model.ifBlank { preset?.defaultModel ?: "default" }
            val privacy = if (preset?.selfHosted == true) {
                "Sends metadata to your self-hosted server."
            } else {
                "Only artist, title, and album text are sent (never audio)."
            }
            val note = if (mode == "all") {
                "Hand-set genres and embedded file tags are never changed."
            } else {
                "Progress is saved after every batch."
            }

            AlertDialog.Builder(this)
                .setTitle(if (mode == "missing") "AI detect $count missing song(s)?" else "AI re-detect $count song(s)?")
                .setMessage("Provider: ${preset?.name ?: cfg.provider} ($modelLabel).\nProcesses $count song(s) in ~$batches request(s).\n$privacy $note")
                .setPositiveButton(if (mode == "missing") "Start AI detect" else "Start AI re-detect") { _, _ ->
                    persistAi(cfg.copy(enabled = true))
                    val err = genreDetector.startAi(mode)
                    if (err.isNotEmpty()) Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnAiMissing.setOnClickListener { promptAiDetect("missing") }
        btnAiAll.setOnClickListener { promptAiDetect("all") }

        btnResume.setOnClickListener {
            val cfg = flushTextInputs()
            persistAi(cfg.copy(enabled = true))
            val err = genreDetector.startAi("resume")
            if (err.isNotEmpty()) Toast.makeText(this, err, Toast.LENGTH_SHORT).show()
        }

        btnClearPending.setOnClickListener {
            genreDetector.clearPendingAi()
        }

        loadFieldsFromSettings(currentAi)
        renderStatus(genreDetector.refreshStatus())
    }

    private fun showEditGenresDialog(track: Track, forArtist: String? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_genres, null)
        val titleTxt = dialogView.findViewById<TextView>(R.id.edit_genres_title)
        val subTxt = dialogView.findViewById<TextView>(R.id.edit_genres_subtitle)
        val selectedWrap = dialogView.findViewById<WrapLayout>(R.id.edit_genres_selected_wrap)
        val emptySelected = dialogView.findViewById<TextView>(R.id.edit_genres_empty_selected)
        val treeContainer = dialogView.findViewById<LinearLayout>(R.id.edit_genres_tree_container)
        val customInput = dialogView.findViewById<EditText>(R.id.edit_genres_custom_input)
        val btnAddCustom = dialogView.findViewById<TextView>(R.id.edit_genres_btn_add_custom)
        val btnClear = dialogView.findViewById<TextView>(R.id.edit_genres_btn_clear)
        val btnCancel = dialogView.findViewById<TextView>(R.id.edit_genres_btn_cancel)
        val btnSave = dialogView.findViewById<TextView>(R.id.edit_genres_btn_save)

        val selected = GenreTaxonomy.cleanUserGenres(track.genres).toMutableList()
        if (!forArtist.isNullOrBlank()) {
            titleTxt.text = "Genres for $forArtist"
            subTxt.text = "Applies as hand-set genres to all songs by $forArtist."
        } else {
            titleTxt.text = "Edit genres"
            subTxt.text = "${track.displayTitle} — ${track.displayArtist}"
        }

        val chipButtons = LinkedHashMap<String, TextView>()

        fun styleChip(tv: TextView, active: Boolean) {
            tv.setBackgroundResource(if (active) R.drawable.bg_pill_active else R.drawable.bg_pill)
            tv.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (active) R.color.black else R.color.text_primary
                )
            )
            tv.textSize = 12f
            tv.setPadding(12.dpToPx(), 6.dpToPx(), 12.dpToPx(), 6.dpToPx())
        }

        lateinit var rebuildSelected: () -> Unit

        fun toggleGenre(name: String) {
            if (selected.contains(name)) {
                selected.remove(name)
            } else {
                selected.add(name)
                val cleaned = GenreTaxonomy.cleanUserGenres(selected)
                selected.clear()
                selected.addAll(cleaned)
            }
            rebuildSelected()
        }

        rebuildSelected = {
            selectedWrap.removeAllViews()
            emptySelected.visibility = if (selected.isEmpty()) View.VISIBLE else View.GONE
            for (g in selected.toList()) {
                val chip = TextView(this).apply {
                    text = "$g ✕"
                    isClickable = true
                    isFocusable = true
                    styleChip(this, true)
                    setOnClickListener {
                        selected.remove(g)
                        rebuildSelected()
                    }
                }
                selectedWrap.addView(chip)
            }
            for ((name, tv) in chipButtons) {
                styleChip(tv, selected.contains(name))
            }
        }

        for ((root, subs) in GenreTaxonomy.taxonomyGroups()) {
            val groupBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 6.dpToPx(), 0, 8.dpToPx())
            }
            val rootChip = TextView(this).apply {
                text = root
                isClickable = true
                isFocusable = true
                styleChip(this, selected.contains(root))
                setOnClickListener { toggleGenre(root) }
            }
            chipButtons[root] = rootChip

            val rootWrap = WrapLayout(this).apply {
                addView(rootChip)
            }
            groupBox.addView(rootWrap)

            if (subs.isNotEmpty()) {
                val subWrap = WrapLayout(this).apply {
                    setPadding(12.dpToPx(), 6.dpToPx(), 0, 0)
                }
                for (sub in subs) {
                    val subChip = TextView(this).apply {
                        text = sub
                        isClickable = true
                        isFocusable = true
                        styleChip(this, selected.contains(sub))
                        setOnClickListener { toggleGenre(sub) }
                    }
                    chipButtons[sub] = subChip
                    subWrap.addView(subChip)
                }
                groupBox.addView(subWrap)
            }
            treeContainer.addView(groupBox)
        }

        rebuildSelected()

        btnAddCustom.setOnClickListener {
            val raw = customInput.text.toString().trim()
            if (raw.isEmpty()) return@setOnClickListener
            val mapped = GenreTaxonomy.mapTag(raw).ifEmpty { raw }
            selected.add(mapped)
            val cleaned = GenreTaxonomy.cleanUserGenres(selected)
            selected.clear()
            selected.addAll(cleaned)
            customInput.setText("")
            rebuildSelected()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnClear.setOnClickListener {
            selected.clear()
            lifecycleScope.launch(Dispatchers.IO) {
                if (!forArtist.isNullOrBlank()) {
                    store.setArtistUserGenres(forArtist, emptyList())
                } else {
                    store.setTrackUserGenres(track.id, emptyList())
                }
                genreDetector.refreshStatus()
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Cleared hand-set genres", Toast.LENGTH_SHORT).show()
                    genreDetector.kickAuto()
                }
            }
        }
        btnSave.setOnClickListener {
            val finalGenres = selected.toList()
            lifecycleScope.launch(Dispatchers.IO) {
                if (!forArtist.isNullOrBlank()) {
                    store.setArtistUserGenres(forArtist, finalGenres)
                } else {
                    store.setTrackUserGenres(track.id, finalGenres)
                }
                genreDetector.refreshStatus()
                withContext(Dispatchers.Main) {
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "Saved genres", Toast.LENGTH_SHORT).show()
                    if (currentTab == "home") tabViews["home"]?.let { populateHomeView(it, refreshOnlineDiscover = false) }
                    if (currentTab == "library") refreshLibraryData?.invoke()
                }
            }
        }

        dialog.show()
    }

    private fun showHiddenSongsScreen() {
        isHiddenSongsOpen = true
        overlayContainer.removeAllViews()
        val view = layoutInflater.inflate(R.layout.screen_hidden_tracks, overlayContainer, false)
        overlayContainer.addView(view)
        overlayContainer.visibility = View.VISIBLE

        view.findViewById<View>(R.id.hidden_back).setOnClickListener {
            closeHiddenSongsScreen()
        }

        val emptyView = view.findViewById<View>(R.id.hidden_empty)
        val recycler = view.findViewById<RecyclerView>(R.id.recycler_hidden_tracks)
        recycler.layoutManager = LinearLayoutManager(this)

        val adapter = TrackAdapter(store, { track ->
            playerManager.playTracks(listOf(track), 0)
        }, { track, anchor ->
            showTrackMenu(track, anchor)
        })
        recycler.adapter = adapter

        lifecycleScope.launch(Dispatchers.IO) {
            val hiddenTracks = store.getHiddenTracks()
            withContext(Dispatchers.Main) {
                if (hiddenTracks.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recycler.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recycler.visibility = View.VISIBLE
                    adapter.submitList(hiddenTracks)
                }
            }
        }
    }

    // -------------------------------------------------------------
    // Home Customize Dialog (Reorder & Toggle All 7 Segments)
    // -------------------------------------------------------------

    private fun showHomeCustomizeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_home_customize, null)
        val recycler = dialogView.findViewById<RecyclerView>(R.id.dialog_home_sections_recycler)
        recycler.layoutManager = LinearLayoutManager(this)

        val settings = store.loadSettings()
        var updatedOrder = settings.homeOrder
        var updatedHidden = settings.homeHidden

        lateinit var adapter: SectionReorderAdapter
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = true

            override fun isItemViewSwipeEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                    viewHolder.itemView.animate().scaleX(1.03f).scaleY(1.03f).alpha(0.94f).setDuration(120).start()
                    viewHolder.itemView.translationZ = 8.dpToPx().toFloat()
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(120).start()
                viewHolder.itemView.translationZ = 0f
            }
        })

        adapter = SectionReorderAdapter(
            initialOrder = settings.homeOrder,
            initialHidden = settings.homeHidden,
            onStartDrag = { holder -> itemTouchHelper.startDrag(holder) }
        ) { newOrder, newHidden ->
            updatedOrder = newOrder
            updatedHidden = newHidden
        }
        recycler.adapter = adapter
        itemTouchHelper.attachToRecyclerView(recycler)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialogView.findViewById<Button>(R.id.dialog_home_btn_save).setOnClickListener {
            val wasDiscoverHidden = settings.homeHidden.contains("discover")
            val newSettings = store.loadSettings().copy(
                homeOrder = adapter.currentOrder,
                homeHidden = adapter.currentHidden
            )
            store.saveSettings(newSettings)
            dialog.dismiss()
            tabViews["home"]?.let { homeView ->
                applyHomeSectionOrder(homeView)
                if (wasDiscoverHidden && !newSettings.homeHidden.contains("discover")) {
                    populateHomeView(homeView, refreshOnlineDiscover = true)
                }
            }
            Toast.makeText(this, "Home layout updated", Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    // -------------------------------------------------------------
    // 10-Band Equalizer Dialog
    // -------------------------------------------------------------

    private fun showEqualizerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_equalizer, null)
        val switchEq = dialogView.findViewById<SwitchCompat>(R.id.eq_dialog_switch)
        val btnReset = dialogView.findViewById<TextView>(R.id.eq_dialog_btn_reset)
        val presetContainer = dialogView.findViewById<LinearLayout>(R.id.eq_preset_container)
        val preampSeekBar = dialogView.findViewById<SeekBar>(R.id.eq_preamp_seekbar)
        val preampValueText = dialogView.findViewById<TextView>(R.id.eq_preamp_value_text)
        val bandsContainer = dialogView.findViewById<LinearLayout>(R.id.eq_bands_container)

        val eq = playerManager.biquadEq
        val initialSettings = store.loadSettings()
        var activePreset = initialSettings.eqPreset
        val presetPills = linkedMapOf<String, TextView>()

        fun persistEqSettings() {
            store.saveSettings(
                store.loadSettings().copy(
                    eqEnabled = eq.isEnabled(),
                    eqPreset = activePreset,
                    eqPreamp = eq.getPreampDb(),
                    eqBands = eq.getBands()
                )
            )
        }

        fun updatePresetPills() {
            for ((name, pill) in presetPills) {
                val isSelected = name == activePreset
                pill.setBackgroundResource(if (isSelected) R.drawable.bg_pill_active else R.drawable.bg_pill)
                pill.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (isSelected) R.color.black else R.color.text_primary
                    )
                )
            }
        }

        switchEq.isChecked = eq.isEnabled()
        switchEq.setOnCheckedChangeListener { _, isChecked ->
            eq.setEnabled(isChecked)
            persistEqSettings()
        }

        val initialPreamp = eq.getPreampDb()
        preampSeekBar.progress = Math.round((initialPreamp + 12f) * 10f).coerceIn(0, 180)
        preampValueText.text = "%+.1f dB".format(initialPreamp)
        preampSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                if (fromUser) {
                    val gain = (prog / 10f) - 12f
                    eq.setPreampDb(gain)
                    preampValueText.text = "%+.1f dB".format(gain)
                    if (!switchEq.isChecked) {
                        switchEq.isChecked = true
                    }
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}

            override fun onStopTrackingTouch(sb: SeekBar?) {
                persistEqSettings()
            }
        })

        // Build 10 Band sliders
        val currentBands = eq.getBands()
        val seekbars = mutableListOf<SeekBar>()
        val dbTexts = mutableListOf<TextView>()
        val labels = listOf("31 Hz", "62 Hz", "125 Hz", "250 Hz", "500 Hz", "1 kHz", "2 kHz", "4 kHz", "8 kHz", "16 kHz")

        for (i in 0..9) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 46.dpToPx())
            }

            val label = TextView(this).apply {
                text = labels[i]
                layoutParams = LinearLayout.LayoutParams(70.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 12f
            }

            val dbVal = TextView(this).apply {
                text = "%+.1f dB".format(currentBands[i])
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(64.dpToPx(), LinearLayout.LayoutParams.WRAP_CONTENT)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_green))
                textSize = 12f
            }

            val bar = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                max = 240 // -12.0dB to +12.0dB (step 0.1)
                progress = Math.round((currentBands[i] + 12f) * 10f).coerceIn(0, 240)
                progressTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.accent_green)
                thumbTintList = ContextCompat.getColorStateList(this@MainActivity, R.color.accent_green)

                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, prog: Int, fromUser: Boolean) {
                        if (fromUser) {
                            val gain = (prog / 10f) - 12f
                            eq.setBandGain(i, gain)
                            dbVal.text = "%+.1f dB".format(gain)
                            if (!switchEq.isChecked) {
                                switchEq.isChecked = true
                            }
                            if (activePreset != "Custom") {
                                activePreset = "Custom"
                                updatePresetPills()
                            }
                        }
                    }

                    override fun onStartTrackingTouch(sb: SeekBar?) {}

                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        persistEqSettings()
                    }
                })
            }

            seekbars.add(bar)
            dbTexts.add(dbVal)
            row.addView(label)
            row.addView(bar)
            row.addView(dbVal)
            bandsContainer.addView(row)
        }

        // Preset Pills (+ Custom pill)
        for ((name, gains) in BiquadEqAudioProcessor.PRESETS) {
            val pill = TextView(this).apply {
                text = name
                setPadding(14.dpToPx(), 6.dpToPx(), 14.dpToPx(), 6.dpToPx())
                setBackgroundResource(R.drawable.bg_pill)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = 8.dpToPx()
                }
                setOnClickListener {
                    eq.setAllBands(gains)
                    if (!switchEq.isChecked) {
                        switchEq.isChecked = true
                    }
                    activePreset = name
                    for (i in 0..9) {
                        seekbars[i].progress = Math.round((gains[i] + 12f) * 10f).coerceIn(0, 240)
                        dbTexts[i].text = "%+.1f dB".format(gains[i])
                    }
                    updatePresetPills()
                    persistEqSettings()
                }
            }
            presetPills[name] = pill
            presetContainer.addView(pill)
        }

        val customPill = TextView(this).apply {
            text = "Custom"
            setPadding(14.dpToPx(), 6.dpToPx(), 14.dpToPx(), 6.dpToPx())
            setBackgroundResource(R.drawable.bg_pill)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8.dpToPx()
            }
            setOnClickListener {
                activePreset = "Custom"
                updatePresetPills()
                persistEqSettings()
            }
        }
        presetPills["Custom"] = customPill
        presetContainer.addView(customPill)
        updatePresetPills()

        btnReset.setOnClickListener {
            eq.resetBands()
            activePreset = "Flat"
            preampSeekBar.progress = 120
            preampValueText.text = "+0.0 dB"
            for (i in 0..9) {
                seekbars[i].progress = 120
                dbTexts[i].text = "+0.0 dB"
            }
            updatePresetPills()
            persistEqSettings()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.setOnDismissListener {
            refreshSettingsData?.invoke()
        }

        dialogView.findViewById<Button>(R.id.eq_dialog_btn_close).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // -------------------------------------------------------------
    // Sleep Timer Dialog
    // -------------------------------------------------------------

    private fun showSleepTimerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_sleep_timer, null)
        val statusText = dialogView.findViewById<TextView>(R.id.sleep_timer_status)
        val modeMinutes = dialogView.findViewById<TextView>(R.id.sleep_mode_minutes)
        val modeTracks = dialogView.findViewById<TextView>(R.id.sleep_mode_tracks)
        val customInput = dialogView.findViewById<EditText>(R.id.sleep_custom_input)
        val btnSetCustom = dialogView.findViewById<TextView>(R.id.sleep_btn_set_custom)
        val btnTurnOff = dialogView.findViewById<TextView>(R.id.sleep_btn_turn_off)
        val btnClose = dialogView.findViewById<TextView>(R.id.sleep_btn_close)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        var isMinutesMode = true
        fun updateModePills() {
            modeMinutes.setBackgroundResource(if (isMinutesMode) R.drawable.bg_pill_active else R.drawable.bg_pill)
            modeMinutes.setTextColor(ContextCompat.getColor(this, if (isMinutesMode) R.color.black else R.color.text_primary))
            modeTracks.setBackgroundResource(if (!isMinutesMode) R.drawable.bg_pill_active else R.drawable.bg_pill)
            modeTracks.setTextColor(ContextCompat.getColor(this, if (!isMinutesMode) R.color.black else R.color.text_primary))
            customInput.hint = if (isMinutesMode) "Enter minutes" else "Enter number of tracks"
        }

        modeMinutes.setOnClickListener {
            isMinutesMode = true
            updateModePills()
        }
        modeTracks.setOnClickListener {
            isMinutesMode = false
            updateModePills()
        }
        updateModePills()

        val statusJob = lifecycleScope.launch {
            while (true) {
                val sec = playerManager.sleepTimerRemainingSec.value
                val tracks = playerManager.sleepTimerRemainingTracks.value
                statusText.text = when {
                    sec > 0 -> "Active: pausing in ${formatMs(sec * 1000L)}"
                    tracks > 0 -> "Active: stopping after $tracks ${if (tracks == 1) "track" else "tracks"}"
                    else -> "No timer active"
                }
                delay(500)
            }
        }
        dialog.setOnDismissListener {
            statusJob.cancel()
        }

        val minutePresets = listOf(
            R.id.sleep_preset_15m to 15,
            R.id.sleep_preset_30m to 30,
            R.id.sleep_preset_45m to 45,
            R.id.sleep_preset_60m to 60
        )
        for ((id, mins) in minutePresets) {
            dialogView.findViewById<View>(id).setOnClickListener {
                playerManager.setSleepTimer(mins)
                Toast.makeText(this, "Timer set for $mins minutes", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }

        val trackPresets = listOf(
            R.id.sleep_preset_1t to 1,
            R.id.sleep_preset_3t to 3,
            R.id.sleep_preset_5t to 5,
            R.id.sleep_preset_10t to 10
        )
        for ((id, tracks) in trackPresets) {
            dialogView.findViewById<View>(id).setOnClickListener {
                playerManager.setSleepTimerTracks(tracks)
                Toast.makeText(
                    this,
                    "Timer set: stop after $tracks ${if (tracks == 1) "track" else "tracks"}",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
        }

        btnSetCustom.setOnClickListener {
            val value = customInput.text.toString().trim().toIntOrNull()
            if (value == null || value <= 0) {
                Toast.makeText(this, "Enter a positive number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isMinutesMode) {
                playerManager.setSleepTimer(value)
                Toast.makeText(this, "Timer set for $value ${if (value == 1) "minute" else "minutes"}", Toast.LENGTH_SHORT).show()
            } else {
                playerManager.setSleepTimerTracks(value)
                Toast.makeText(
                    this,
                    "Timer set: stop after $value ${if (value == 1) "track" else "tracks"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            dialog.dismiss()
        }

        btnTurnOff.setOnClickListener {
            playerManager.cancelSleepTimer()
            Toast.makeText(this, "Sleep timer cancelled", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    // -------------------------------------------------------------
    // Track Context Menu (Options)
    // -------------------------------------------------------------

    private fun showTrackMenu(
        track: Track,
        anchor: View,
        playlistId: Long = 0L,
        isFromQueue: Boolean = false,
        queueIndex: Int = -1
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            val freshTrack = store.getTrackById(track.id) ?: track
            val coverBitmap = CoverLoader.loadBitmap(store, freshTrack.id, 192)
            val isHidden = store.isTrackHidden(freshTrack.id)

            withContext(Dispatchers.Main) {
                val actions = mutableListOf<BottomActionSheet.ActionItem>()

                if (isFromQueue) {
                    actions += BottomActionSheet.ActionItem("Remove from queue", R.drawable.ic_close) {
                        val idx = if (queueIndex >= 0) queueIndex else playerManager.queue.value.indexOfFirst { it.id == track.id }
                        if (idx >= 0) {
                            playerManager.removeFromQueue(idx)
                            Toast.makeText(this@MainActivity, "Removed from queue", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                actions += BottomActionSheet.ActionItem("Play next", R.drawable.ic_play_next) {
                    playerManager.playNextInQueue(track)
                    Toast.makeText(this@MainActivity, "Playing next: ${track.displayTitle}", Toast.LENGTH_SHORT).show()
                }

                actions += BottomActionSheet.ActionItem("Add to queue", R.drawable.ic_queue) {
                    playerManager.addToQueue(track)
                    Toast.makeText(this@MainActivity, "Added to queue: ${track.displayTitle}", Toast.LENGTH_SHORT).show()
                }

                actions += BottomActionSheet.ActionItem("Add to playlist...", R.drawable.ic_playlist_add) {
                    showAddToPlaylistDialog(track)
                }

                actions += BottomActionSheet.ActionItem(
                    label = if (track.liked) "Remove from Liked Songs" else "Add to Liked Songs",
                    iconRes = if (track.liked) R.drawable.ic_heart_filled else R.drawable.ic_heart
                ) {
                    val newLiked = !track.liked
                    lifecycleScope.launch(Dispatchers.IO) {
                        store.setLiked(track.id, newLiked)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                this@MainActivity,
                                if (newLiked) "Added to Liked Songs" else "Removed from Liked Songs",
                                Toast.LENGTH_SHORT
                            ).show()
                            if (currentTab == "library") refreshLibraryData?.invoke()
                        }
                    }
                }

                if (playlistId > 0) {
                    actions += BottomActionSheet.ActionItem("Remove from this playlist", R.drawable.ic_close) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val tids = store.getPlaylistTrackIds(playlistId).filter { it != track.id }
                            store.setPlaylistTracks(playlistId, tids)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Removed from playlist", Toast.LENGTH_SHORT).show()
                                openCollectionDetail("Playlist", emptyList(), playlistId, true)
                            }
                        }
                    }
                }

                actions += BottomActionSheet.ActionItem("Go to artist", R.drawable.ic_artist) {
                    val artists = CanonicalKey.splitArtists(track.artist).ifEmpty { listOf(track.displayArtist) }
                    fun openSingleArtist(targetArtist: String) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val all = store.getAllTracks()
                            val artistTracks = all.filter {
                                CanonicalKey.hasArtist(it.artist, targetArtist) ||
                                    it.displayArtist.equals(targetArtist, ignoreCase = true)
                            }
                            withContext(Dispatchers.Main) {
                                openCollectionDetail(targetArtist, artistTracks, 0L, isPlaylist = false)
                            }
                        }
                    }
                    if (artists.size > 1) {
                        val artistItems = artists.map { art ->
                            BottomActionSheet.ActionItem(art, R.drawable.ic_artist) {
                                openSingleArtist(art)
                            }
                        }
                        BottomActionSheet.show(
                            context = this@MainActivity,
                            title = "Go to artist",
                            subtitle = track.displayTitle,
                            coverBitmap = coverBitmap,
                            fallbackIconRes = R.drawable.ic_artist,
                            items = artistItems
                        )
                    } else {
                        openSingleArtist(artists.firstOrNull() ?: track.displayArtist)
                    }
                }

                actions += BottomActionSheet.ActionItem("Go to album", R.drawable.ic_album) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val all = store.getAllTracks()
                        val albumName = freshTrack.album.ifBlank { "Unknown Album" }
                        val albumTracks = all.filter { it.album.equals(albumName, ignoreCase = true) }
                        withContext(Dispatchers.Main) {
                            openCollectionDetail(albumName, albumTracks, 0L, isPlaylist = false)
                        }
                    }
                }

                val trackGenres = freshTrack.genres
                val genreMenuLabel = if (trackGenres.isNotEmpty()) {
                    val preview = trackGenres.take(3).joinToString(", ") +
                        if (trackGenres.size > 3) " +${trackGenres.size - 3}" else ""
                    "Genres: $preview"
                } else {
                    "Edit genres..."
                }
                actions += BottomActionSheet.ActionItem(genreMenuLabel, R.drawable.ic_tune) {
                    showEditGenresDialog(freshTrack)
                }

                val primaryArt = CanonicalKey.splitArtists(freshTrack.artist)
                    .firstOrNull { it.isNotBlank() && !it.equals("Unknown Artist", ignoreCase = true) }
                if (primaryArt != null) {
                    actions += BottomActionSheet.ActionItem("Set genres for $primaryArt", R.drawable.ic_artist) {
                        showEditGenresDialog(freshTrack, forArtist = primaryArt)
                    }
                }

                actions += BottomActionSheet.ActionItem("Share", R.drawable.ic_share) {
                    shareTrackFile(track)
                }

                actions += BottomActionSheet.ActionItem("Show in file manager", R.drawable.ic_folder) {
                    showTrackInFileManager(track)
                }

                if (isHidden) {
                    actions += BottomActionSheet.ActionItem("Unhide track", R.drawable.ic_check) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            store.unhideTrack(track.id)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Track restored: ${track.displayTitle}", Toast.LENGTH_SHORT).show()
                                if (isHiddenSongsOpen) {
                                    showHiddenSongsScreen()
                                } else {
                                    when (currentTab) {
                                        "home" -> tabViews["home"]?.let { populateHomeView(it, refreshOnlineDiscover = false) }
                                        "library" -> refreshLibraryData?.invoke()
                                        "search" -> refreshSearchData?.invoke()
                                    }
                                }
                            }
                        }
                    }
                } else {
                    actions += BottomActionSheet.ActionItem("Hide track", R.drawable.ic_visibility_off) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            store.hideTrack(track.id)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Hidden from library: ${track.displayTitle}", Toast.LENGTH_SHORT).show()
                                when (currentTab) {
                                    "home" -> tabViews["home"]?.let { populateHomeView(it, refreshOnlineDiscover = false) }
                                    "library" -> refreshLibraryData?.invoke()
                                    "search" -> refreshSearchData?.invoke()
                                }
                            }
                        }
                    }
                }

                actions += BottomActionSheet.ActionItem("Move file to trash", R.drawable.ic_delete, isDestructive = true) {
                    showDeletePermanentlyDialog(track)
                }

                val sub = if (track.album.isNotBlank() && !track.album.equals("Unknown Album", ignoreCase = true)) {
                    "${track.displayArtist} • ${track.album}"
                } else {
                    track.displayArtist
                }

                BottomActionSheet.show(
                    context = this@MainActivity,
                    title = track.displayTitle,
                    subtitle = sub,
                    coverBitmap = coverBitmap,
                    fallbackIconRes = R.drawable.ic_music_note,
                    items = actions
                )
            }
        }
    }

    private fun showAddToPlaylistDialog(track: Track) {
        lifecycleScope.launch(Dispatchers.IO) {
            val playlists = store.getPlaylists()
            withContext(Dispatchers.Main) {
                if (playlists.isEmpty()) {
                    showCreatePlaylistDialog(listOf(track.id))
                    return@withContext
                }
                lifecycleScope.launch(Dispatchers.IO) {
                    val playlistTrackIds = playlists.associate { pl ->
                        pl.id to store.getPlaylistTrackIds(pl.id).toMutableSet()
                    }
                    val names = playlists.map { it.name }.toTypedArray()
                    val checkedItems = playlists.map { pl ->
                        playlistTrackIds[pl.id]?.contains(track.id) == true
                    }.toBooleanArray()

                    withContext(Dispatchers.Main) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Add / Remove from Playlists")
                            .setMultiChoiceItems(names, checkedItems) { _, which, isChecked ->
                                checkedItems[which] = isChecked
                            }
                            .setPositiveButton("Save") { _, _ ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    playlists.forEachIndexed { index, pl ->
                                        val currentIds = store.getPlaylistTrackIds(pl.id).toMutableList()
                                        val shouldHave = checkedItems[index]
                                        if (shouldHave && !currentIds.contains(track.id)) {
                                            currentIds.add(track.id)
                                            store.setPlaylistTracks(pl.id, currentIds)
                                        } else if (!shouldHave && currentIds.contains(track.id)) {
                                            currentIds.remove(track.id)
                                            store.setPlaylistTracks(pl.id, currentIds)
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "Playlists updated", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNeutralButton("+ New Playlist") { _, _ ->
                                showCreatePlaylistDialog(listOf(track.id))
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun shareTrackFile(track: Track) {
        try {
            val path = track.resolvedPath.ifEmpty {
                store.resolvePath(track.rootKey, track.relPath)
            }
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(this, "Audio file not found: ${file.name}", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, track.displayTitle)
                putExtra(Intent.EXTRA_TEXT, "${track.displayTitle} - ${track.displayArtist}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share Music File"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDeletePermanentlyDialog(track: Track) {
        AlertDialog.Builder(this)
            .setTitle("Delete Permanently?")
            .setMessage("Are you sure you want to permanently delete '${track.displayTitle}' from your device and library? This action cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val path = track.resolvedPath.ifEmpty {
                            store.resolvePath(track.rootKey, track.relPath)
                        }
                        val file = File(path)
                        if (file.exists()) {
                            file.delete()
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MainActivity", "Error deleting file: ${e.message}")
                    }
                    store.deleteTracks(listOf(track.id))
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Deleted '${track.displayTitle}'", Toast.LENGTH_SHORT).show()
                        when (currentTab) {
                            "home" -> tabViews["home"]?.let { populateHomeView(it, refreshOnlineDiscover = false) }
                            "library" -> refreshLibraryData?.invoke()
                            "search" -> refreshSearchData?.invoke()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreatePlaylistDialog(initialTrackIds: List<String> = emptyList()) {
        val input = EditText(this).apply {
            hint = "Playlist Name"
            setPadding(20.dpToPx(), 16.dpToPx(), 20.dpToPx(), 16.dpToPx())
        }
        AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    store.createPlaylist(name, initialTrackIds)
                    refreshLibraryData?.invoke()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenamePlaylistDialog(playlistId: Long, currentName: String) {
        val input = EditText(this).apply {
            setText(currentName)
            setPadding(20.dpToPx(), 16.dpToPx(), 20.dpToPx(), 16.dpToPx())
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Playlist")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    store.renamePlaylist(playlistId, newName)
                    openCollectionDetail(newName, emptyList(), playlistId, true)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showImportM3uDialog() {
        val input = EditText(this).apply {
            hint = "Absolute path to .m3u or .m3u8"
            setPadding(20.dpToPx(), 16.dpToPx(), 20.dpToPx(), 16.dpToPx())
        }
        AlertDialog.Builder(this)
            .setTitle("Import M3U8 Playlist")
            .setMessage("Enter the path to the .m3u8 playlist file:")
            .setView(input)
            .setPositiveButton("Import") { _, _ ->
                val path = input.text.toString().trim()
                val f = File(path)
                if (f.exists()) {
                    val plId = store.importPlaylistFromM3u8(f)
                    if (plId > 0) {
                        Toast.makeText(this, "Imported successfully!", Toast.LENGTH_SHORT).show()
                        refreshLibraryData?.invoke()
                    } else {
                        Toast.makeText(this, "No matching songs found in library.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "File not found.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleCustomPathPickerResult(result: androidx.activity.result.ActivityResult) {
        if (result.resultCode != RESULT_OK) return
        val uri = result.data?.data ?: return
        try {
            val flags = result.data?.flags ?: 0
            val takeFlags = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (takeFlags != 0) {
                contentResolver.takePersistableUriPermission(uri, takeFlags)
            }
        } catch (_: Exception) {}
        val resolved = resolveUriToFilePath(uri)
        if (!resolved.isNullOrBlank()) {
            onCustomPathSelected?.invoke(resolved)
        } else {
            Toast.makeText(this, "Could not resolve path from selection", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchCustomPathPicker() {
        val initialUri = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:"
        )
        val treeIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        }
        try {
            customFolderPickerLauncher.launch(treeIntent)
        } catch (_: Exception) {
            val docIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
            }
            try {
                customFilePickerLauncher.launch(docIntent)
            } catch (_: Exception) {
                Toast.makeText(this, "No file manager available to select path", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resolveUriToFilePath(uri: Uri): String? {
        if ("file".equals(uri.scheme, ignoreCase = true)) {
            return uri.path
        }

        if (DocumentsContract.isTreeUri(uri)) {
            val treeId = DocumentsContract.getTreeDocumentId(uri)
            val resolved = resolveDocumentId(uri.authority, treeId)
            if (!resolved.isNullOrBlank()) return resolved
        }

        if (DocumentsContract.isDocumentUri(this, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            val resolved = resolveDocumentId(uri.authority, docId)
            if (!resolved.isNullOrBlank()) return resolved
        }

        return queryDataColumn(uri, null, null)
    }

    private fun resolveDocumentId(authority: String?, docId: String): String? {
        if (docId.startsWith("raw:")) {
            return docId.removePrefix("raw:")
        }

        when (authority) {
            "com.android.externalstorage.documents" -> {
                val split = docId.split(":", limit = 2)
                val type = split.getOrNull(0) ?: return null
                val rel = split.getOrNull(1)?.trimStart('/') ?: ""
                return when {
                    type.equals("primary", ignoreCase = true) -> {
                        val base = Environment.getExternalStorageDirectory().absolutePath
                        if (rel.isEmpty()) base else "$base/$rel"
                    }
                    type.equals("home", ignoreCase = true) -> {
                        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath
                        if (rel.isEmpty()) base else "$base/$rel"
                    }
                    else -> {
                        val volPath = if (rel.isEmpty()) "/storage/$type" else "/storage/$type/$rel"
                        if (File(volPath).exists()) volPath else null
                    }
                }
            }
            "com.android.providers.downloads.documents" -> {
                if (docId.startsWith("msf:") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val fileId = docId.removePrefix("msf:").toLongOrNull()
                    if (fileId != null) {
                        val mediaUri = ContentUris.withAppendedId(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            fileId
                        )
                        queryDataColumn(mediaUri, null, null)?.let { return it }
                    }
                }
                val numId = docId.toLongOrNull()
                if (numId != null) {
                    val downloadPrefixes = listOf(
                        "content://downloads/public_downloads",
                        "content://downloads/my_downloads",
                        "content://downloads/all_downloads"
                    )
                    for (prefix in downloadPrefixes) {
                        try {
                            val contentUri = ContentUris.withAppendedId(Uri.parse(prefix), numId)
                            queryDataColumn(contentUri, null, null)?.let { return it }
                        } catch (_: Exception) {}
                    }
                }
                val docUri = try {
                    DocumentsContract.buildDocumentUri("com.android.providers.downloads.documents", docId)
                } catch (_: Exception) { null }
                if (docUri != null) {
                    val name = queryDisplayName(docUri)
                    if (!name.isNullOrBlank()) {
                        val candidate = File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            name
                        )
                        if (candidate.exists()) return candidate.absolutePath
                    }
                }
            }
            "com.android.providers.media.documents" -> {
                val split = docId.split(":", limit = 2)
                if (split.size == 2) {
                    val contentUri = when (split[0]) {
                        "audio" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Files.getContentUri("external")
                    }
                    queryDataColumn(contentUri, "_id=?", arrayOf(split[1]))?.let { return it }
                }
            }
        }
        return null
    }

    private fun queryDataColumn(uri: Uri, selection: String?, selectionArgs: Array<String>?): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildExternalStorageDocumentUri(dir: File): Uri? {
        val canonicalDir = try {
            dir.canonicalPath
        } catch (_: Exception) {
            dir.absolutePath
        }
        val primaryRoot = try {
            Environment.getExternalStorageDirectory().canonicalPath
        } catch (_: Exception) {
            Environment.getExternalStorageDirectory().absolutePath
        }
        val docId = when {
            canonicalDir == primaryRoot -> "primary:"
            canonicalDir.startsWith("$primaryRoot/") -> {
                "primary:" + canonicalDir.removePrefix("$primaryRoot/")
            }
            canonicalDir.startsWith("/storage/") -> {
                val rest = canonicalDir.removePrefix("/storage/")
                val slashIdx = rest.indexOf('/')
                if (slashIdx < 0) "$rest:" else "${rest.substring(0, slashIdx)}:${rest.substring(slashIdx + 1)}"
            }
            else -> return null
        }
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
    }

    private fun showTrackInFileManager(track: Track) {
        val path = track.resolvedPath.ifEmpty {
            store.resolvePath(track.rootKey, track.relPath)
        }
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Audio file not found: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }
        val parentDir = file.parentFile ?: file
        val dirUri = buildExternalStorageDocumentUri(parentDir)
        if (dirUri == null) {
            Toast.makeText(this, "Folder: ${parentDir.absolutePath}", Toast.LENGTH_LONG).show()
            return
        }

        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(dirUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent("android.provider.action.BROWSE").apply {
                data = dirUri
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, dirUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )

        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // Try next fallback
            }
        }
        Toast.makeText(this, "No file manager app found. Path: ${parentDir.absolutePath}", Toast.LENGTH_LONG).show()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    private fun formatMs(ms: Long): String {
        val sec = (ms / 1000).toInt()
        val m = sec / 60
        val s = sec % 60
        return "%d:%02d".format(m, s)
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
}
