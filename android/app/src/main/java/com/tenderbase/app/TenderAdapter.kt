package com.tenderbase.app

import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Discovery list adapter. Renders tender cards plus supporting rows:
 * skeleton placeholders (initial load), a footer with loading / retry / end
 * states, and stable ids so pagination never duplicates rows.
 */
class TenderAdapter(
    private val onTenderClick: (Tender) -> Unit,
    private val onSaveToggle: (Tender) -> Unit,
    private val onRetry: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    enum class FooterState { LOADING, RETRY, END }

    private sealed class Row {
        data class Item(val tender: Tender) : Row()
        class Skeleton : Row()
        data class Footer(val state: FooterState) : Row()
    }

    private val rows = mutableListOf<Row>()
    private val visibleIds = mutableSetOf<Int>()
    private var footer: Row.Footer? = null
    private var savedIds: Set<Int> = emptySet()

    companion object {
        private const val TYPE_ITEM = 0
        private const val TYPE_SKELETON = 1
        private const val TYPE_FOOTER = 2
        private const val SKELETON_COUNT = 7
    }

    // ------------------------------------------------------------- updates

    /** Show pulsing placeholders while the first page loads. */
    fun showSkeletons() {
        visibleIds.clear()
        rows.clear()
        footer = null
        repeat(SKELETON_COUNT) { rows.add(Row.Skeleton()) }
        notifyDataSetChanged()
    }

    /** Replace the whole list (new search/filters/page 1). */
    fun submitTenders(items: List<Tender>, footerState: FooterState?) {
        visibleIds.clear()
        rows.clear()
        appendInternal(items)
        setFooter(footerState)
    }

    /** Append the next page, skipping any ids already shown. */
    fun appendTenders(items: List<Tender>, footerState: FooterState?) {
        appendInternal(items)
        setFooter(footerState)
    }

    /** Swap only the footer row (e.g. loading -> retry after a failed page). */
    fun submitFooter(state: FooterState) {
        setFooter(state)
    }

    /** True when the given tender id is currently displayed. */
    fun contains(id: Int): Boolean = id in visibleIds

    /** The tender at a list position, or null for footer/skeleton rows. */
    fun tenderAt(position: Int): Tender? =
        (rows.getOrNull(position) as? Row.Item)?.tender

    /** Remove one tender from the list (e.g. swiped away). Returns its position or -1. */
    fun removeTender(id: Int): Int {
        val index = rows.indexOfFirst { it is Row.Item && it.tender.id == id }
        if (index < 0) return -1
        rows.removeAt(index)
        visibleIds.remove(id)
        notifyItemRemoved(index)
        return index
    }

    /** Re-insert a previously removed tender at the given position (undo). */
    fun reinsertTender(t: Tender, position: Int) {
        if (t.id in visibleIds) return
        val pos = position.coerceIn(0, rows.size)
        rows.add(pos, Row.Item(t))
        visibleIds.add(t.id)
        notifyItemInserted(pos)
    }

    fun setSavedIds(ids: Set<Int>) {
        savedIds = ids
        notifyItemRangeChanged(0, itemCount)
    }

    val tenderCount: Int get() = visibleIds.size

    private fun appendInternal(items: List<Tender>) {
        for (t in items) {
            if (visibleIds.add(t.id)) rows.add(Row.Item(t))
        }
    }

    private fun setFooter(state: FooterState?) {
        footer = state?.let { Row.Footer(it) }
        notifyDataSetChanged()
    }

    // ------------------------------------------------------------ recycling

    override fun getItemCount(): Int = rows.size + if (footer != null) 1 else 0

    override fun getItemViewType(position: Int): Int =
        when {
            position >= rows.size -> TYPE_FOOTER
            rows[position] is Row.Item -> TYPE_ITEM
            else -> TYPE_SKELETON
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_SKELETON -> SkeletonVH(
                inflater.inflate(R.layout.item_tender_skeleton, parent, false)
            )
            TYPE_FOOTER -> FooterVH(
                inflater.inflate(R.layout.item_load_more, parent, false)
            ).apply {
                itemView.setOnClickListener { onRetry() }
            }
            else -> TenderVH(
                inflater.inflate(R.layout.item_tender, parent, false)
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is TenderVH -> (rows.getOrNull(position) as? Row.Item)?.let { holder.bind(it.tender) }
            is FooterVH -> footer?.let { holder.bind(it.state) }
            is SkeletonVH -> holder.startPulse()
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is SkeletonVH) holder.stopPulse()
        super.onViewRecycled(holder)
    }

    // ----------------------------------------------------------- view holders

    inner class TenderVH(v: View) : RecyclerView.ViewHolder(v) {
        fun bind(t: Tender) {
            TenderCardBinder.bind(
                view = itemView,
                t = t,
                isSaved = t.id in savedIds,
                onClick = onTenderClick,
                onSaveToggle = onSaveToggle
            )
        }
    }

    class SkeletonVH(v: View) : RecyclerView.ViewHolder(v) {
        private var pulse: ObjectAnimator? = null

        fun startPulse() {
            stopPulse()
            pulse = ObjectAnimator.ofFloat(itemView, "alpha", 1f, 0.45f).apply {
                duration = 650
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        }

        fun stopPulse() {
            pulse?.cancel()
            pulse = null
            itemView.alpha = 1f
        }
    }

    class FooterVH(v: View) : RecyclerView.ViewHolder(v) {
        private val progress: View = v.findViewById(R.id.footerProgress)
        private val text: TextView = v.findViewById(R.id.footerText)

        fun bind(state: FooterState) {
            val ctx = itemView.context
            when (state) {
                FooterState.LOADING -> {
                    progress.visibility = View.VISIBLE
                    text.text = ctx.getString(R.string.loading_more)
                    itemView.isClickable = false
                }
                FooterState.RETRY -> {
                    progress.visibility = View.GONE
                    text.text = ctx.getString(R.string.retry_load_more)
                    itemView.isClickable = true
                }
                FooterState.END -> {
                    progress.visibility = View.GONE
                    text.text = ctx.getString(R.string.end_of_results)
                    itemView.isClickable = false
                }
            }
        }
    }
}
