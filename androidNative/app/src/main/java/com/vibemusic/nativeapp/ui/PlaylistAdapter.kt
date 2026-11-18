package com.vibemusic.nativeapp.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vibemusic.nativeapp.R
import com.vibemusic.nativeapp.data.Playlist
import com.vibemusic.nativeapp.data.VibeStore

class PlaylistAdapter(
    private val store: VibeStore,
    private val isGrid: Boolean = false,
    private val onPlayClick: ((Playlist) -> Unit)? = null,
    private val onPlaylistClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    private val playlists = mutableListOf<Playlist>()
    private var activePlaylistId: Long = 0L
    private var activeCollectionKey: String = ""
    private var isPlaying: Boolean = false

    fun submitList(newList: List<Playlist>) {
        playlists.clear()
        playlists.addAll(newList)
        notifyDataSetChanged()
    }

    fun updatePlaybackState(playlistId: Long, collectionKey: String, playing: Boolean) {
        if (this.activePlaylistId == playlistId &&
            this.activeCollectionKey == collectionKey &&
            this.isPlaying == playing
        ) {
            return
        }
        this.activePlaylistId = playlistId
        this.activeCollectionKey = collectionKey
        this.isPlaying = playing
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = if (isGrid) R.layout.item_library_grid_card else R.layout.item_playlist_card
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pl = playlists[position]
        holder.name.text = pl.name
        holder.count.text = "${pl.trackCount} songs"

        // 2x2 cover mosaic vs single cover
        val covers = pl.coverTracks
        if (covers.size >= 4) {
            holder.singleCover.visibility = View.GONE
            holder.mosaicGrid.visibility = View.VISIBLE
            val views = listOf(holder.mosaic1, holder.mosaic2, holder.mosaic3, holder.mosaic4)
            for (i in 0 until 4) {
                val f = store.getCoverFile(covers[i])
                if (f.exists() && f.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    views[i].setImageBitmap(bmp)
                } else {
                    views[i].setImageResource(R.drawable.ic_music_note)
                }
            }
        } else {
            holder.mosaicGrid.visibility = View.GONE
            holder.singleCover.visibility = View.VISIBLE
            if (covers.isNotEmpty()) {
                val f = store.getCoverFile(covers[0])
                if (f.exists() && f.length() > 0) {
                    val bmp = BitmapFactory.decodeFile(f.absolutePath)
                    holder.singleCover.setImageBitmap(bmp)
                } else {
                    holder.singleCover.setImageResource(R.drawable.ic_library)
                }
            } else {
                holder.singleCover.setImageResource(R.drawable.ic_library)
            }
        }

        val isActive = if (pl.id != 0L) {
            activePlaylistId == pl.id || activeCollectionKey == "playlist:${pl.id}"
        } else {
            activeCollectionKey.isNotEmpty() && activeCollectionKey == "collection:${pl.name}"
        }
        val isActivelyPlaying = isActive && isPlaying

        holder.name.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (isActive) R.color.accent_green else R.color.text_primary
            )
        )

        if (onPlayClick != null) {
            holder.playBtn?.visibility = View.VISIBLE
            holder.playIcon?.setImageResource(if (isActivelyPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            holder.playBtn?.setOnClickListener { onPlayClick.invoke(pl) }
        } else {
            holder.playBtn?.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onPlaylistClick(pl) }
    }

    override fun getItemCount(): Int = playlists.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val singleCover: ImageView = view.findViewById(R.id.pl_cover_single)
        val mosaicGrid: GridLayout = view.findViewById(R.id.pl_mosaic)
        val mosaic1: ImageView = view.findViewById(R.id.pl_mosaic_1)
        val mosaic2: ImageView = view.findViewById(R.id.pl_mosaic_2)
        val mosaic3: ImageView = view.findViewById(R.id.pl_mosaic_3)
        val mosaic4: ImageView = view.findViewById(R.id.pl_mosaic_4)
        val playBtn: View? = view.findViewById(R.id.pl_btn_play)
        val playIcon: ImageView? = view.findViewById(R.id.pl_btn_play_icon)
        val name: TextView = view.findViewById(R.id.pl_name)
        val count: TextView = view.findViewById(R.id.pl_count)
    }
}
