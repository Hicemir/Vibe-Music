package com.vibemusic.nativeapp.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.vibemusic.nativeapp.R
import kotlin.math.abs

/**
 * Clean, modern YouTube Music / Spotify-style rounded bottom sheet menu
 * replacing plain system PopupMenu dialogs on mobile, with drag-down to dismiss.
 */
object BottomActionSheet {

    data class ActionItem(
        val label: String,
        val iconRes: Int? = null,
        val isDestructive: Boolean = false,
        val isSelected: Boolean = false,
        val onClick: () -> Unit
    )

    @SuppressLint("ClickableViewAccessibility")
    fun show(
        context: Context,
        title: String,
        subtitle: String? = null,
        coverBitmap: Bitmap? = null,
        fallbackIconRes: Int? = null,
        items: List<ActionItem>
    ) {
        val density = context.resources.displayMetrics.density
        fun Int.dp(): Int = (this * density).toInt()
        val baseDim = 0.62f

        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)

        val sheetRadius = 22.dp().toFloat()
        val sheetBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor("#181822"))
            cornerRadii = floatArrayOf(sheetRadius, sheetRadius, sheetRadius, sheetRadius, 0f, 0f, 0f, 0f)
        }

        lateinit var scroll: ScrollView

        val root = object : LinearLayout(context) {
            private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
            private var downRawX = 0f
            private var downRawY = 0f
            private var dragStartRawY = 0f
            private var isDragging = false
            private var startedInScroll = false
            private var isDismissing = false
            private var velocityTracker: VelocityTracker? = null

            private fun obtainRawEvent(ev: MotionEvent): MotionEvent {
                val copy = MotionEvent.obtain(ev)
                copy.setLocation(ev.rawX, ev.rawY)
                return copy
            }

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (isDismissing) return true
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        animate().cancel()
                        downRawX = ev.rawX
                        downRawY = ev.rawY
                        dragStartRawY = ev.rawY
                        isDragging = false
                        startedInScroll = ev.y >= scroll.top && ev.y <= scroll.bottom
                        velocityTracker?.recycle()
                        velocityTracker = VelocityTracker.obtain().apply {
                            val rawEv = obtainRawEvent(ev)
                            addMovement(rawEv)
                            rawEv.recycle()
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val rawEv = obtainRawEvent(ev)
                        velocityTracker?.addMovement(rawEv)
                        rawEv.recycle()

                        val dy = ev.rawY - downRawY
                        val dx = abs(ev.rawX - downRawX)
                        val canPullDown = !startedInScroll || scroll.scrollY <= 0
                        if (canPullDown && dy > touchSlop && dy > dx) {
                            isDragging = true
                            dragStartRawY = ev.rawY
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (!isDragging) {
                            velocityTracker?.recycle()
                            velocityTracker = null
                        }
                    }
                }
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (isDismissing) return true
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> return true
                    MotionEvent.ACTION_MOVE -> {
                        val rawEv = obtainRawEvent(ev)
                        velocityTracker?.addMovement(rawEv)
                        rawEv.recycle()

                        if (!isDragging) {
                            val dy = ev.rawY - downRawY
                            val dx = abs(ev.rawX - downRawX)
                            if (dy > touchSlop && dy > dx) {
                                isDragging = true
                                dragStartRawY = ev.rawY
                            }
                        }
                        if (isDragging) {
                            val shift = (ev.rawY - dragStartRawY).coerceAtLeast(0f)
                            translationY = shift
                            val h = height.coerceAtLeast(1).toFloat()
                            val frac = (1f - (shift / h)).coerceIn(0f, 1f)
                            dialog.window?.setDimAmount(baseDim * frac)
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val vt = velocityTracker
                        var vy = 0f
                        if (vt != null) {
                            val rawEv = obtainRawEvent(ev)
                            vt.addMovement(rawEv)
                            rawEv.recycle()
                            vt.computeCurrentVelocity(1000)
                            vy = vt.yVelocity
                            vt.recycle()
                            velocityTracker = null
                        }
                        if (isDragging) {
                            isDragging = false
                            val shift = translationY
                            val h = height.coerceAtLeast(1).toFloat()
                            val threshold = (h * 0.28f).coerceAtLeast(88.dp().toFloat())
                            val shouldDismiss = ev.actionMasked == MotionEvent.ACTION_UP &&
                                (shift > threshold || (vy > 900f && shift > 16.dp()))
                            if (shouldDismiss) {
                                isDismissing = true
                                val remaining = (h - shift).coerceAtLeast(0f)
                                val dur = ((remaining / h) * 200f).toLong().coerceIn(90L, 200L)
                                animate()
                                    .translationY(h)
                                    .setDuration(dur)
                                    .setInterpolator(AccelerateInterpolator())
                                    .withEndAction {
                                        if (dialog.isShowing) {
                                            dialog.window?.setWindowAnimations(0)
                                            dialog.dismiss()
                                        }
                                    }
                                    .start()
                                dialog.window?.setDimAmount(0f)
                            } else {
                                animate()
                                    .translationY(0f)
                                    .setDuration(180L)
                                    .setInterpolator(DecelerateInterpolator())
                                    .start()
                                dialog.window?.setDimAmount(baseDim)
                            }
                            return true
                        }
                    }
                }
                return super.onTouchEvent(ev)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = sheetBg
            setPadding(0, 12.dp(), 0, 20.dp())
        }

        // Top drag pill
        val pill = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(40.dp(), 4.dp()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = 14.dp()
            }
            setBackgroundResource(R.drawable.bg_pill_handle)
        }
        root.addView(pill)

        // Header row
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp(), 2.dp(), 20.dp(), 14.dp())
        }

        if (coverBitmap != null || fallbackIconRes != null) {
            val thumb = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(44.dp(), 44.dp()).apply {
                    marginEnd = 12.dp()
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.bg_cover_rounded_6dp)
                clipToOutline = true
                if (coverBitmap != null) {
                    setImageBitmap(coverBitmap)
                } else if (fallbackIconRes != null) {
                    setImageResource(fallbackIconRes)
                    setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(10.dp(), 10.dp(), 10.dp(), 10.dp())
                }
            }
            header.addView(thumb)
        }

        val titleCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleCol.addView(titleTv)

        if (!subtitle.isNullOrBlank()) {
            val subTv = TextView(context).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, 2.dp(), 0, 0)
            }
            titleCol.addView(subTv)
        }
        header.addView(titleCol)
        root.addView(header)

        // Divider
        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(Color.parseColor("#18FFFFFF"))
        }
        root.addView(divider)

        // Scrollable action rows
        val itemsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 6.dp(), 0, 4.dp())
        }

        val typedValue = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, typedValue, true)

        for (item in items) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 52.dp())
                setPadding(20.dp(), 0, 20.dp(), 0)
                setBackgroundResource(typedValue.resourceId)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    dialog.dismiss()
                    item.onClick()
                }
            }

            val tintColor = when {
                item.isDestructive -> ContextCompat.getColor(context, R.color.accent_red)
                item.isSelected -> ContextCompat.getColor(context, R.color.accent_green)
                else -> ContextCompat.getColor(context, R.color.text_primary)
            }

            if (item.iconRes != null) {
                val icon = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(22.dp(), 22.dp()).apply {
                        marginEnd = 16.dp()
                    }
                    setImageResource(item.iconRes)
                    setColorFilter(tintColor)
                }
                row.addView(icon)
            }

            val labelTv = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = item.label
                textSize = 15f
                setTextColor(tintColor)
                if (item.isSelected || item.isDestructive) {
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                }
            }
            row.addView(labelTv)

            if (item.isSelected) {
                val check = ImageView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(20.dp(), 20.dp())
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(ContextCompat.getColor(context, R.color.accent_green))
                }
                row.addView(check)
            }

            itemsContainer.addView(row)
        }

        val maxListHeight = (context.resources.displayMetrics.heightPixels * 0.58f).toInt()
        scroll = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isVerticalScrollBarEnabled = false
            addView(itemsContainer)
        }
        itemsContainer.measure(
            View.MeasureSpec.makeMeasureSpec(context.resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        if (itemsContainer.measuredHeight > maxListHeight) {
            scroll.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxListHeight)
        }
        root.addView(scroll)

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(baseDim)
            attributes?.windowAnimations = R.style.BottomSheetDialogAnimation
        }
        dialog.show()
    }
}
