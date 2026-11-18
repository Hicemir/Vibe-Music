package com.vibemusic.nativeapp.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vibemusic.nativeapp.R
import com.vibemusic.nativeapp.data.Track
import com.vibemusic.nativeapp.data.VibeStore

class TrackAdapter(
    private val store: VibeStore,
    private val onTrackClick: (Track) -> Unit,
    private val onMenuClick: ((Track, View) -> Unit)? = null,
    private val onAddClick: ((Track, TextView) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val tracks = mutableListOf<Track>()
    private var footerText: String? = null
    private var onFooterClick: (() -> Unit)? = null

    fun submitList(newList: List<Track>) {
        tracks.clear()
        tracks.addAll(newList)
        notifyDataSetChanged()
    }

    fun setFooterButton(text: String?, onClick: (() -> Unit)? = null) {
        footerText = text
        onFooterClick = onClick
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (position < tracks.size) TYPE_TRACK else TYPE_FOOTER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == TYPE_FOOTER) {
            val ctx = parent.context
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            val container = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            val btn = TextView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    dp(44)
                ).apply {
                    setMargins(dp(16), dp(10), dp(16), dp(16))
                }
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_thin_button)
                setTextColor(ContextCompat.getColor(ctx, R.color.accent_green))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                isClickable = true
                isFocusable = true
            }
            container.addView(btn)
            return FooterViewHolder(container, btn)
        }
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is FooterViewHolder) {
            holder.button.text = footerText ?: ""
            holder.button.setOnClickListener { onFooterClick?.invoke() }
            return
        }
        val trackHolder = holder as ViewHolder
        val track = tracks[position]
        trackHolder.title.text = track.displayTitle
        trackHolder.artist.text = track.displayArtist
        trackHolder.duration.text = track.formattedDuration()

        // Load cover thumbnail
        val coverFile = store.getCoverFile(track.id)
        if (coverFile.exists() && coverFile.length() > 0) {
            val bmp = BitmapFactory.decodeFile(coverFile.absolutePath)
            if (bmp != null) {
                trackHolder.cover.setImageBitmap(bmp)
            } else {
                trackHolder.cover.setImageResource(R.drawable.ic_music_note)
            }
        } else {
            trackHolder.cover.setImageResource(R.drawable.ic_music_note)
        }

        if (onAddClick != null) {
            trackHolder.btnAdd.visibility = View.VISIBLE
            trackHolder.btnAdd.text = "+ Add"
            trackHolder.btnAdd.setTextColor(ContextCompat.getColor(trackHolder.itemView.context, R.color.accent_green))
            trackHolder.btnAdd.setOnClickListener {
                onAddClick.invoke(track, trackHolder.btnAdd)
            }
        } else {
            trackHolder.btnAdd.visibility = View.GONE
        }

        trackHolder.itemView.setOnClickListener { onTrackClick(track) }
        trackHolder.itemView.setOnLongClickListener {
            if (onMenuClick != null) {
                onMenuClick.invoke(track, trackHolder.menu)
                true
            } else {
                false
            }
        }
        trackHolder.menu.setOnClickListener { onMenuClick?.invoke(track, it) }
    }

    override fun getItemCount(): Int = tracks.size + if (footerText != null) 1 else 0

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.track_cover)
        val title: TextView = view.findViewById(R.id.track_title)
        val artist: TextView = view.findViewById(R.id.track_artist)
        val duration: TextView = view.findViewById(R.id.track_duration)
        val btnAdd: TextView = view.findViewById(R.id.btn_track_add)
        val menu: ImageView = view.findViewById(R.id.track_menu)
    }

    class FooterViewHolder(view: View, val button: TextView) : RecyclerView.ViewHolder(view)

    companion object {
        private const val TYPE_TRACK = 0
        private const val TYPE_FOOTER = 1
    }
}
