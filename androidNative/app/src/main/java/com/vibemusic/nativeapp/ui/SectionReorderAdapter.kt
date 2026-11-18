package com.vibemusic.nativeapp.ui

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.vibemusic.nativeapp.R

class SectionReorderAdapter(
    initialOrder: List<String>,
    initialHidden: Set<String>,
    private val onStartDrag: ((RecyclerView.ViewHolder) -> Unit)? = null,
    private val onOrderChanged: (List<String>, Set<String>) -> Unit
) : RecyclerView.Adapter<SectionReorderAdapter.ViewHolder>() {

    val currentOrder = initialOrder.filter { it != "allMusic" }.toMutableList()
    val currentHidden = initialHidden.toMutableSet()

    private val sectionNames = mapOf(
        "recentPlaylists" to "Recent Playlists",
        "quickPicks" to "Quick Picks",
        "topTracks" to "Most Played",
        "artistRadios" to "Artist Radios",
        "discover" to "Online Discover",
        "recentlyAdded" to "Recently Added"
    )

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_section_reorder, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val key = currentOrder[position]
        holder.name.text = sectionNames[key] ?: key

        holder.switchView.setOnCheckedChangeListener(null)
        holder.switchView.isChecked = !currentHidden.contains(key)

        holder.switchView.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                currentHidden.remove(key)
            } else {
                currentHidden.add(key)
            }
            onOrderChanged(currentOrder, currentHidden)
        }

        holder.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag?.invoke(holder)
            }
            false
        }
    }

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition == toPosition ||
            fromPosition !in currentOrder.indices ||
            toPosition !in currentOrder.indices
        ) return
        val item = currentOrder.removeAt(fromPosition)
        currentOrder.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
        onOrderChanged(currentOrder, currentHidden)
    }

    override fun getItemCount(): Int = currentOrder.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val switchView: SwitchCompat = view.findViewById(R.id.section_switch)
        val name: TextView = view.findViewById(R.id.section_name)
        val dragHandle: ImageView = view.findViewById(R.id.section_drag_handle)
    }
}
