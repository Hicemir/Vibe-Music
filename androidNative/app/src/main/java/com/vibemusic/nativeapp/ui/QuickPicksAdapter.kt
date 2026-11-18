package com.vibemusic.nativeapp.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vibemusic.nativeapp.R
import com.vibemusic.nativeapp.data.Track
import com.vibemusic.nativeapp.data.VibeStore

/**
 * YouTube Music Style Horizontal Carousel Adapter for Quick Picks:
 * Each item in the horizontal carousel is a Column containing up to 4 song rows.
 * By default, 3 columns are shown, and as the user scrolls, more columns spawn seamlessly.
 */
class QuickPicksAdapter(
    private val store: VibeStore,
    private val onTrackClick: (Track) -> Unit,
    private val onMoreClick: (Track, View) -> Unit
) : RecyclerView.Adapter<QuickPicksAdapter.ViewHolder>() {

    private val allColumns = mutableListOf<List<Track>>()
    private var displayedColumns = 3 // 3 columns by default

    fun submitTracks(tracks: List<Track>) {
        allColumns.clear()
        // Chunk into 4-song columns
        val chunked = tracks.chunked(4)
        allColumns.addAll(chunked)
        displayedColumns = 3.coerceAtMost(allColumns.size)
        notifyDataSetChanged()
    }

    fun hasMoreColumns(): Boolean = displayedColumns < allColumns.size

    fun appendColumn() {
        if (displayedColumns < allColumns.size) {
            val oldPos = displayedColumns
            displayedColumns++
            notifyItemInserted(oldPos)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_carousel_column, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tracks = allColumns[position]
        val rowViews = listOf(holder.row0, holder.row1, holder.row2, holder.row3)

        for (i in 0 until 4) {
            val rowView = rowViews[i]
            if (i < tracks.size) {
                rowView.visibility = View.VISIBLE
                val track = tracks[i]
                val coverImg = rowView.findViewById<ImageView>(R.id.row_cover)
                val titleTxt = rowView.findViewById<TextView>(R.id.row_title)
                val artistTxt = rowView.findViewById<TextView>(R.id.row_artist)
                val moreBtn = rowView.findViewById<ImageView>(R.id.row_more)

                titleTxt.text = track.displayTitle
                val playsStr = if (track.playCount > 0) " • ${track.playCount} plays" else ""
                artistTxt.text = "${track.displayArtist}$playsStr"

                val coverFile = store.getCoverFile(track.id)
                if (coverFile.exists() && coverFile.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(coverFile.absolutePath)
                    coverImg.setImageBitmap(bmp)
                } else {
                    coverImg.setImageResource(R.drawable.ic_music_note)
                }

                rowView.setOnClickListener {
                    onTrackClick(track)
                }

                rowView.setOnLongClickListener {
                    onMoreClick(track, moreBtn)
                    true
                }

                moreBtn.setOnClickListener { anchor ->
                    onMoreClick(track, anchor)
                }
            } else {
                rowView.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = displayedColumns.coerceAtMost(allColumns.size)

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val row0: View = view.findViewById(R.id.col_row_0)
        val row1: View = view.findViewById(R.id.col_row_1)
        val row2: View = view.findViewById(R.id.col_row_2)
        val row3: View = view.findViewById(R.id.col_row_3)
    }
}
