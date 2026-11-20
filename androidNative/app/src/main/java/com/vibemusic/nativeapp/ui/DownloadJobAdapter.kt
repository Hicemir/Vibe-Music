package com.vibemusic.nativeapp.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vibemusic.nativeapp.R
import com.vibemusic.nativeapp.data.DownloadJob
import java.util.Locale
import kotlin.math.roundToInt

class DownloadJobAdapter(
    private val onCancelClick: ((DownloadJob) -> Unit)? = null,
    private val onRetryClick: ((DownloadJob) -> Unit)? = null
) : RecyclerView.Adapter<DownloadJobAdapter.ViewHolder>() {

    private val jobs = mutableListOf<DownloadJob>()

    fun submitList(newList: List<DownloadJob>) {
        if (jobs.size == newList.size && jobs.indices.all { jobs[it].id == newList[it].id }) {
            for (i in newList.indices) {
                val oldItem = jobs[i]
                val newItem = newList[i]
                if (oldItem != newItem) {
                    jobs[i] = newItem
                    notifyItemChanged(i, PAYLOAD_PROGRESS)
                }
            }
            return
        }
        jobs.clear()
        jobs.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_download_job, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val job = jobs[position]
        bindJobState(holder, job)
        holder.cancelBtn.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            val currentJob = if (pos in jobs.indices) jobs[pos] else job
            onCancelClick?.invoke(currentJob)
        }
        holder.icon.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            val currentJob = if (pos in jobs.indices) jobs[pos] else job
            if (currentJob.status == "error" || currentJob.status == "cancelled") {
                onRetryClick?.invoke(currentJob)
            }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS)) {
            bindJobState(holder, jobs[position])
            return
        }
        onBindViewHolder(holder, position)
    }

    private fun bindJobState(holder: ViewHolder, job: DownloadJob) {
        val context = holder.itemView.context
        holder.title.text = job.title.ifEmpty { job.url }
        holder.progress.progress = job.progress.roundToInt().coerceIn(0, 100)

        when (job.status) {
            "downloading" -> {
                val pctInt = job.progress.roundToInt().coerceIn(0, 100)
                val sizeText = when {
                    job.downloadedBytes > 0 && job.totalBytes > 0 ->
                        String.format(
                            Locale.US,
                            " • %.2f / %.2f MB",
                            job.downloadedBytes / 1048576.0,
                            job.totalBytes / 1048576.0
                        )
                    job.downloadedBytes > 0 ->
                        String.format(Locale.US, " • %.2f MB", job.downloadedBytes / 1048576.0)
                    else -> ""
                }
                holder.info.text = "${pctInt}%$sizeText"
                if (job.stage.isNotBlank()) {
                    holder.speedEta.text = job.stage
                } else {
                    val speedPart = job.speed.takeIf { it.isNotBlank() }
                    val etaPart = job.eta.takeIf { it.isNotBlank() }?.let { "ETA $it" }
                    holder.speedEta.text = listOfNotNull(speedPart, etaPart)
                        .joinToString(" • ")
                        .ifEmpty { "Downloading..." }
                }

                attachCircularDrawable(holder.icon)
                    .update(CircularDownloadDrawable.State.DOWNLOADING, job.progress)
                holder.icon.isClickable = false
                holder.cancelBtn.visibility = View.VISIBLE
            }
            "pending" -> {
                holder.info.text = "0% • Queued"
                holder.speedEta.text = "Waiting..."
                attachCircularDrawable(holder.icon)
                    .update(CircularDownloadDrawable.State.PENDING, 0f)
                holder.icon.isClickable = false
                holder.cancelBtn.visibility = View.VISIBLE
            }
            "done" -> {
                val sizeText = if (job.totalBytes > 0) {
                    String.format(
                        Locale.US,
                        " • %.2f / %.2f MB",
                        job.totalBytes / 1048576.0,
                        job.totalBytes / 1048576.0
                    )
                } else ""
                holder.info.text = "100%$sizeText • Completed"
                holder.speedEta.text = ""
                attachCircularDrawable(holder.icon)
                    .update(CircularDownloadDrawable.State.DONE, 100f)
                holder.icon.isClickable = false
                holder.cancelBtn.visibility = View.GONE
            }
            "error" -> {
                holder.info.text = "Tap \u21bb to retry \u2022 Error: ${job.error ?: "Failed"}"
                holder.speedEta.text = ""
                holder.icon.imageTintList = null
                holder.icon.setImageResource(R.drawable.ic_refresh)
                holder.icon.setColorFilter(ContextCompat.getColor(context, R.color.accent_green))
                holder.icon.isClickable = true
                holder.cancelBtn.visibility = View.VISIBLE
            }
            "cancelled" -> {
                holder.info.text = "Tap \u21bb to retry \u2022 ${job.error ?: "Cancelled"}"
                holder.speedEta.text = ""
                holder.icon.imageTintList = null
                holder.icon.setImageResource(R.drawable.ic_refresh)
                holder.icon.setColorFilter(ContextCompat.getColor(context, R.color.accent_green))
                holder.icon.isClickable = true
                holder.cancelBtn.visibility = View.VISIBLE
            }
            else -> {
                holder.info.text = job.status
                holder.speedEta.text = ""
                holder.icon.imageTintList = null
                holder.icon.setImageResource(R.drawable.ic_download)
                holder.icon.setColorFilter(ContextCompat.getColor(context, R.color.text_muted))
                holder.icon.isClickable = false
                holder.cancelBtn.visibility = View.GONE
            }
        }
    }

    private fun attachCircularDrawable(imageView: ImageView): CircularDownloadDrawable {
        imageView.imageTintList = null
        imageView.clearColorFilter()
        val existing = imageView.drawable as? CircularDownloadDrawable
        if (existing != null) return existing
        return CircularDownloadDrawable(imageView.context).also {
            imageView.setImageDrawable(it)
        }
    }

    override fun getItemCount(): Int = jobs.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.dl_title)
        val icon: ImageView = view.findViewById(R.id.dl_status_icon)
        val cancelBtn: ImageView = view.findViewById(R.id.btn_dl_cancel)
        val progress: ProgressBar = view.findViewById(R.id.dl_progress)
        val info: TextView = view.findViewById(R.id.dl_info)
        val speedEta: TextView = view.findViewById(R.id.dl_speed_eta)
    }

    companion object {
        private const val PAYLOAD_PROGRESS = "progress"
    }
}
