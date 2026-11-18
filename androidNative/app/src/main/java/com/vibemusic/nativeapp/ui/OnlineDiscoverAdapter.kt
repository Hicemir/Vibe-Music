package com.vibemusic.nativeapp.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vibemusic.nativeapp.R
import com.vibemusic.nativeapp.data.CanonicalKey
import com.vibemusic.nativeapp.data.DiscoverItem
import com.vibemusic.nativeapp.data.DownloadJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube Music Style Horizontal Carousel Adapter for Online Discover:
 * Displays 4-song columns with live thumbnail cover arts and caching.
 * Tapping a song opens YouTube/browser link directly; tapping download icon enqueues download
 * and animates a circular green edge progress indicator.
 */
class OnlineDiscoverAdapter(
    private val scope: CoroutineScope,
    private val onItemClick: (DiscoverItem) -> Unit,
    private val onDownloadClick: (DiscoverItem) -> Unit
) : RecyclerView.Adapter<OnlineDiscoverAdapter.ViewHolder>() {

    private val currentItems = mutableListOf<DiscoverItem>()
    private val seenIds = mutableSetOf<String>()
    private val seenTitleKeys = mutableSetOf<String>()
    private val allColumns = mutableListOf<List<DiscoverItem>>()
    private var displayedColumns = 0
    private val bitmapCache = LruCache<String, Bitmap>(80)
    private var jobsByYtId: Map<String, DownloadJob> = emptyMap()

    fun submitItems(items: List<DiscoverItem>) {
        currentItems.clear()
        seenIds.clear()
        seenTitleKeys.clear()
        addUniqueItems(items)
        rebuildColumns()
        notifyDataSetChanged()
    }

    fun hasMoreColumns(): Boolean = displayedColumns < allColumns.size

    fun appendMoreColumns(count: Int = 2) {
        val oldPos = displayedColumns
        displayedColumns = (displayedColumns + count).coerceAtMost(allColumns.size)
        if (displayedColumns > oldPos) {
            notifyItemRangeInserted(oldPos, displayedColumns - oldPos)
        }
    }

    fun appendItems(newItems: List<DiscoverItem>) {
        if (newItems.isEmpty()) return
        val before = currentItems.size
        addUniqueItems(newItems)
        if (currentItems.size > before) {
            rebuildColumns()
            notifyDataSetChanged()
        }
    }

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

        val visibleCount = displayedColumns.coerceAtMost(allColumns.size)
        for (colIdx in 0 until visibleCount) {
            val colItems = allColumns[colIdx]
            val affected = colItems.any { item -> oldMap[item.ytId] != newMap[item.ytId] }
            if (affected) {
                notifyItemChanged(colIdx, PAYLOAD_DOWNLOAD_STATE)
            }
        }
    }

    private fun addUniqueItems(items: List<DiscoverItem>) {
        for (item in items) {
            if (seenIds.contains(item.ytId)) continue
            val titleKey = CanonicalKey.canonicalSongTitle(item.title, item.artist)
            if (titleKey.isNotEmpty() && seenTitleKeys.contains(titleKey)) continue
            seenIds.add(item.ytId)
            if (titleKey.isNotEmpty()) seenTitleKeys.add(titleKey)
            currentItems.add(item)
        }
    }

    private fun rebuildColumns() {
        allColumns.clear()
        allColumns.addAll(currentItems.chunked(4))
        displayedColumns = allColumns.size
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_carousel_column, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val items = allColumns[position]
        val rowViews = listOf(holder.row0, holder.row1, holder.row2, holder.row3)

        for (i in 0 until 4) {
            val rowView = rowViews[i]
            if (i < items.size) {
                rowView.visibility = View.VISIBLE
                val item = items[i]
                val coverImg = rowView.findViewById<ImageView>(R.id.row_cover)
                val titleTxt = rowView.findViewById<TextView>(R.id.row_title)
                val artistTxt = rowView.findViewById<TextView>(R.id.row_artist)
                val downloadBtn = rowView.findViewById<ImageView>(R.id.row_more)

                titleTxt.text = item.title
                val durStr = if (item.duration > 0) " • %d:%02d".format((item.duration / 60).toInt(), (item.duration % 60).toInt()) else ""
                artistTxt.text = "${item.artist}$durStr"

                bindDownloadButton(downloadBtn, item)

                coverImg.tag = item.thumbnailUrl
                val cached = if (item.thumbnailUrl.isNotBlank()) bitmapCache.get(item.thumbnailUrl) else null
                if (cached != null) {
                    coverImg.setImageBitmap(cached)
                } else {
                    coverImg.setImageResource(R.drawable.ic_music_note)
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
                                        if (coverImg.tag == thumbUrl) {
                                            coverImg.setImageBitmap(bmp)
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                // Clicking the song opens YouTube app / browser directly
                rowView.setOnClickListener {
                    onItemClick(item)
                }

                // Dedicated download button with circular green progress animation
                downloadBtn.setOnClickListener {
                    val drawable = downloadBtn.drawable as? CircularDownloadDrawable
                    if (drawable == null || drawable.currentState == CircularDownloadDrawable.State.IDLE) {
                        drawable?.update(CircularDownloadDrawable.State.PENDING, 0f)
                        onDownloadClick(item)
                    }
                }
            } else {
                rowView.visibility = View.GONE
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_DOWNLOAD_STATE)) {
            val items = allColumns[position]
            val rowViews = listOf(holder.row0, holder.row1, holder.row2, holder.row3)
            for (i in 0 until 4) {
                if (i < items.size) {
                    val downloadBtn = rowViews[i].findViewById<ImageView>(R.id.row_more)
                    bindDownloadButton(downloadBtn, items[i])
                }
            }
            return
        }
        onBindViewHolder(holder, position)
    }

    private fun bindDownloadButton(downloadBtn: ImageView, item: DiscoverItem) {
        downloadBtn.imageTintList = null
        downloadBtn.clearColorFilter()
        val pad = (5f * downloadBtn.resources.displayMetrics.density).toInt()
        downloadBtn.setPadding(pad, pad, pad, pad)
        downloadBtn.setBackgroundResource(R.drawable.bg_circle_ripple)
        downloadBtn.contentDescription = "Download ${item.title}"

        val drawable = (downloadBtn.drawable as? CircularDownloadDrawable)
            ?: CircularDownloadDrawable(downloadBtn.context).also {
                downloadBtn.setImageDrawable(it)
            }

        val job = jobsByYtId[item.ytId]
        val (state, pct) = when {
            item.isOwned || job?.status == "done" -> CircularDownloadDrawable.State.DONE to 100f
            job?.status == "downloading" -> CircularDownloadDrawable.State.DOWNLOADING to job.progress
            job?.status == "pending" -> CircularDownloadDrawable.State.PENDING to 0f
            else -> CircularDownloadDrawable.State.IDLE to 0f
        }
        drawable.update(state, pct)
    }

    override fun getItemCount(): Int = displayedColumns.coerceAtMost(allColumns.size)

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val row0: View = view.findViewById(R.id.col_row_0)
        val row1: View = view.findViewById(R.id.col_row_1)
        val row2: View = view.findViewById(R.id.col_row_2)
        val row3: View = view.findViewById(R.id.col_row_3)
    }

    companion object {
        private const val PAYLOAD_DOWNLOAD_STATE = "download_state"
    }
}
