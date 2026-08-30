package com.tenderbase.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class TenderAdapter(
    initialItems: List<Tender>,
    private val onClick: (Tender) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = initialItems.toMutableList()
    private var showFooter = false

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_FOOTER = 1
    }

    fun submit(newItems: List<Tender>) {
        items.clear()
        items.addAll(newItems)
        showFooter = false
        notifyDataSetChanged()
    }

    fun append(newItems: List<Tender>) {
        val start = items.size
        items.addAll(newItems)
        if (showFooter) {
            showFooter = false
            notifyItemRangeInserted(start, newItems.size)
            notifyItemRemoved(start + newItems.size)
        } else {
            notifyItemRangeInserted(start, newItems.size)
        }
    }

    fun showFooter() {
        if (showFooter) return
        showFooter = true
        notifyItemInserted(items.size)
    }

    fun hideFooter() {
        if (!showFooter) return
        showFooter = false
        notifyItemRemoved(items.size)
    }

    override fun getItemCount() = items.size + if (showFooter) 1 else 0

    override fun getItemViewType(position: Int): Int =
        if (position >= items.size) TYPE_FOOTER else TYPE_ITEM

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == TYPE_FOOTER) {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_load_more, parent, false)
            FooterVH(v)
        } else {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_tender, parent, false)
            VH(v)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is VH) {
            bindItem(holder, items[position])
        }
    }

    private fun bindItem(holder: VH, t: Tender) {
        holder.title.text = t.title
        holder.org.text = t.organisation ?: "Unknown organisation"

        holder.province.text = t.province ?: "SA (province n/a)"

        val label = DateUtils.closesLabel(t.closingAt, t.closingDate)
        holder.closes.text = label
        val urgent = DateUtils.isUrgent(t.closingAt, t.closingDate)
        val ctx = holder.itemView.context
        holder.closes.setTextColor(
            ctx.getColor(if (label == "Closed") R.color.textMuted
                         else if (urgent) R.color.urgent else R.color.primary)
        )

        // Category chips (up to 3).
        holder.chips.removeAllViews()
        val cats = if (t.categories.isNotEmpty()) t.categories
                   else listOfNotNull(t.category)
        for (c in cats.take(3)) {
            val chip = Chip(ctx)
            chip.text = c.replace('-', ' ').replaceFirstChar { it.uppercase() }
            chip.isClickable = false
            chip.isCheckable = false
            chip.setEnsureMinTouchTargetSize(false)
            holder.chips.addView(chip)
        }
        holder.chips.visibility = if (holder.chips.childCount == 0) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener { onClick(t) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val org: TextView = v.findViewById(R.id.tvOrg)
        val province: TextView = v.findViewById(R.id.tvProvince)
        val closes: TextView = v.findViewById(R.id.tvCloses)
        val chips: ChipGroup = v.findViewById(R.id.chipGroup)
    }

    class FooterVH(v: View) : RecyclerView.ViewHolder(v)
}
