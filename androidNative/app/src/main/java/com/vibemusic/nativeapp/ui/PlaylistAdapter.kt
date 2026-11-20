package com.vibemusic.nativeapp.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
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
        val oldList = playlists.toList()
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newList.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                val o = oldList[oldItemPosition]
                val n = newList[newItemPosition]
                return if (o.id != 0L || n.id != 0L) o.id == n.id else o.name == n.name
            }

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                return oldList[oldItemPosition] == newList[newItemPosition]
            }
        })
        playlists.clear()
        playlists.addAll(newList)
        diff.dispatchUpdatesTo(this)
    }

    private fun isPlaylistActive(pl: Playlist, playlistId: Long, collectionKey: String): Boolean {
        return if (pl.id != 0L) {
            playlistId == pl.id || collectionKey == "playlist:${pl.id}"
        } else {
            collectionKey.isNotEmpty() && collectionKey == "collection:${pl.name}"
        }
    }

    fun updatePlaybackState(playlistId: Long, collectionKey: String, playing: Boolean) {
        if (this.activePlaylistId == playlistId &&
            this.activeCollectionKey == collectionKey &&
            this.isPlaying == playing
        ) {
            return
        }
        val oldPlaylistId = this.activePlaylistId
        val oldCollectionKey = this.activeCollectionKey
        this.activePlaylistId = playlistId
        this.activeCollectionKey = collectionKey
        this.isPlaying = playing

        for (i in playlists.indices) {
            val pl = playlists[i]
            val wasActive = isPlaylistActive(pl, oldPlaylistId, oldCollectionKey)
            val nowActive = isPlaylistActive(pl, playlistId, collectionKey)
            if (wasActive || nowActive) {
                notifyItemChanged(i, PAYLOAD_PLAYBACK_STATE)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layoutId = if (isGrid) R.layout.item_library_grid_card else R.layout.item_playlist_card
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PLAYBACK_STATE)) {
            bindPlaybackState(holder, playlists[position])
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val pl = playlists[position]
        holder.name.text = pl.name
        holder.count.text = "${pl.trackCount} songs"

        // 2x2 cover mosaic vs single cover
        val covers = pl.coverTracks
        val mosaicViews = listOf(holder.mosaic1, holder.mosaic2, holder.mosaic3, holder.mosaic4)
        if (covers.size >= 4) {
            holder.singleCover.tag = null
            holder.singleCover.visibility = View.GONE
            holder.mosaicGrid.visibility = View.VISIBLE
            for (i in 0 until 4) {
                CoverLoader.loadInto(mosaicViews[i], store, covers[i], 256, R.drawable.ic_music_note)
            }
        } else {
            for (mv in mosaicViews) {
                mv.tag = null
            }
            holder.mosaicGrid.visibility = View.GONE
            holder.singleCover.visibility = View.VISIBLE
            CoverLoader.loadInto(holder.singleCover, store, covers.firstOrNull(), 384, R.drawable.ic_library)
        }

        bindPlaybackState(holder, pl)

        if (onPlayClick != null) {
            holder.playBtn?.visibility = View.VISIBLE
            holder.playBtn?.setOnClickListener { onPlayClick.invoke(pl) }
        } else {
            holder.playBtn?.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onPlaylistClick(pl) }
    }

    private fun bindPlaybackState(holder: ViewHolder, pl: Playlist) {
        val isActive = isPlaylistActive(pl, activePlaylistId, activeCollectionKey)
        val isActivelyPlaying = isActive && isPlaying

        holder.name.setTextColor(
            ContextCompat.getColor(
                holder.itemView.context,
                if (isActive) R.color.accent_green else R.color.text_primary
            )
        )

        if (onPlayClick != null) {
            holder.playIcon?.setImageResource(if (isActivelyPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        }
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

    companion object {
        private const val PAYLOAD_PLAYBACK_STATE = "playback_state"
    }
}
