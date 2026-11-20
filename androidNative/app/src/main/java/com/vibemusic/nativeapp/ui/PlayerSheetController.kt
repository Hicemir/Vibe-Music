package com.vibemusic.nativeapp.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import com.vibemusic.nativeapp.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Coordinates the YouTube Music-style seamless morphing transitions:
 *  1) Mini-Player <-> Full Now Playing (continuous expandProgress 0f..1f)
 *  2) Two-stage Queue Slider (continuous queueProgress 0f..2f):
 *     - 0f: Closed (Full Now Playing)
 *     - 1f: Stage 1 (Split view: full-bleed faded artwork + seekbar/controls on top, queue sheet below)
 *     - 2f: Stage 2 (Compact 68dp top player bar + full-height queue sheet)
 */
class PlayerSheetController(
    private val context: Context,
    private val rootLayout: View,
    private val bottomDock: View,
    private val bottomNavBar: View,
    private val miniPlayerContainer: View,
    private val miniPlayerRoot: View,
    private val miniPlayPause: View,
    private val nowPlayingContainer: FrameLayout,
    private val onRequestLyricsOpen: () -> Boolean,
    private val onMiniSwipeNext: () -> Unit = {},
    private val onMiniSwipePrev: () -> Unit = {},
    private val onPeekAdjacentTrack: (Int) -> Triple<String, String, Bitmap?>? = { null },
    private val onCoverDoubleTapSeek: (Long) -> Unit = {}
) {
    // Mini Player Carousel Views
    private val miniTrackViewport: View = miniPlayerRoot.findViewById(R.id.mini_track_viewport)
    private val miniTrackContent: View = miniPlayerRoot.findViewById(R.id.mini_track_content)
    private val miniTrackIncoming: View = miniPlayerRoot.findViewById(R.id.mini_track_incoming)
    private val miniCover: ImageView = miniPlayerRoot.findViewById(R.id.mini_cover)
    private val miniTitle: TextView = miniPlayerRoot.findViewById(R.id.mini_title)
    private val miniArtist: TextView = miniPlayerRoot.findViewById(R.id.mini_artist)
    private val miniIncomingCover: ImageView = miniPlayerRoot.findViewById(R.id.mini_incoming_cover)
    private val miniIncomingTitle: TextView = miniPlayerRoot.findViewById(R.id.mini_incoming_title)
    private val miniIncomingArtist: TextView = miniPlayerRoot.findViewById(R.id.mini_incoming_artist)

    // Now Playing Views
    private val npRoot: View = nowPlayingContainer.findViewById(R.id.now_playing_root)
    private val npBgView: View = nowPlayingContainer.findViewById(R.id.np_bg_view)
    private val npCover: ImageView = nowPlayingContainer.findViewById(R.id.np_cover)
    private val npStage1ArtScrim: View = nowPlayingContainer.findViewById(R.id.np_stage1_art_scrim)
    private val npMiniFadeRow: View = nowPlayingContainer.findViewById(R.id.np_mini_fade_row)
    private val npMiniFadeTitle: TextView = nowPlayingContainer.findViewById(R.id.np_mini_fade_title)
    private val npMiniFadeArtist: TextView = nowPlayingContainer.findViewById(R.id.np_mini_fade_artist)
    private val npMiniFadePlayPause: ImageView = nowPlayingContainer.findViewById(R.id.np_mini_fade_play_pause)

    private val npMainPlayer: View = nowPlayingContainer.findViewById(R.id.np_main_player)
    private val npTopBar: View = nowPlayingContainer.findViewById(R.id.np_top_bar)
    private val npBody: View = nowPlayingContainer.findViewById(R.id.np_body)
    private val npCoverSlot: View = nowPlayingContainer.findViewById(R.id.np_cover_slot)
    private val npMetaGroup: View = nowPlayingContainer.findViewById(R.id.np_meta_group)
    private val npPillsScroll: View = nowPlayingContainer.findViewById(R.id.np_pills_scroll)
    private val npVolumeBarContainer: View = nowPlayingContainer.findViewById(R.id.np_volume_bar_container)
    private val npTransportGroup: View = nowPlayingContainer.findViewById(R.id.np_transport_group)
    private val npBottomQueuePeek: View = nowPlayingContainer.findViewById(R.id.np_bottom_queue_peek)

    private val npQueueCompactHeader: View = nowPlayingContainer.findViewById(R.id.np_queue_compact_header)
    private val npCompactTitle: TextView = nowPlayingContainer.findViewById(R.id.np_compact_title)
    private val npCompactArtist: TextView = nowPlayingContainer.findViewById(R.id.np_compact_artist)
    private val npCompactPlayPause: ImageView = nowPlayingContainer.findViewById(R.id.np_compact_play_pause)
    private val npCompactNext: ImageView = nowPlayingContainer.findViewById(R.id.np_compact_next)

    private val npQueueSlider: LinearLayout = nowPlayingContainer.findViewById(R.id.np_queue_slider)
    private val npQueueDragHitbox: View = nowPlayingContainer.findViewById(R.id.np_queue_drag_hitbox)
    private val npQueueNowPlayingContainer: View = nowPlayingContainer.findViewById(R.id.np_queue_now_playing_container)
    private val npQueueRecycler: RecyclerView = nowPlayingContainer.findViewById(R.id.np_queue_recycler)
    private val npLyricsSliderContainer: View = nowPlayingContainer.findViewById(R.id.np_lyrics_slider_container)

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // Progress state
    var expandProgress: Float = 0f
        private set
    var queueProgress: Float = 0f
        private set

    val isExpanded: Boolean
        get() = expandProgress > 0.01f

    val isQueueOpen: Boolean
        get() = queueProgress > 0.01f

    private var expandAnimator: ValueAnimator? = null
    private var queueAnimator: ValueAnimator? = null
    private var colorAnimator: ValueAnimator? = null
    private var miniSwipeAnimator: ValueAnimator? = null

    private var isDraggingExpand = false
    private var isDraggingQueue = false

    // Measured geometry
    private var rootW = 0
    private var rootH = 0
    private var miniH = 60.dpToPx()
    private var navH = 56.dpToPx()
    private var miniTop = 0f
    private var miniCoverSize = 44.dpToPx().toFloat()
    private var miniCoverLeft = 12.dpToPx().toFloat()
    private var miniCoverTop = 0f

    private var fullCoverSize = 300.dpToPx().toFloat()
    private var fullCoverLeft = 24.dpToPx().toFloat()
    private var fullCoverTop = 80.dpToPx().toFloat()

    private var metaTopQ0 = 0f
    private var transportTopQ0 = 0f
    private var metaTargetTopQ1 = 0f
    private var transportTargetTopQ1 = 0f

    private var stage1SheetTop = 0f
    private var stage2SheetTop = 68.dpToPx().toFloat()
    private var compactHeaderH = 68.dpToPx().toFloat()
    private var stage2CoverSize = 44.dpToPx().toFloat()
    private var stage2CoverLeft = 16.dpToPx().toFloat()
    private var stage2CoverTop = 12.dpToPx().toFloat()

    private var currentOutlineRadiusPx = 12.dpToPx().toFloat()
    private var lastAppliedOutlineRadiusPx = -1f
    private var lastRecyclerPadBottom = -1

    // Dynamic album colors
    internal val defaultBgColor = ContextCompat.getColor(context, R.color.bg_primary)
    internal val defaultSurfaceColor = ContextCompat.getColor(context, R.color.bg_surface_elevated)
    private var currentBgColor = defaultBgColor
    private var currentSurfaceColor = defaultSurfaceColor

    init {
        setupCoverOutline()
        applyColors(defaultBgColor, defaultSurfaceColor)
        setupGeometryListener()
        setupTouchListeners()
        premeasureNowPlaying()
    }

    private fun premeasureNowPlaying() {
        if (nowPlayingContainer.visibility == View.GONE) {
            nowPlayingContainer.alpha = 0f
            nowPlayingContainer.visibility = View.INVISIBLE
            nowPlayingContainer.post {
                measureGeometry()
                if (expandProgress <= 0.001f && !isDraggingExpand && expandAnimator?.isRunning != true) {
                    nowPlayingContainer.visibility = View.GONE
                }
                nowPlayingContainer.alpha = 1f
                applyLayoutTransforms()
            }
        }
    }

    private fun setupCoverOutline() {
        npCover.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, currentOutlineRadiusPx)
            }
        }
        npCover.clipToOutline = true
    }

    private fun setupGeometryListener() {
        val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            measureGeometry()
            applyLayoutTransforms()
        }
        rootLayout.addOnLayoutChangeListener(listener)
        nowPlayingContainer.addOnLayoutChangeListener(listener)
        npCoverSlot.addOnLayoutChangeListener(listener)
    }

    private fun measureGeometry() {
        val w = rootLayout.width
        val h = rootLayout.height
        if (w <= 0 || h <= 0) return

        rootW = w
        rootH = h
        miniH = miniPlayerContainer.height.takeIf { it > 0 } ?: 60.dpToPx()
        navH = bottomNavBar.height.takeIf { it > 0 } ?: 56.dpToPx()
        miniTop = (rootH - navH - miniH).toFloat()
        miniCoverSize = 44.dpToPx().toFloat()
        miniCoverLeft = 12.dpToPx().toFloat()
        miniCoverTop = miniTop + (miniH - miniCoverSize) / 2f

        compactHeaderH = 68.dpToPx().toFloat()
        stage2SheetTop = compactHeaderH
        stage2CoverSize = 44.dpToPx().toFloat()
        stage2CoverLeft = 16.dpToPx().toFloat()
        stage2CoverTop = (compactHeaderH - stage2CoverSize) / 2f

        // Measure Stage 0 cover slot inside npMainPlayer -> npBody
        val npW = nowPlayingContainer.width.takeIf { it > 0 } ?: rootW
        val npH = nowPlayingContainer.height.takeIf { it > 0 } ?: rootH

        val slotW = npCoverSlot.width
        val slotH = npCoverSlot.height
        if (slotW > 0 && slotH > 0) {
            val newFullSize = min(slotW, slotH).toFloat().coerceAtLeast(120.dpToPx().toFloat())
            fullCoverSize = newFullSize
            val slotLeftInRoot = npMainPlayer.left + npBody.left + npCoverSlot.left
            val slotTopInRoot = npMainPlayer.top + npBody.top + npCoverSlot.top
            fullCoverLeft = slotLeftInRoot + (slotW - fullCoverSize) / 2f
            fullCoverTop = slotTopInRoot + (slotH - fullCoverSize) / 2f

            val lp = npCover.layoutParams
            val targetInt = fullCoverSize.toInt()
            if (lp.width != targetInt || lp.height != targetInt) {
                lp.width = targetInt
                lp.height = targetInt
                npCover.layoutParams = lp
            }
        }

        metaTopQ0 = (npMainPlayer.top + npBody.top + npMetaGroup.top).toFloat()
        transportTopQ0 = (npMainPlayer.top + npBody.top + npTransportGroup.top).toFloat()

        val topBarH = npTopBar.height.takeIf { it > 0 } ?: 56.dpToPx()
        val metaH = npMetaGroup.height.takeIf { it > 0 } ?: 52.dpToPx()
        val transportH = npTransportGroup.height.takeIf { it > 0 } ?: 132.dpToPx()
        val minStage1Top = (topBarH + 92.dpToPx() + metaH + transportH + 16.dpToPx()).toFloat()
        stage1SheetTop = max(npH * 0.54f, minStage1Top).coerceAtMost(npH * 0.64f)

        transportTargetTopQ1 = stage1SheetTop - transportH - 8.dpToPx()
        metaTargetTopQ1 = transportTargetTopQ1 - metaH - 10.dpToPx()

        val scrimLp = npStage1ArtScrim.layoutParams
        val desiredScrimH = stage1SheetTop.toInt().coerceAtLeast(1)
        if (scrimLp.height != desiredScrimH) {
            scrimLp.height = desiredScrimH
            npStage1ArtScrim.layoutParams = scrimLp
        }
        val _unused = npW
    }

    private fun setupTouchListeners() {
        setupMiniPlayerTouch()
        setupNowPlayingTouch()
        setupBottomPeekTouch()
        setupQueueDragTouch()
        setupCompactHeaderActions()
    }

    private fun setupMiniPlayerTouch() {
        var startY = 0f
        var startX = 0f
        var dragMode = 0 // 0=none, 1=vertical expand, 2=horizontal swipe
        var tracker: VelocityTracker? = null
        var peekedDir = 0
        var peekedTrackInfo: Triple<String, String, Bitmap?>? = null

        miniPlayerContainer.setOnTouchListener { _, event ->
            val playRect = Rect()
            miniPlayPause.getHitRect(playRect)
            if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                playRect.contains(event.x.toInt(), event.y.toInt())
            ) {
                return@setOnTouchListener false
            }

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    expandAnimator?.cancel()
                    if (miniSwipeAnimator?.isRunning == true) {
                        miniSwipeAnimator?.end()
                    }
                    startY = event.rawY
                    startX = event.rawX
                    dragMode = 0
                    peekedDir = 0
                    peekedTrackInfo = null
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().apply { addMovement(event) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dy = startY - event.rawY
                    val dx = event.rawX - startX
                    if (dragMode == 0) {
                        if (dy > touchSlop && dy > abs(dx)) {
                            dragMode = 1
                            isDraggingExpand = true
                        } else if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                            dragMode = 2
                        }
                    }
                    when (dragMode) {
                        1 -> {
                            val travel = miniTop.coerceAtLeast(1f)
                            expandProgress = (dy / travel).coerceIn(0f, 1f)
                            applyLayoutTransforms()
                        }
                        2 -> {
                            val viewportW = miniTrackViewport.width.toFloat().coerceAtLeast(1f)
                            val neededDir = when {
                                dx < 0f -> 1
                                dx > 0f -> -1
                                else -> 0
                            }
                            if (neededDir != 0 && neededDir != peekedDir) {
                                peekedDir = neededDir
                                val adj = onPeekAdjacentTrack(neededDir)
                                peekedTrackInfo = adj
                                if (adj != null) {
                                    miniIncomingTitle.text = adj.first
                                    miniIncomingArtist.text = adj.second
                                    if (adj.third != null) {
                                        miniIncomingCover.setImageBitmap(adj.third)
                                    } else {
                                        miniIncomingCover.setImageResource(R.drawable.ic_music_note)
                                    }
                                    miniTrackIncoming.visibility = View.VISIBLE
                                } else {
                                    miniTrackIncoming.visibility = View.GONE
                                }
                            }

                            val adjInfo = peekedTrackInfo
                            if (adjInfo != null) {
                                val clampedDx = dx.coerceIn(-viewportW, viewportW)
                                val progress = (abs(clampedDx) / viewportW).coerceIn(0f, 1f)
                                miniTrackContent.translationX = clampedDx
                                miniTrackContent.alpha = (1f - progress * 0.35f).coerceIn(0.4f, 1f)
                                val incomingOffset = if (clampedDx < 0f) viewportW + clampedDx else -viewportW + clampedDx
                                miniTrackIncoming.translationX = incomingOffset
                                miniTrackIncoming.alpha = (0.15f + progress * 0.85f).coerceIn(0.15f, 1f)
                            } else {
                                miniTrackContent.translationX = (dx * 0.25f).coerceIn(-viewportW * 0.25f, viewportW * 0.25f)
                                miniTrackContent.alpha = 1f
                                miniTrackIncoming.visibility = View.GONE
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null

                    val currentMode = dragMode
                    dragMode = 0
                    when (currentMode) {
                        1 -> {
                            isDraggingExpand = false
                            val shouldExpand = when {
                                vy < -600f -> true
                                vy > 600f -> false
                                else -> expandProgress >= 0.30f
                            }
                            if (shouldExpand) expandNowPlaying() else collapseNowPlaying()
                        }
                        2 -> {
                            val viewportW = miniTrackViewport.width.toFloat().coerceAtLeast(1f)
                            val totalDx = event.rawX - startX
                            val dir = if (totalDx < 0f) 1 else -1
                            val committedTrack = peekedTrackInfo
                            val shouldCommit = event.actionMasked == MotionEvent.ACTION_UP &&
                                committedTrack != null &&
                                peekedDir == dir &&
                                (abs(totalDx) > 42.dpToPx() || (abs(vx) > 450f && (vx < 0f) == (totalDx < 0f)))

                            val startContentX = miniTrackContent.translationX
                            val startContentAlpha = miniTrackContent.alpha
                            val startIncomingX = miniTrackIncoming.translationX
                            val startIncomingAlpha = miniTrackIncoming.alpha

                            miniSwipeAnimator?.cancel()
                            if (shouldCommit && committedTrack != null) {
                                val targetOutX = if (dir == 1) -viewportW else viewportW
                                miniSwipeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                                    duration = 190L
                                    interpolator = DecelerateInterpolator(1.5f)
                                    addUpdateListener { anim ->
                                        val f = anim.animatedValue as Float
                                        miniTrackContent.translationX = lerp(startContentX, targetOutX, f)
                                        miniTrackContent.alpha = lerp(startContentAlpha, 0.2f, f)
                                        miniTrackIncoming.translationX = lerp(startIncomingX, 0f, f)
                                        miniTrackIncoming.alpha = lerp(startIncomingAlpha, 1f, f)
                                    }
                                    var handledEnd = false
                                    var wasCancelled = false
                                    addListener(object : AnimatorListenerAdapter() {
                                        override fun onAnimationCancel(animation: Animator) {
                                            wasCancelled = true
                                        }
                                        override fun onAnimationEnd(animation: Animator) {
                                            if (handledEnd || wasCancelled) return
                                            handledEnd = true
                                            if (miniSwipeAnimator === animation) {
                                                miniSwipeAnimator = null
                                            }
                                            miniTitle.text = committedTrack.first
                                            miniArtist.text = committedTrack.second
                                            if (committedTrack.third != null) {
                                                miniCover.setImageBitmap(committedTrack.third)
                                            } else {
                                                miniCover.setImageResource(R.drawable.ic_music_note)
                                            }
                                            miniTrackContent.translationX = 0f
                                            miniTrackContent.alpha = 1f
                                            miniTrackIncoming.visibility = View.GONE
                                            miniTrackIncoming.translationX = 0f
                                            miniTrackIncoming.alpha = 1f
                                            if (dir == 1) {
                                                onMiniSwipeNext()
                                            } else {
                                                onMiniSwipePrev()
                                            }
                                        }
                                    })
                                    start()
                                }
                            } else {
                                val snapBackIncomingX = if (peekedDir == 1) viewportW else -viewportW
                                val hasIncoming = miniTrackIncoming.visibility == View.VISIBLE
                                miniSwipeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                                    duration = 160L
                                    interpolator = DecelerateInterpolator(1.5f)
                                    addUpdateListener { anim ->
                                        val f = anim.animatedValue as Float
                                        miniTrackContent.translationX = lerp(startContentX, 0f, f)
                                        miniTrackContent.alpha = lerp(startContentAlpha, 1f, f)
                                        if (hasIncoming) {
                                            miniTrackIncoming.translationX = lerp(startIncomingX, snapBackIncomingX, f)
                                            miniTrackIncoming.alpha = lerp(startIncomingAlpha, 0f, f)
                                        }
                                    }
                                    addListener(object : AnimatorListenerAdapter() {
                                        override fun onAnimationEnd(animation: Animator) {
                                            if (miniSwipeAnimator === animation) {
                                                miniSwipeAnimator = null
                                            }
                                            miniTrackContent.translationX = 0f
                                            miniTrackContent.alpha = 1f
                                            miniTrackIncoming.visibility = View.GONE
                                            miniTrackIncoming.translationX = 0f
                                            miniTrackIncoming.alpha = 1f
                                        }
                                    })
                                    start()
                                }
                            }
                            peekedDir = 0
                            peekedTrackInfo = null
                        }
                        else -> {
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                expandNowPlaying()
                            }
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupNowPlayingTouch() {
        var startY = 0f
        var startX = 0f
        var mode = 0 // 0=none, 1=collapseNowPlaying, 2=openQueue
        var tracker: VelocityTracker? = null
        var lastCoverTapTime = 0L
        var lastCoverTapX = 0f
        var lastCoverTapY = 0f

        val touchListener = View.OnTouchListener { _, event ->
            if (npLyricsSliderContainer.visibility == View.VISIBLE) {
                return@OnTouchListener false
            }
            if (queueProgress > 0.01f) {
                return@OnTouchListener false
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    startX = event.rawX
                    mode = 0
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().apply { addMovement(event) }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dy = event.rawY - startY
                    val dx = abs(event.rawX - startX)
                    if (mode == 0 && abs(dy) > touchSlop && abs(dy) > dx) {
                        if (dy > 0f) {
                            mode = 1
                            isDraggingExpand = true
                            expandAnimator?.cancel()
                        } else {
                            mode = 2
                            isDraggingQueue = true
                            queueAnimator?.cancel()
                        }
                    }
                    when (mode) {
                        1 -> {
                            val travel = miniTop.coerceAtLeast(1f)
                            expandProgress = (1f - (dy / travel)).coerceIn(0f, 1f)
                            applyLayoutTransforms()
                            true
                        }
                        2 -> {
                            val pullUp = (-dy).coerceAtLeast(0f)
                            queueProgress = sheetTopToQueueProgress((rootH - pullUp).coerceAtLeast(stage2SheetTop))
                            applyLayoutTransforms()
                            true
                        }
                        else -> false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null

                    val currentMode = mode
                    mode = 0
                    when (currentMode) {
                        1 -> {
                            isDraggingExpand = false
                            val shouldCollapse = vy > 600f || expandProgress < 0.72f
                            if (shouldCollapse) collapseNowPlaying() else expandNowPlaying()
                            true
                        }
                        2 -> {
                            isDraggingQueue = false
                            snapQueueFromDrag(startProgress = 0f, velocityY = vy)
                            true
                        }
                        else -> {
                            if (event.actionMasked == MotionEvent.ACTION_UP &&
                                abs(event.rawX - startX) < touchSlop &&
                                abs(event.rawY - startY) < touchSlop &&
                                expandProgress > 0.9f &&
                                queueProgress < 0.05f &&
                                npLyricsSliderContainer.visibility != View.VISIBLE
                            ) {
                                val inCoverX = event.x >= fullCoverLeft && event.x <= (fullCoverLeft + fullCoverSize)
                                val inCoverY = event.y >= fullCoverTop && event.y <= (fullCoverTop + fullCoverSize)
                                if (inCoverX && inCoverY) {
                                    val now = SystemClock.uptimeMillis()
                                    val centerX = fullCoverLeft + fullCoverSize / 2f
                                    val isRightSide = event.x >= centerX
                                    val wasRightSide = lastCoverTapX >= centerX
                                    if (now - lastCoverTapTime <= 320L && isRightSide == wasRightSide) {
                                        lastCoverTapTime = 0L
                                        val deltaMs = if (isRightSide) 5000L else -5000L
                                        onCoverDoubleTapSeek(deltaMs)
                                    } else {
                                        lastCoverTapTime = now
                                        lastCoverTapX = event.x
                                        lastCoverTapY = event.y
                                    }
                                    return@OnTouchListener true
                                }
                            }
                            false
                        }
                    }
                }
                else -> false
            }
        }
        npRoot.setOnTouchListener(touchListener)
    }

    private fun setupBottomPeekTouch() {
        var startY = 0f
        var dragging = false
        var tracker: VelocityTracker? = null

        npBottomQueuePeek.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    queueAnimator?.cancel()
                    startY = event.rawY
                    dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().apply { addMovement(event) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val pullUp = startY - event.rawY
                    if (!dragging && pullUp > touchSlop) {
                        dragging = true
                        isDraggingQueue = true
                    }
                    if (dragging) {
                        val sheetTop = (rootH - pullUp).coerceIn(stage2SheetTop, rootH.toFloat())
                        queueProgress = sheetTopToQueueProgress(sheetTop)
                        applyLayoutTransforms()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null

                    if (dragging) {
                        dragging = false
                        isDraggingQueue = false
                        snapQueueFromDrag(startProgress = 0f, velocityY = vy)
                        true
                    } else {
                        // Plain tap on bottom peek does nothing as previously requested
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun setupQueueDragTouch() {
        var startY = 0f
        var startSheetTop = 0f
        var startProgress = 0f
        var dragging = false
        var tracker: VelocityTracker? = null

        val headerDragListener = View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    queueAnimator?.cancel()
                    startY = event.rawY
                    startProgress = queueProgress
                    startSheetTop = queueProgressToSheetTop(queueProgress)
                    dragging = false
                    tracker?.recycle()
                    tracker = VelocityTracker.obtain().apply { addMovement(event) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(event)
                    val dy = event.rawY - startY
                    if (!dragging && abs(dy) > touchSlop) {
                        dragging = true
                        isDraggingQueue = true
                    }
                    if (dragging) {
                        val newTop = (startSheetTop + dy).coerceIn(stage2SheetTop, rootH.toFloat())
                        queueProgress = sheetTopToQueueProgress(newTop)
                        applyLayoutTransforms()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(event)
                    tracker?.computeCurrentVelocity(1000)
                    val vy = tracker?.yVelocity ?: 0f
                    tracker?.recycle()
                    tracker = null

                    if (dragging) {
                        dragging = false
                        isDraggingQueue = false
                        snapQueueFromDrag(startProgress = startProgress, velocityY = vy)
                    }
                    true
                }
                else -> false
            }
        }

        npQueueDragHitbox.setOnTouchListener(headerDragListener)
        npQueueNowPlayingContainer.setOnTouchListener(headerDragListener)

        // Nested drag inside RecyclerView:
        //  - If at Stage 1 (< 1.98f) and pulling UP -> smoothly expand Stage 1 -> Stage 2
        //  - If at top of list (!canScrollVertically(-1)) and pulling DOWN -> collapse Stage 2 -> Stage 1 -> Stage 0
        var rvStartY = 0f
        var rvStartX = 0f
        var rvStartProgress = 0f
        var rvStartSheetTop = 0f
        var rvDraggingSheet = false
        var rvIgnoreSynthetic = false
        var rvTracker: VelocityTracker? = null

        npQueueRecycler.setOnTouchListener { _, event ->
            if (rvIgnoreSynthetic) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rvStartY = event.rawY
                    rvStartX = event.rawX
                    rvStartProgress = queueProgress
                    rvStartSheetTop = queueProgressToSheetTop(queueProgress)
                    rvDraggingSheet = false
                    rvTracker?.recycle()
                    rvTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    rvTracker?.addMovement(event)
                    val dy = event.rawY - rvStartY
                    val dx = abs(event.rawX - rvStartX)

                    if (!rvDraggingSheet && abs(dy) > touchSlop && abs(dy) > dx) {
                        val pullingDownAtTop = dy > 0f && !npQueueRecycler.canScrollVertically(-1)
                        val pullingUpInStage1 = dy < 0f && queueProgress < 1.98f
                        if (pullingDownAtTop || pullingUpInStage1) {
                            rvDraggingSheet = true
                            isDraggingQueue = true
                            queueAnimator?.cancel()
                            rvStartY = event.rawY
                            rvStartProgress = queueProgress
                            rvStartSheetTop = queueProgressToSheetTop(queueProgress)

                            // Cancel child pressed/scroll state cleanly
                            val now = SystemClock.uptimeMillis()
                            val cancelEv = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, event.x, event.y, 0)
                            rvIgnoreSynthetic = true
                            npQueueRecycler.onTouchEvent(cancelEv)
                            rvIgnoreSynthetic = false
                            cancelEv.recycle()
                        }
                    }

                    if (rvDraggingSheet) {
                        val dragDy = event.rawY - rvStartY
                        val newTop = (rvStartSheetTop + dragDy).coerceIn(stage2SheetTop, rootH.toFloat())
                        queueProgress = sheetTopToQueueProgress(newTop)
                        applyLayoutTransforms()
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    rvTracker?.addMovement(event)
                    rvTracker?.computeCurrentVelocity(1000)
                    val vy = rvTracker?.yVelocity ?: 0f
                    rvTracker?.recycle()
                    rvTracker = null

                    if (rvDraggingSheet) {
                        rvDraggingSheet = false
                        isDraggingQueue = false
                        snapQueueFromDrag(startProgress = rvStartProgress, velocityY = vy)
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun setupCompactHeaderActions() {
        npQueueCompactHeader.setOnClickListener {
            animateQueueTo(0f)
        }
        npCompactPlayPause.setOnClickListener {
            miniPlayPause.performClick()
        }
        npCompactNext.setOnClickListener {
            onMiniSwipeNext()
        }
        npMiniFadePlayPause.setOnClickListener {
            miniPlayPause.performClick()
        }
    }

    private fun snapQueueFromDrag(startProgress: Float, velocityY: Float) {
        val target = when {
            velocityY < -700f -> {
                if (startProgress < 0.8f && queueProgress < 1.25f) 1f else 2f
            }
            velocityY > 700f -> {
                if (startProgress > 1.2f && queueProgress > 0.75f) 1f else 0f
            }
            queueProgress < 0.50f -> 0f
            queueProgress < 1.45f -> 1f
            else -> 2f
        }
        animateQueueTo(target)
    }

    fun expandNowPlaying() {
        nowPlayingContainer.alpha = 1f
        measureGeometry()
        animateExpandTo(1f)
    }

    fun collapseNowPlaying() {
        isDraggingExpand = false
        isDraggingQueue = false
        if (queueProgress > 0f) {
            queueAnimator?.cancel()
            queueProgress = 0f
        }
        animateExpandTo(0f)
    }

    fun openQueueStage1() {
        if (expandProgress < 1f) expandNowPlaying()
        animateQueueTo(1f)
    }

    fun openQueueStage2() {
        if (expandProgress < 1f) expandNowPlaying()
        animateQueueTo(2f)
    }

    fun closeQueue(animated: Boolean = true) {
        if (!animated) {
            queueAnimator?.cancel()
            queueProgress = 0f
            isDraggingQueue = false
            applyLayoutTransforms()
            return
        }
        animateQueueTo(0f)
    }

    fun handleBackPressed(): Boolean {
        if (queueProgress > 0.05f) {
            closeQueue(animated = true)
            return true
        }
        if (expandProgress > 0.05f) {
            collapseNowPlaying()
            return true
        }
        return false
    }

    private fun animateExpandTo(target: Float) {
        expandAnimator?.cancel()
        val start = expandProgress
        if (abs(target - start) < 0.005f) {
            expandProgress = target
            isDraggingExpand = false
            applyLayoutTransforms()
            return
        }
        expandAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = (280 * abs(target - start)).toLong().coerceIn(140L, 280L)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { anim ->
                expandProgress = anim.animatedValue as Float
                applyLayoutTransforms()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (expandAnimator === animation) {
                        expandAnimator = null
                        expandProgress = target
                        isDraggingExpand = false
                        applyLayoutTransforms()
                    }
                }
                override fun onAnimationCancel(animation: Animator) {
                    if (expandAnimator === animation) {
                        expandAnimator = null
                        isDraggingExpand = false
                    }
                }
            })
            start()
        }
    }

    private fun animateQueueTo(target: Float) {
        queueAnimator?.cancel()
        val start = queueProgress
        if (abs(target - start) < 0.005f) {
            queueProgress = target
            isDraggingQueue = false
            applyLayoutTransforms()
            return
        }
        queueAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = (240 * abs(target - start).coerceAtMost(1.3f)).toLong().coerceIn(150L, 300L)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { anim ->
                queueProgress = anim.animatedValue as Float
                applyLayoutTransforms()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (queueAnimator === animation) {
                        queueAnimator = null
                        queueProgress = target
                        isDraggingQueue = false
                        applyLayoutTransforms()
                    }
                }
                override fun onAnimationCancel(animation: Animator) {
                    if (queueAnimator === animation) {
                        queueAnimator = null
                        isDraggingQueue = false
                    }
                }
            })
            start()
        }
    }

    private fun queueProgressToSheetTop(q: Float): Float {
        val h = rootH.toFloat().coerceAtLeast(1f)
        return if (q <= 1f) {
            lerp(h, stage1SheetTop, q.coerceIn(0f, 1f))
        } else {
            lerp(stage1SheetTop, stage2SheetTop, (q - 1f).coerceIn(0f, 1f))
        }
    }

    private fun sheetTopToQueueProgress(sheetTop: Float): Float {
        val h = rootH.toFloat().coerceAtLeast(1f)
        return if (sheetTop >= stage1SheetTop) {
            val denom = (h - stage1SheetTop).coerceAtLeast(1f)
            ((h - sheetTop) / denom).coerceIn(0f, 1f)
        } else {
            val denom = (stage1SheetTop - stage2SheetTop).coerceAtLeast(1f)
            1f + ((stage1SheetTop - sheetTop) / denom).coerceIn(0f, 1f)
        }
    }

    private fun applyLayoutTransforms() {
        if (rootW <= 0 || rootH <= 0) return

        val p = expandProgress.coerceIn(0f, 1f)
        val q = queueProgress.coerceIn(0f, 2f)
        val animatingExpand = expandAnimator?.isRunning == true
        val animatingQueue = queueAnimator?.isRunning == true

        // 1. Resting collapsed state
        if (p <= 0.001f && !isDraggingExpand && !animatingExpand) {
            nowPlayingContainer.visibility = View.GONE
            nowPlayingContainer.clipBounds = null
            miniPlayerContainer.visibility = View.VISIBLE
            bottomNavBar.translationY = 0f
            npQueueSlider.visibility = View.GONE
            return
        }

        if (nowPlayingContainer.visibility != View.VISIBLE) {
            nowPlayingContainer.visibility = View.VISIBLE
        }
        miniPlayerContainer.visibility = View.INVISIBLE
        bottomNavBar.translationY = navH * p

        // 2. Clip Bounds for Now Playing sheet (reveals home/library above rising sheet!)
        val sheetTop = lerp(miniTop, 0f, p)
        val sheetBottom = lerp(miniTop + miniH, rootH.toFloat(), p)
        if (p < 0.999f) {
            nowPlayingContainer.clipBounds = Rect(0, sheetTop.toInt(), rootW, sheetBottom.toInt())
        } else {
            nowPlayingContainer.clipBounds = null
        }

        // 3. Mini-player fade row during early pull-up
        if (p < 0.35f) {
            npMiniFadeRow.visibility = View.VISIBLE
            npMiniFadeRow.translationY = sheetTop
            npMiniFadeRow.alpha = (1f - p * 3.5f).coerceIn(0f, 1f)
        } else {
            npMiniFadeRow.visibility = View.GONE
        }

        // 4. Cover Art morph (Mini -> Stage 0 -> Stage 1 Full-Bleed -> Stage 2 Compact 44dp)
        val coverLeft: Float
        val coverTop: Float
        val coverSize: Float
        val coverRadius: Float

        if (p < 0.999f) {
            coverLeft = lerp(miniCoverLeft, fullCoverLeft, p)
            coverTop = lerp(miniCoverTop, fullCoverTop, p)
            coverSize = lerp(miniCoverSize, fullCoverSize, p)
            coverRadius = lerp(6.dpToPx().toFloat(), 12.dpToPx().toFloat(), p)
        } else if (q <= 1f) {
            coverLeft = lerp(fullCoverLeft, 0f, q)
            coverTop = lerp(fullCoverTop, 0f, q)
            coverSize = lerp(fullCoverSize, rootW.toFloat(), q)
            coverRadius = lerp(12.dpToPx().toFloat(), 0f, q)
        } else {
            val t = q - 1f
            coverLeft = lerp(0f, stage2CoverLeft, t)
            coverTop = lerp(0f, stage2CoverTop, t)
            coverSize = lerp(rootW.toFloat(), stage2CoverSize, t)
            coverRadius = lerp(0f, 6.dpToPx().toFloat(), t)
        }

        val scale = (coverSize / fullCoverSize.coerceAtLeast(1f)).coerceAtLeast(0.05f)
        npCover.pivotX = 0f
        npCover.pivotY = 0f
        npCover.scaleX = scale
        npCover.scaleY = scale
        npCover.translationX = coverLeft
        npCover.translationY = coverTop

        currentOutlineRadiusPx = (coverRadius / scale).coerceAtLeast(0f)
        if (abs(currentOutlineRadiusPx - lastAppliedOutlineRadiusPx) > 0.35f) {
            lastAppliedOutlineRadiusPx = currentOutlineRadiusPx
            npCover.invalidateOutline()
        }

        // 5. Main Player Controls Alpha & Stage 1 Translations
        val pControlsAlpha = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val pSlideOffset = (1f - pControlsAlpha) * 20.dpToPx()

        val qTopAlpha = if (q <= 1f) 1f else (1f - (q - 1f) * 3.0f).coerceIn(0f, 1f)
        npTopBar.alpha = pControlsAlpha * qTopAlpha
        npTopBar.visibility = if (npTopBar.alpha > 0.01f) View.VISIBLE else View.INVISIBLE

        val qMetaAlpha = if (q <= 1f) 1f else (1f - (q - 1f) * 2.6f).coerceIn(0f, 1f)
        npMetaGroup.alpha = pControlsAlpha * qMetaAlpha
        npMetaGroup.visibility = if (npMetaGroup.alpha > 0.01f) View.VISIBLE else View.INVISIBLE
        npMetaGroup.translationY = pSlideOffset + if (q <= 1f) {
            lerp(0f, metaTargetTopQ1 - metaTopQ0, q)
        } else {
            (metaTargetTopQ1 - metaTopQ0) - (q - 1f) * 44.dpToPx()
        }

        val qPillsAlpha = (1f - q / 0.42f).coerceIn(0f, 1f)
        npPillsScroll.alpha = pControlsAlpha * qPillsAlpha
        npPillsScroll.visibility = if (npPillsScroll.alpha > 0.01f) View.VISIBLE else View.INVISIBLE
        npVolumeBarContainer.alpha = pControlsAlpha * qPillsAlpha
        if (q > 0.42f && npVolumeBarContainer.visibility == View.VISIBLE) {
            npVolumeBarContainer.visibility = View.GONE
        }

        val qTransAlpha = if (q <= 1f) 1f else (1f - (q - 1f) * 2.6f).coerceIn(0f, 1f)
        npTransportGroup.alpha = pControlsAlpha * qTransAlpha
        npTransportGroup.visibility = if (npTransportGroup.alpha > 0.01f) View.VISIBLE else View.INVISIBLE
        npTransportGroup.translationY = pSlideOffset + if (q <= 1f) {
            lerp(0f, transportTargetTopQ1 - transportTopQ0, q)
        } else {
            (transportTargetTopQ1 - transportTopQ0) - (q - 1f) * 32.dpToPx()
        }

        val qPeekAlpha = (1f - q * 4.5f).coerceIn(0f, 1f)
        npBottomQueuePeek.alpha = pControlsAlpha * qPeekAlpha
        npBottomQueuePeek.visibility = if (npBottomQueuePeek.alpha > 0.01f) View.VISIBLE else View.INVISIBLE

        // 6. Stage 1 Artwork Scrim
        val scrimAlpha = if (q <= 1f) q else (1f - (q - 1f) * 2.2f).coerceIn(0f, 1f)
        npStage1ArtScrim.alpha = pControlsAlpha * scrimAlpha

        // 7. Stage 2 Compact Header
        val compactAlpha = ((q - 1.58f) / 0.42f).coerceIn(0f, 1f)
        npQueueCompactHeader.alpha = compactAlpha
        npQueueCompactHeader.visibility = if (compactAlpha > 0.01f) View.VISIBLE else View.GONE

        // 8. Queue Sheet Position
        if (q <= 0.001f && !isDraggingQueue && !animatingQueue) {
            npQueueSlider.visibility = View.GONE
        } else {
            if (npQueueSlider.visibility != View.VISIBLE) {
                npQueueSlider.visibility = View.VISIBLE
            }
            val queueY = queueProgressToSheetTop(q)
            npQueueSlider.translationY = queueY
            val padBottom = queueY.toInt().coerceAtLeast(0) + 24.dpToPx()
            if (abs(padBottom - lastRecyclerPadBottom) > 8) {
                lastRecyclerPadBottom = padBottom
                npQueueRecycler.setPadding(0, 0, 0, padBottom)
            }
        }
    }

    // -------------------------------------------------------------
    // Track Metadata & Dynamic Album Color Palette Sync
    // -------------------------------------------------------------

    fun updateTrackInfo(
        title: String,
        artist: String,
        bitmap: Bitmap?,
        precomputedPalette: Pair<Int, Int>? = null
    ) {
        npMiniFadeTitle.text = title
        npMiniFadeArtist.text = artist
        npCompactTitle.text = title
        npCompactArtist.text = artist

        if (bitmap != null) {
            npCover.setImageBitmap(bitmap)
            val (bg, surface) = precomputedPalette ?: extractDarkPalette(bitmap)
            animateColorsTo(bg, surface)
        } else {
            npCover.setImageResource(R.drawable.ic_music_note)
            animateColorsTo(defaultBgColor, defaultSurfaceColor)
        }
    }

    fun updatePlayState(isPlaying: Boolean) {
        val icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        npMiniFadePlayPause.setImageResource(icon)
        npCompactPlayPause.setImageResource(icon)
    }

    private fun extractDarkPalette(bmp: Bitmap): Pair<Int, Int> {
        return computeDarkPalette(bmp, defaultBgColor, defaultSurfaceColor)
    }

    companion object {
        fun computeDarkPalette(bmp: Bitmap, defaultBg: Int, defaultSurface: Int): Pair<Int, Int> {
            val small = Bitmap.createScaledBitmap(bmp, 16, 16, true)
            var rSum = 0f
            var gSum = 0f
            var bSum = 0f
            var weightSum = 0f

            for (y in 0 until small.height) {
                for (x in 0 until small.width) {
                    val pixel = small.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val spread = max(r, max(g, b)) - min(r, min(g, b))
                    val weight = 1f + (spread / 255f) * 2.5f
                    rSum += r * weight
                    gSum += g * weight
                    bSum += b * weight
                    weightSum += weight
                }
            }
            if (small !== bmp) small.recycle()

            if (weightSum <= 0f) return defaultBg to defaultSurface

            val avgColor = Color.rgb(
                (rSum / weightSum).toInt().coerceIn(0, 255),
                (gSum / weightSum).toInt().coerceIn(0, 255),
                (bSum / weightSum).toInt().coerceIn(0, 255)
            )
            val hsv = FloatArray(3)
            Color.colorToHSV(avgColor, hsv)

            val sat = if (hsv[1] < 0.08f) 0f else (hsv[1] * 0.80f).coerceIn(0.14f, 0.52f)
            val bgColor = Color.HSVToColor(floatArrayOf(hsv[0], sat, 0.14f))
            val surfaceColor = Color.HSVToColor(floatArrayOf(hsv[0], (sat * 0.90f).coerceAtMost(0.45f), 0.21f))
            return bgColor to surfaceColor
        }
    }

    private fun animateColorsTo(targetBg: Int, targetSurface: Int) {
        colorAnimator?.cancel()
        val startBg = currentBgColor
        val startSurface = currentSurfaceColor
        val evaluator = ArgbEvaluator()
        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320L
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                val bg = evaluator.evaluate(f, startBg, targetBg) as Int
                val surface = evaluator.evaluate(f, startSurface, targetSurface) as Int
                applyColors(bg, surface)
            }
            start()
        }
    }

    private fun applyColors(bgColor: Int, surfaceColor: Int) {
        currentBgColor = bgColor
        currentSurfaceColor = surfaceColor

        val darkerBottom = ColorUtils.blendARGB(bgColor, Color.BLACK, 0.22f)
        npBgView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(bgColor, darkerBottom)
        )

        miniPlayerRoot.setBackgroundColor(surfaceColor)

        val r = 20.dpToPx().toFloat()
        npQueueSlider.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(surfaceColor)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }

        val midScrim = ColorUtils.setAlphaComponent(bgColor, 0x99)
        npStage1ArtScrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                0x55000000,
                0x00000000,
                0x00000000,
                midScrim,
                bgColor,
                bgColor
            )
        )
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun Int.dpToPx(): Int = (this * context.resources.displayMetrics.density).toInt()
}
