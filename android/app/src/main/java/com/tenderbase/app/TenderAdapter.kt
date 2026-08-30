package com.tenderbase.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class TenderAdapter(
    private var items: List<Tender>,
    private val onClick: (Tender) -> Unit
) : RecyclerView.Adapter<TenderAdapter.VH>() {

    fun submit(newItems: List<Tender>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val org: TextView = v.findViewById(R.id.tvOrg)
        val province: TextView = v.findViewById(R.id.tvProvince)
        val closes: TextView = v.findViewById(R.id.tvCloses)
        val chips: ChipGroup = v.findViewById(R.id.chipGroup)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tender, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
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
}
