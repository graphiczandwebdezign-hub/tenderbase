package com.tenderbase.app

import android.content.res.ColorStateList
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.card.MaterialCardView

/**
 * Binds a [Tender] onto an inflated `item_tender.xml` view. Shared by the
 * discovery list and the deadlines dashboard so both render identically.
 */
object TenderCardBinder {

    fun bind(
        view: View,
        t: Tender,
        isSaved: Boolean,
        onClick: (Tender) -> Unit,
        onSaveToggle: (Tender) -> Unit
    ) {
        val ctx = view.context
        val card: MaterialCardView = view.findViewById(R.id.tenderCard)
        val badge: TextView = view.findViewById(R.id.statusBadge)
        val source: TextView = view.findViewById(R.id.sourceText)
        val title: TextView = view.findViewById(R.id.tvTitle)
        val org: TextView = view.findViewById(R.id.tvOrg)
        val ref: TextView = view.findViewById(R.id.tvRef)
        val location: TextView = view.findViewById(R.id.locationTag)
        val category: TextView = view.findViewById(R.id.categoryTag)
        val closes: TextView = view.findViewById(R.id.tvCloses)
        val save: ImageButton = view.findViewById(R.id.saveButton)

        // Status badge (text + colour, never colour alone).
        val label = t.badgeLabel()
        badge.text = label
        val (bg, fg) = when (label) {
            "OPEN" -> R.drawable.bg_badge_open to R.color.badgeOpenText
            "CLOSING SOON" -> R.drawable.bg_badge_soon to R.color.badgeSoonText
            "CANCELLED" -> R.drawable.bg_badge_cancelled to R.color.badgeCancelledText
            else -> R.drawable.bg_badge_closed to R.color.badgeClosedText
        }
        badge.setBackgroundResource(bg)
        badge.setTextColor(ctx.getColor(fg))

        title.text = t.title
        org.text = t.organisation ?: "Unknown organisation"
        if (t.reference != null) {
            ref.visibility = View.VISIBLE
            ref.text = ctx.getString(R.string.ref_prefix, t.reference)
        } else {
            ref.visibility = View.GONE
        }

        val locationText = listOfNotNull(t.province, t.municipality).joinToString(" · ")
        location.visibility = if (locationText.isEmpty()) View.GONE else View.VISIBLE
        location.text = locationText
        val categoryText = t.category
            ?: t.categories.firstOrNull()?.replace('-', ' ')?.replaceFirstChar { it.uppercase() }
        category.visibility = if (categoryText == null) View.GONE else View.VISIBLE
        category.text = categoryText

        val urgencyColor = when (DateUtils.urgency(t.closingAt, t.closingDate, t.deadlineState)) {
            DateUtils.Urgency.CLOSED -> R.color.textMuted
            DateUtils.Urgency.TODAY, DateUtils.Urgency.URGENT -> R.color.urgent
            DateUtils.Urgency.SOON -> R.color.warning
            DateUtils.Urgency.NORMAL -> R.color.primary
        }
        closes.text = DateUtils.closesLabel(t.closingAt, t.closingDate, t.deadlineState)
        closes.setTextColor(ctx.getColor(urgencyColor))
        closes.compoundDrawableTintList = ColorStateList.valueOf(ctx.getColor(urgencyColor))

        source.text = t.source ?: ""

        save.setImageResource(if (isSaved) R.drawable.ic_star_filled else R.drawable.ic_star_border)
        save.contentDescription =
            ctx.getString(if (isSaved) R.string.cd_unsave else R.string.cd_save)
        save.setOnClickListener {
            save.isEnabled = false
            save.post { save.isEnabled = true }
            onSaveToggle(t)
        }

        card.setOnClickListener { onClick(t) }
    }
}
