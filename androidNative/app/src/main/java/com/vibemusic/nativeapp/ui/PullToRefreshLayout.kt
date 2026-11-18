package com.vibemusic.nativeapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.min

class PullToRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onRefreshListener: (() -> Unit)? = null

    var isRefreshing: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                animateIndicatorToRefreshing()
            } else {
                animateIndicatorToReset()
            }
        }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density
    private val indicatorSizePx = (40 * density).toInt()
    private val triggerDistancePx = 72f * density
    private val maxDragDistancePx = 130f * density
    private val restingOffsetPx = 16f * density
    private val dragRate = 0.5f

    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var initialDownX = 0f
    private var initialDownY = 0f
    private var initialMotionY = 0f
    private var isBeingDragged = false
    private var isHorizontalDrag = false
    private var currentDragOffset = 0f

    private val indicatorView = RefreshIndicatorView(context)
    private var positionAnimator: ValueAnimator? = null

    init {
        clipChildren = false
        clipToPadding = false

        val lp = LayoutParams(indicatorSizePx, indicatorSizePx).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        indicatorView.translationY = -indicatorSizePx.toFloat()
        indicatorView.visibility = View.GONE
        indicatorView.elevation = 8f * density
        addView(indicatorView, lp)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        indicatorView.bringToFront()
    }

    override fun onViewAdded(child: View?) {
        super.onViewAdded(child)
        if (child !== indicatorView) {
            indicatorView.bringToFront()
        }
    }

    fun canChildScrollUp(): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === indicatorView || child.visibility != View.VISIBLE) continue
            val scrollable = findVerticalScrollableChild(child) ?: child
            if (scrollable.canScrollVertically(-1)) {
                return true
            }
        }
        return false
    }

    private fun findVerticalScrollableChild(view: View): View? {
        if (view is NestedScrollView || view is ScrollView) {
            return view
        }
        if (view is RecyclerView) {
            val lm = view.layoutManager
            if (lm == null || lm.canScrollVertically()) {
                return view
            }
            return null
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val found = findVerticalScrollableChild(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun isTouchInsideSeekBar(view: View, rawX: Float, rawY: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        if (view is SeekBar) {
            val loc = IntArray(2)
            view.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX <= loc[0] + view.width &&
                rawY >= loc[1] && rawY <= loc[1] + view.height
            ) {
                return true
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                if (isTouchInsideSeekBar(view.getChildAt(i), rawX, rawY)) return true
            }
        }
        return false
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (!canChildScrollUp() && !isHorizontalDrag) {
            parent?.requestDisallowInterceptTouchEvent(disallowIntercept)
            return
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isEnabled || isRefreshing || canChildScrollUp()) {
            return false
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (isTouchInsideSeekBar(this, ev.rawX, ev.rawY)) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    return false
                }
                activePointerId = ev.getPointerId(0)
                isBeingDragged = false
                isHorizontalDrag = false
                val pointerIndex = ev.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false
                initialDownX = ev.getX(pointerIndex)
                initialDownY = ev.getY(pointerIndex)
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
                val pointerIndex = ev.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false

                val x = ev.getX(pointerIndex)
                val y = ev.getY(pointerIndex)
                val dx = x - initialDownX
                val dy = y - initialDownY

                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    isHorizontalDrag = true
                    return false
                }

                if (!isHorizontalDrag && dy > touchSlop && dy > abs(dx) * 1.2f) {
                    if (canChildScrollUp()) return false
                    initialMotionY = initialDownY + touchSlop
                    isBeingDragged = true
                    positionAnimator?.cancel()
                    indicatorView.visibility = View.VISIBLE
                    indicatorView.scaleX = 1f
                    indicatorView.scaleY = 1f
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                onSecondaryPointerUp(ev)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isBeingDragged = false
                isHorizontalDrag = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }

        return isBeingDragged
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!isEnabled || isRefreshing || canChildScrollUp()) {
            return false
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointerId = ev.getPointerId(0)
                isBeingDragged = false
            }

            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = ev.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return false

                val y = ev.getY(pointerIndex)
                if (!isBeingDragged) {
                    val dy = y - initialDownY
                    if (dy > touchSlop) {
                        initialMotionY = initialDownY + touchSlop
                        isBeingDragged = true
                        positionAnimator?.cancel()
                        indicatorView.visibility = View.VISIBLE
                        indicatorView.scaleX = 1f
                        indicatorView.scaleY = 1f
                    }
                }

                if (isBeingDragged) {
                    val overscrollTop = ((y - initialMotionY) * dragRate).coerceIn(0f, maxDragDistancePx)
                    currentDragOffset = overscrollTop
                    updateIndicatorDrag(overscrollTop)
                }
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                val index = ev.actionIndex
                if (index < 0) return false
                activePointerId = ev.getPointerId(index)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                onSecondaryPointerUp(ev)
            }

            MotionEvent.ACTION_UP -> {
                val pointerIndex = ev.findPointerIndex(activePointerId)
                if (pointerIndex >= 0 && isBeingDragged) {
                    val y = ev.getY(pointerIndex)
                    val overscrollTop = ((y - initialMotionY) * dragRate).coerceAtLeast(0f)
                    isBeingDragged = false
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                    if (overscrollTop >= triggerDistancePx) {
                        isRefreshing = true
                        onRefreshListener?.invoke()
                    } else {
                        animateIndicatorToReset()
                    }
                    return true
                }
                isBeingDragged = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isBeingDragged) {
                    isBeingDragged = false
                    animateIndicatorToReset()
                }
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return false
            }
        }

        return true
    }

    private fun onSecondaryPointerUp(ev: MotionEvent) {
        val pointerIndex = ev.actionIndex
        val pointerId = ev.getPointerId(pointerIndex)
        if (pointerId == activePointerId) {
            val newPointerIndex = if (pointerIndex == 0) 1 else 0
            activePointerId = ev.getPointerId(newPointerIndex)
        }
    }

    private fun updateIndicatorDrag(overscrollTop: Float) {
        val progress = (overscrollTop / triggerDistancePx).coerceIn(0f, 1f)
        val targetY = -indicatorSizePx + overscrollTop
        indicatorView.translationY = targetY
        indicatorView.setPullProgress(progress)
    }

    private fun animateIndicatorToRefreshing() {
        positionAnimator?.cancel()
        indicatorView.visibility = View.VISIBLE
        indicatorView.scaleX = 1f
        indicatorView.scaleY = 1f
        indicatorView.startSpinning()

        val startY = indicatorView.translationY
        val endY = restingOffsetPx
        positionAnimator = ValueAnimator.ofFloat(startY, endY).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                indicatorView.translationY = anim.animatedValue as Float
            }
            start()
        }
    }

    private fun animateIndicatorToReset() {
        positionAnimator?.cancel()
        currentDragOffset = 0f
        val startY = indicatorView.translationY
        val endY = -indicatorSizePx.toFloat()
        positionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val frac = anim.animatedValue as Float
                indicatorView.translationY = startY + (endY - startY) * frac
                indicatorView.scaleX = 1f - frac * 0.4f
                indicatorView.scaleY = 1f - frac * 0.4f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!isRefreshing) {
                        indicatorView.stopSpinning()
                        indicatorView.visibility = View.GONE
                        indicatorView.translationY = endY
                        indicatorView.scaleX = 1f
                        indicatorView.scaleY = 1f
                    }
                }
            })
            start()
        }
    }

    private class RefreshIndicatorView(context: Context) : View(context) {
        private val density = context.resources.displayMetrics.density

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#242428")
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        }

        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1DB954")
            style = Paint.Style.STROKE
            strokeWidth = 2.6f * density
            strokeCap = Paint.Cap.ROUND
        }

        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1DB954")
            style = Paint.Style.FILL
        }

        private val arcBounds = RectF()
        private val arrowPath = Path()

        private var pullProgress = 0f
        private var spinAngle = 0f
        private var isSpinning = false
        private var spinAnimator: ValueAnimator? = null

        fun setPullProgress(progress: Float) {
            isSpinning = false
            spinAnimator?.cancel()
            pullProgress = progress.coerceIn(0f, 1f)
            invalidate()
        }

        fun startSpinning() {
            if (isSpinning) return
            isSpinning = true
            spinAnimator?.cancel()
            spinAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 850
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    spinAngle = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopSpinning() {
            isSpinning = false
            spinAnimator?.cancel()
            spinAnimator = null
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            stopSpinning()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(cx, cy) - 1f * density

            // Draw elevated dark disc
            canvas.drawCircle(cx, cy, radius, bgPaint)
            canvas.drawCircle(cx, cy, radius, borderPaint)

            val arcRadius = 9.5f * density
            arcBounds.set(cx - arcRadius, cy - arcRadius, cx + arcRadius, cy + arcRadius)

            if (isSpinning) {
                arcPaint.alpha = 255
                canvas.drawArc(arcBounds, spinAngle - 90f, 270f, false, arcPaint)
            } else {
                val alpha = (110 + (145 * pullProgress)).toInt().coerceIn(0, 255)
                arcPaint.alpha = alpha
                arrowPaint.alpha = alpha

                val startAngle = -90f + (pullProgress * 140f)
                val sweepAngle = 30f + (pullProgress * 245f)
                canvas.drawArc(arcBounds, startAngle, sweepAngle, false, arcPaint)

                // Draw small arrow head at the end of the arc
                val endAngleRad = Math.toRadians((startAngle + sweepAngle).toDouble())
                val tipX = cx + (arcRadius * Math.cos(endAngleRad)).toFloat()
                val tipY = cy + (arcRadius * Math.sin(endAngleRad)).toFloat()

                val arrowSize = 3.5f * density * (0.5f + 0.5f * pullProgress)
                arrowPath.reset()
                arrowPath.moveTo(tipX, tipY - arrowSize)
                arrowPath.lineTo(tipX + arrowSize * 1.2f, tipY)
                arrowPath.lineTo(tipX, tipY + arrowSize)
                arrowPath.close()

                canvas.save()
                canvas.rotate(startAngle + sweepAngle + 90f, tipX, tipY)
                canvas.drawPath(arrowPath, arrowPaint)
                canvas.restore()
            }
        }
    }
}
