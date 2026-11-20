package com.vibemusic.nativeapp.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max

class WrapLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    var horizontalSpacingPx: Int = (8 * resources.displayMetrics.density).toInt()
    var verticalSpacingPx: Int = (8 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val maxContentWidth = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else widthSize - paddingLeft - paddingRight

        var curX = 0
        var curY = 0
        var lineHeight = 0
        var maxUsedWidth = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val cw = child.measuredWidth
            val ch = child.measuredHeight

            if (curX > 0 && curX + cw > maxContentWidth) {
                curY += lineHeight + verticalSpacingPx
                curX = 0
                lineHeight = 0
            }
            curX += cw + horizontalSpacingPx
            lineHeight = max(lineHeight, ch)
            maxUsedWidth = max(maxUsedWidth, curX - horizontalSpacingPx)
        }

        val totalHeight = paddingTop + paddingBottom + curY + lineHeight
        val measuredW = if (widthMode == MeasureSpec.EXACTLY) widthSize else paddingLeft + paddingRight + maxUsedWidth
        setMeasuredDimension(measuredW, resolveSize(totalHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxContentWidth = (r - l) - paddingLeft - paddingRight
        var curX = paddingLeft
        var curY = paddingTop
        var lineHeight = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val cw = child.measuredWidth
            val ch = child.measuredHeight

            if (curX > paddingLeft && (curX - paddingLeft) + cw > maxContentWidth) {
                curY += lineHeight + verticalSpacingPx
                curX = paddingLeft
                lineHeight = 0
            }
            child.layout(curX, curY, curX + cw, curY + ch)
            curX += cw + horizontalSpacingPx
            lineHeight = max(lineHeight, ch)
        }
    }
}
