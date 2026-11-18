package com.vibemusic.nativeapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.vibemusic.nativeapp.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

class CircularDownloadDrawable(private val context: Context) : Drawable(), Animatable {

    enum class State {
        IDLE,
        PENDING,
        DOWNLOADING,
        DONE
    }

    private val density = context.resources.displayMetrics.density
    private val accentGreen = ContextCompat.getColor(context, R.color.accent_green)
    private val trackColor = ContextCompat.getColor(context, R.color.surface_border)
    private val idleTint = ContextCompat.getColor(context, R.color.text_secondary)
    private val textPrimary = ContextCompat.getColor(context, R.color.text_primary)

    private val idleIcon: Drawable? =
        ContextCompat.getDrawable(context, R.drawable.ic_download)?.mutate()?.apply {
            setTint(idleTint)
        }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = trackColor
        strokeWidth = 2.5f * density
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentGreen
        strokeWidth = 2.6f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val innerGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentGreen
        strokeWidth = 2.0f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textPrimary
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val arcRect = RectF()
    private val glyphPath = Path()

    var currentState: State = State.IDLE
        private set
    private var targetProgress: Float = 0f
    private var displayedProgress: Float = 0f
    private var spinAngle: Float = -90f
    private var lastFrameMs: Long = 0L

    private var animator: ValueAnimator? = null

    fun update(state: State, progress: Float = 0f) {
        val clamped = progress.coerceIn(0f, 100f)
        val stateChanged = currentState != state
        currentState = state

        when (state) {
            State.IDLE -> {
                targetProgress = 0f
                displayedProgress = 0f
                stop()
                invalidateSelf()
            }
            State.PENDING -> {
                if (stateChanged) {
                    targetProgress = 0f
                    displayedProgress = 0f
                }
                start()
                invalidateSelf()
            }
            State.DOWNLOADING -> {
                if (stateChanged && displayedProgress > clamped + 15f) {
                    displayedProgress = clamped
                }
                targetProgress = clamped
                start()
                invalidateSelf()
            }
            State.DONE -> {
                targetProgress = 100f
                displayedProgress = 100f
                stop()
                invalidateSelf()
            }
        }
    }

    override fun start() {
        if (animator?.isRunning == true) return
        lastFrameMs = SystemClock.uptimeMillis()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val now = SystemClock.uptimeMillis()
                val dt = (now - lastFrameMs).coerceIn(1L, 64L)
                lastFrameMs = now

                spinAngle = (spinAngle + dt * 0.28f) % 360f
                val diff = targetProgress - displayedProgress
                if (abs(diff) < 0.25f) {
                    displayedProgress = targetProgress
                } else {
                    displayedProgress += diff * 0.25f
                }
                invalidateSelf()
            }
            start()
        }
    }

    override fun stop() {
        animator?.cancel()
        animator = null
    }

    override fun isRunning(): Boolean = animator?.isRunning == true

    override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
        val changed = super.setVisible(visible, restart)
        if (!visible) {
            stop()
        } else if (currentState == State.PENDING || currentState == State.DOWNLOADING) {
            start()
        }
        return changed
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        val cx = b.exactCenterX()
        val cy = b.exactCenterY()
        val minDim = min(b.width(), b.height()).toFloat()

        if (currentState == State.IDLE) {
            val icon = idleIcon ?: return
            val inset = (minDim * 0.10f).toInt()
            icon.setBounds(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
            icon.draw(canvas)
            return
        }

        val strokePx = (2.4f * density).coerceAtMost(minDim * 0.14f)
        trackPaint.strokeWidth = strokePx
        progressPaint.strokeWidth = strokePx

        val radius = (minDim / 2f) - strokePx * 0.65f
        if (radius <= 0f) return

        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius)

        when (currentState) {
            State.PENDING -> {
                fillPaint.color = 0x181ED760
                canvas.drawCircle(cx, cy, radius, fillPaint)
                canvas.drawCircle(cx, cy, radius, trackPaint)
                canvas.drawArc(arcRect, spinAngle, 95f, false, progressPaint)
                drawDownloadArrow(canvas, cx, cy, radius)
            }
            State.DOWNLOADING -> {
                fillPaint.color = 0x181ED760
                canvas.drawCircle(cx, cy, radius, fillPaint)
                canvas.drawCircle(cx, cy, radius, trackPaint)

                if (displayedProgress < 2f) {
                    canvas.drawArc(arcRect, spinAngle, 95f, false, progressPaint)
                    drawDownloadArrow(canvas, cx, cy, radius)
                } else {
                    val sweep = (displayedProgress.coerceIn(2f, 100f) / 100f) * 360f
                    canvas.drawArc(arcRect, -90f, sweep, false, progressPaint)

                    val pctInt = displayedProgress.roundToInt().coerceIn(0, 100)
                    if (minDim >= 27f * density) {
                        val label = "$pctInt%"
                        textPaint.color = textPrimary
                        textPaint.textSize = when {
                            pctInt >= 100 -> radius * 0.56f
                            else -> radius * 0.72f
                        }
                        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                        canvas.drawText(label, cx, textY, textPaint)
                    } else if (minDim >= 22f * density) {
                        val label = "$pctInt"
                        textPaint.color = textPrimary
                        textPaint.textSize = if (pctInt >= 100) radius * 0.60f else radius * 0.78f
                        val textY = cy - (textPaint.descent() + textPaint.ascent()) / 2f
                        canvas.drawText(label, cx, textY, textPaint)
                    } else {
                        drawDownloadArrow(canvas, cx, cy, radius)
                    }
                }
            }
            State.DONE -> {
                fillPaint.color = 0x261ED760
                canvas.drawCircle(cx, cy, radius, fillPaint)
                progressPaint.strokeWidth = (2.1f * density).coerceAtMost(minDim * 0.13f)
                canvas.drawCircle(cx, cy, radius, progressPaint)

                innerGlyphPaint.strokeWidth = (2.1f * density).coerceAtMost(minDim * 0.14f)
                glyphPath.reset()
                glyphPath.moveTo(cx - radius * 0.38f, cy + radius * 0.04f)
                glyphPath.lineTo(cx - radius * 0.08f, cy + radius * 0.34f)
                glyphPath.lineTo(cx + radius * 0.40f, cy - radius * 0.26f)
                canvas.drawPath(glyphPath, innerGlyphPaint)
            }
            State.IDLE -> Unit
        }
    }

    private fun drawDownloadArrow(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        innerGlyphPaint.strokeWidth = (1.8f * density).coerceAtMost(radius * 0.22f)
        val r = radius * 0.45f
        glyphPath.reset()
        // Vertical stem
        glyphPath.moveTo(cx, cy - r * 0.85f)
        glyphPath.lineTo(cx, cy + r * 0.55f)
        // Arrow head
        glyphPath.moveTo(cx - r * 0.55f, cy + r * 0.05f)
        glyphPath.lineTo(cx, cy + r * 0.65f)
        glyphPath.lineTo(cx + r * 0.55f, cy + r * 0.05f)
        // Base line
        glyphPath.moveTo(cx - r * 0.6f, cy + r * 0.95f)
        glyphPath.lineTo(cx + r * 0.6f, cy + r * 0.95f)
        canvas.drawPath(glyphPath, innerGlyphPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        trackPaint.alpha = alpha
        progressPaint.alpha = alpha
        innerGlyphPaint.alpha = alpha
        textPaint.alpha = alpha
        idleIcon?.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        progressPaint.colorFilter = colorFilter
        innerGlyphPaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
        idleIcon?.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = -1

    override fun getIntrinsicHeight(): Int = -1
}
