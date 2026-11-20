package com.vibemusic.nativeapp.ui

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

        for (i in 0 until 4) {
            val row = holder.rows[i]
            if (i < tracks.size) {
                row.root.visibility = View.VISIBLE
                val track = tracks[i]

                row.titleTxt.text = track.displayTitle
                val playsStr = if (track.playCount > 0) " • ${track.playCount} plays" else ""
                row.artistTxt.text = "${track.displayArtist}$playsStr"

                CoverLoader.loadInto(row.coverImg, store, track.id, 192, R.drawable.ic_music_note)

                row.root.setOnClickListener {
                    onTrackClick(track)
                }

                row.root.setOnLongClickListener {
                    onMoreClick(track, row.moreBtn)
                    true
                }

                row.moreBtn.setOnClickListener { anchor ->
                    onMoreClick(track, anchor)
                }
            } else {
                row.coverImg.tag = null
                row.root.visibility = View.GONE
            }
        }
    }

    override fun getItemCount(): Int = displayedColumns.coerceAtMost(allColumns.size)

    class RowViews(val root: View) {
        val coverImg: ImageView = root.findViewById(R.id.row_cover)
        val titleTxt: TextView = root.findViewById(R.id.row_title)
        val artistTxt: TextView = root.findViewById(R.id.row_artist)
        val moreBtn: ImageView = root.findViewById(R.id.row_more)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val rows: List<RowViews> = listOf(
            RowViews(view.findViewById(R.id.col_row_0)),
            RowViews(view.findViewById(R.id.col_row_1)),
            RowViews(view.findViewById(R.id.col_row_2)),
            RowViews(view.findViewById(R.id.col_row_3))
        )
    }
}
