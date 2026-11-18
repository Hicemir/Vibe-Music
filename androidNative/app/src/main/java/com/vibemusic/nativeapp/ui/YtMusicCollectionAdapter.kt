package com.vibemusic.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vibemusic.nativeapp.R
import com.vibemusic.nativeapp.data.YtCollectionType
import com.vibemusic.nativeapp.data.YtMusicCollection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class YtMusicCollectionAdapter(
    private val scope: CoroutineScope,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<YtMusicCollectionAdapter.ViewHolder>() {

    private var allItems: List<YtMusicCollection> = emptyList()
    private val visibleItems = mutableListOf<YtMusicCollection>()
    private val selectedIds = linkedSetOf<String>()
    private var currentFilter = "all"
    private val bitmapCache = LruCache<String, Bitmap>(64)

    fun submitCollections(items: List<YtMusicCollection>, defaultSelectedIds: Set<String> = setOf("LM")) {
        allItems = items
        selectedIds.clear()
        for (item in items) {
            if (defaultSelectedIds.contains(item.id)) {
                selectedIds.add(item.id)
            }
        }
        applyFilter(currentFilter)
        onSelectionChanged(selectedIds.size)
    }

    fun applyFilter(filter: String) {
        currentFilter = filter
        visibleItems.clear()
        visibleItems.addAll(
            when (filter) {
                "playlists" -> allItems.filter { it.type == YtCollectionType.PLAYLIST || it.type == YtCollectionType.LIKED_MUSIC }
                "albums" -> allItems.filter { it.type == YtCollectionType.ALBUM }
                else -> allItems
            }
        )
        notifyDataSetChanged()
    }

    fun areAllVisibleSelected(): Boolean {
        return visibleItems.isNotEmpty() && visibleItems.all { selectedIds.contains(it.id) }
    }

    fun toggleSelectAllVisible(): Boolean {
        val selectAll = !areAllVisibleSelected()
        if (selectAll) {
            for (item in visibleItems) selectedIds.add(item.id)
        } else {
            for (item in visibleItems) selectedIds.remove(item.id)
        }
        notifyDataSetChanged()
        onSelectionChanged(selectedIds.size)
        return selectAll
    }

    fun getSelectedCollections(): List<YtMusicCollection> {
        return allItems.filter { selectedIds.contains(it.id) }
    }

    fun getVisibleCount(): Int = visibleItems.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_ytm_collection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = visibleItems[position]
        val context = holder.itemView.context

        holder.title.text = item.title
        holder.subtitle.text = when {
            item.subtitle.isNotBlank() && item.trackCountHint > 0 && !item.subtitle.contains(item.trackCountHint.toString()) ->
                "${item.subtitle} • ${item.trackCountHint} songs"
            item.subtitle.isNotBlank() -> item.subtitle
            item.type == YtCollectionType.ALBUM -> "Saved Album"
            item.type == YtCollectionType.LIKED_MUSIC -> "Syncs to Liked Songs"
            else -> "Playlist"
        }

        holder.checkbox.isChecked = selectedIds.contains(item.id)

        val pad10 = (10f * context.resources.displayMetrics.density).toInt()
        holder.thumb.tag = item.thumbnailUrl

        if (item.type == YtCollectionType.LIKED_MUSIC) {
            holder.thumb.setPadding(pad10, pad10, pad10, pad10)
            holder.thumb.setImageResource(R.drawable.ic_heart_filled)
            holder.thumb.setColorFilter(ContextCompat.getColor(context, R.color.accent_red))
        } else {
            val cached = if (item.thumbnailUrl.isNotBlank()) bitmapCache.get(item.thumbnailUrl) else null
            if (cached != null) {
                holder.thumb.clearColorFilter()
                holder.thumb.setPadding(0, 0, 0, 0)
                holder.thumb.setImageBitmap(cached)
            } else {
                holder.thumb.setPadding(pad10, pad10, pad10, pad10)
                holder.thumb.setImageResource(
                    if (item.type == YtCollectionType.ALBUM) R.drawable.ic_album else R.drawable.ic_library
                )
                holder.thumb.setColorFilter(ContextCompat.getColor(context, R.color.text_secondary))

                if (item.thumbnailUrl.isNotBlank()) {
                    val thumbUrl = item.thumbnailUrl
                    scope.launch(Dispatchers.IO) {
                        try {
                            val conn = URL(thumbUrl).openConnection() as HttpURLConnection
                            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                            conn.connectTimeout = 6000
                            conn.readTimeout = 6000
                            val stream = conn.inputStream
                            val bmp = BitmapFactory.decodeStream(stream)
                            stream.close()
                            conn.disconnect()
                            if (bmp != null) {
                                bitmapCache.put(thumbUrl, bmp)
                                withContext(Dispatchers.Main) {
                                    if (holder.thumb.tag == thumbUrl) {
                                        holder.thumb.clearColorFilter()
                                        holder.thumb.setPadding(0, 0, 0, 0)
                                        holder.thumb.setImageBitmap(bmp)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        holder.itemView.setOnClickListener {
            if (!selectedIds.add(item.id)) {
                selectedIds.remove(item.id)
            }
            holder.checkbox.isChecked = selectedIds.contains(item.id)
            onSelectionChanged(selectedIds.size)
        }
    }

    override fun getItemCount(): Int = visibleItems.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.ytm_col_thumb)
        val title: TextView = view.findViewById(R.id.ytm_col_title)
        val subtitle: TextView = view.findViewById(R.id.ytm_col_subtitle)
        val checkbox: CheckBox = view.findViewById(R.id.ytm_col_checkbox)
    }
}
