package com.vibemusic.nativeapp.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.vibemusic.nativeapp.R

class EqualizerBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val bars = mutableListOf<View>()
    private val animators = mutableListOf<ValueAnimator>()
    private var isAnimating = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.BOTTOM

        val density = context.resources.displayMetrics.density
        val barWidth = (3.5f * density).toInt()
        val spacing = (2.5f * density).toInt()
        val greenColor = ContextCompat.getColor(context, R.color.accent_green)

        val heights = listOf(8, 16, 11)
        for (i in 0 until 3) {
            val bar = View(context).apply {
                val bg = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(greenColor)
                    cornerRadius = 2f * density
                }
                background = bg
                val params = LayoutParams(barWidth, (heights[i] * density).toInt()).apply {
                    if (i > 0) marginStart = spacing
                }
                layoutParams = params
            }
            bars.add(bar)
            addView(bar)
        }
    }

    fun setPlaying(playing: Boolean) {
        if (playing) {
            startBars()
        } else {
            stopBars()
        }
    }

    private fun startBars() {
        if (isAnimating) return
        isAnimating = true
        stopBarsInternal()

        val density = context.resources.displayMetrics.density
        val ranges = listOf(
            Pair(4f * density, 18f * density) to 380L,
            Pair(6f * density, 22f * density) to 270L,
            Pair(3f * density, 16f * density) to 430L
        )

        for (i in 0 until 3) {
            val (range, dur) = ranges[i]
            val bar = bars[i]
            val anim = ValueAnimator.ofFloat(range.first, range.second).apply {
                duration = dur
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { va ->
                    val h = (va.animatedValue as Float).toInt()
                    val p = bar.layoutParams
                    p.height = h
                    bar.layoutParams = p
                }
            }
            animators.add(anim)
            anim.start()
        }
    }

    private fun stopBarsInternal() {
        for (anim in animators) {
            anim.cancel()
        }
        animators.clear()
    }

    private fun stopBars() {
        isAnimating = false
        stopBarsInternal()
        val density = context.resources.displayMetrics.density
        val resting = listOf(6, 12, 8)
        for (i in 0 until 3) {
            val p = bars[i].layoutParams
            p.height = (resting[i] * density).toInt()
            bars[i].layoutParams = p
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopBars()
    }
}
