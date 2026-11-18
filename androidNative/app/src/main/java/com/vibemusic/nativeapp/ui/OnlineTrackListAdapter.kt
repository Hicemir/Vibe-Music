package com.vibemusic.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vibemusic.nativeapp.R
import com.vibemusic.nativeapp.data.DiscoverItem
import com.vibemusic.nativeapp.data.DownloadJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

class OnlineTrackListAdapter(
    private val scope: CoroutineScope,
    private val onTrackClick: (DiscoverItem) -> Unit,
    private val onDownloadClick: (DiscoverItem) -> Unit,
    private val onAddClick: ((DiscoverItem, TextView) -> Unit)? = null
) : RecyclerView.Adapter<OnlineTrackListAdapter.ViewHolder>() {

    private val items = mutableListOf<DiscoverItem>()
    private val bitmapCache = LruCache<String, Bitmap>(60)
    private var jobsByYtId: Map<String, DownloadJob> = emptyMap()

    fun submitList(newItems: List<DiscoverItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getItems(): List<DiscoverItem> = items.toList()

    fun updateDownloadJobs(jobs: List<DownloadJob>) {
        val newMap = LinkedHashMap<String, DownloadJob>()
        for (job in jobs) {
            if (job.ytId.isNotBlank() && !newMap.containsKey(job.ytId)) {
                newMap[job.ytId] = job
            }
        }
        val oldMap = jobsByYtId
        if (oldMap == newMap) return
        jobsByYtId = newMap

        for (i in items.indices) {
            val ytId = items[i].ytId
            if (oldMap[ytId] != newMap[ytId]) {
                notifyItemChanged(i, PAYLOAD_DOWNLOAD_STATE)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.artist.text = item.artist

        if (item.duration > 0) {
            val m = (item.duration / 60).toInt()
            val s = (item.duration % 60).toInt()
            holder.duration.text = "%d:%02d".format(m, s)
        } else {
            holder.duration.text = ""
        }

        bindDownloadState(holder, item)

        // Thumbnail loading with cache
        holder.cover.tag = item.thumbnailUrl
        val cached = if (item.thumbnailUrl.isNotBlank()) bitmapCache.get(item.thumbnailUrl) else null
        if (cached != null) {
            holder.cover.setImageBitmap(cached)
        } else {
            holder.cover.setImageResource(R.drawable.ic_music_note)
            if (item.thumbnailUrl.isNotBlank()) {
                val thumbUrl = item.thumbnailUrl
                scope.launch(Dispatchers.IO) {
                    try {
                        val conn = URL(thumbUrl).openConnection() as HttpURLConnection
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        conn.doInput = true
                        conn.connect()
                        val input = conn.inputStream
                        val bmp = BitmapFactory.decodeStream(input)
                        input.close()
                        if (bmp != null) {
                            bitmapCache.put(thumbUrl, bmp)
                            withContext(Dispatchers.Main) {
                                if (holder.cover.tag == thumbUrl) {
                                    holder.cover.setImageBitmap(bmp)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        holder.itemView.setOnClickListener { onTrackClick(item) }
        holder.menu.setOnClickListener {
            val drawable = holder.menu.drawable as? CircularDownloadDrawable
            if (drawable == null || drawable.currentState == CircularDownloadDrawable.State.IDLE) {
                drawable?.update(CircularDownloadDrawable.State.PENDING, 0f)
                onDownloadClick(item)
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_DOWNLOAD_STATE)) {
            bindDownloadState(holder, items[position])
            return
        }
        onBindViewHolder(holder, position)
    }

    private fun bindDownloadState(holder: ViewHolder, item: DiscoverItem) {
        val context = holder.itemView.context
        val job = jobsByYtId[item.ytId]

        holder.menu.imageTintList = null
        holder.menu.clearColorFilter()
        val pad = (6f * context.resources.displayMetrics.density).toInt()
        holder.menu.setPadding(pad, pad, pad, pad)
        holder.menu.setBackgroundResource(R.drawable.bg_circle_ripple)
        holder.menu.contentDescription = "Download ${item.title}"

        val drawable = (holder.menu.drawable as? CircularDownloadDrawable)
            ?: CircularDownloadDrawable(context).also {
                holder.menu.setImageDrawable(it)
            }

        val (state, pct) = when {
            item.isOwned || job?.status == "done" -> CircularDownloadDrawable.State.DONE to 100f
            job?.status == "downloading" -> CircularDownloadDrawable.State.DOWNLOADING to job.progress
            job?.status == "pending" -> CircularDownloadDrawable.State.PENDING to 0f
            else -> CircularDownloadDrawable.State.IDLE to 0f
        }
        drawable.update(state, pct)

        if (onAddClick != null) {
            holder.btnAdd.visibility = View.VISIBLE
            when (state) {
                CircularDownloadDrawable.State.DOWNLOADING -> {
                    holder.btnAdd.text = "${pct.roundToInt().coerceIn(0, 100)}%"
                    holder.btnAdd.isEnabled = false
                    holder.btnAdd.setTextColor(ContextCompat.getColor(context, R.color.accent_green))
                }
                CircularDownloadDrawable.State.PENDING -> {
                    holder.btnAdd.text = "Queued"
                    holder.btnAdd.isEnabled = false
                    holder.btnAdd.setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }
                CircularDownloadDrawable.State.DONE -> {
                    holder.btnAdd.text = "✓ Added"
                    holder.btnAdd.isEnabled = false
                    holder.btnAdd.setTextColor(ContextCompat.getColor(context, R.color.text_muted))
                }
                CircularDownloadDrawable.State.IDLE -> {
                    holder.btnAdd.text = "+ Add"
                    holder.btnAdd.isEnabled = true
                    holder.btnAdd.setTextColor(ContextCompat.getColor(context, R.color.accent_green))
                    holder.btnAdd.setOnClickListener {
                        onAddClick.invoke(item, holder.btnAdd)
                    }
                }
            }
        } else {
            holder.btnAdd.visibility = View.GONE
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.track_cover)
        val title: TextView = view.findViewById(R.id.track_title)
        val artist: TextView = view.findViewById(R.id.track_artist)
        val duration: TextView = view.findViewById(R.id.track_duration)
        val btnAdd: TextView = view.findViewById(R.id.btn_track_add)
        val menu: ImageView = view.findViewById(R.id.track_menu)
    }

    companion object {
        private const val PAYLOAD_DOWNLOAD_STATE = "download_state"
    }
}
