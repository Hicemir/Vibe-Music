package com.vibemusic.nativeapp.ui

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vibemusic.nativeapp.R
import com.vibemusic.nativeapp.data.LyricLine

class LyricsAdapter(
    private val onLineClick: (LyricLine) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private val lines = mutableListOf<LyricLine>()
    private var activeIndex = -1

    fun submitLines(newLines: List<LyricLine>) {
        lines.clear()
        lines.addAll(newLines)
        activeIndex = -1
        notifyDataSetChanged()
    }

    fun updateCurrentTime(currentPosMs: Long): Int {
        if (lines.isEmpty()) return -1
        var newActive = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= currentPosMs) {
                newActive = i
            } else {
                break
            }
        }
        if (newActive != activeIndex) {
            val old = activeIndex
            activeIndex = newActive
            if (old >= 0) notifyItemChanged(old)
            if (activeIndex >= 0) notifyItemChanged(activeIndex)
        }
        return activeIndex
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lines[position]
        holder.text.text = line.text

        if (position == activeIndex) {
            holder.text.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.accent_green))
            holder.text.setTypeface(null, Typeface.BOLD)
            holder.text.textSize = 22f
        } else {
            holder.text.setTextColor(ContextCompat.getColor(holder.itemView.context, R.color.text_secondary))
            holder.text.setTypeface(null, Typeface.NORMAL)
            holder.text.textSize = 18f
        }

        holder.itemView.setOnClickListener { onLineClick(line) }
    }

    override fun getItemCount(): Int = lines.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.lyric_line_text)
    }
}
