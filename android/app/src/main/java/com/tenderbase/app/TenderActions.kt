package com.tenderbase.app

/**
 * Tender action helpers: deep links, share summaries, calendar slots and file
 * sizes. Pure Kotlin (no Android imports) so everything here is unit-testable
 * on the JVM.
 */
object TenderActions {

    const val DEEP_LINK_SCHEME = "tenderbase"
    const val DEEP_LINK_HOST = "tender"

    // ------------------------------------------------------------- deep links

    /** Stable in-app link for a tender, e.g. "tenderbase://tender/42". */
    fun deepLinkFor(id: Int): String = "$DEEP_LINK_SCHEME://$DEEP_LINK_HOST/$id"

    /**
     * Parse a deep link back into a tender id. Returns null unless the scheme,
     * host and numeric id are all exactly right — a malformed link must never
     * open the wrong tender.
     */
    fun parseDeepLink(scheme: String?, host: String?, path: String?): Int? {
        if (scheme != DEEP_LINK_SCHEME || host != DEEP_LINK_HOST) return null
        val id = path?.trim('/')?.toIntOrNull() ?: return null
        return id.takeIf { it > 0 }
    }

    // ----------------------------------------------------------------- share

    /** Structured share text: what the tender is, when it closes, where to act. */
    fun shareSummary(t: Tender): String = buildString {
        appendLine(t.title)
        appendLine()
        t.organisation?.let { appendLine("Organisation: $it") }
        t.reference?.let { appendLine("Reference: $it") }
        appendLine("Closing: ${DateUtils.prettyDate(t.closingAt, t.closingDate)}")
        t.province?.let { appendLine("Province: $it") }
        t.category?.let { appendLine("Category: $it") }
        appendLine()
        append("View in TenderBase: ${deepLinkFor(t.id)}")
        t.sourceUrl?.let { append("\nSource: $it") }
    }

    // -------------------------------------------------------------- calendar

    data class CalendarSlot(
        val beginMillis: Long,
        val endMillis: Long,
        val allDay: Boolean
    )

    /**
     * Calendar event slot for the deadline. Uses the exact closing instant
     * when known (1-hour event); falls back to an all-day event on the
     * closing date. Null when the tender has no deadline at all.
     */
    fun calendarSlot(t: Tender): CalendarSlot? {
        val millis = DateUtils.toMillis(t.closingAt, t.closingDate) ?: return null
        return if (t.closingAt != null) {
            CalendarSlot(millis, millis + 60 * 60 * 1000, allDay = false)
        } else {
            CalendarSlot(millis, millis + 24 * 60 * 60 * 1000 - 1, allDay = true)
        }
    }

    // ------------------------------------------------------------- file size

    /** Human file size: "512 KB", "1.4 MB"; null when the size is unknown. */
    fun formatFileSize(bytes: Long?): String? {
        if (bytes == null || bytes < 0) return null
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "${round(kb)} KB"
        val mb = kb / 1024.0
        if (mb < 1024) return "${round(mb)} MB"
        // Above 1024 MiB we report in decimal GB (1e9), the convention the
        // source portals use for their advertised file sizes.
        return "${round(bytes / 1_000_000_000.0)} GB"
    }

    private fun round(v: Double): String =
        if (v >= 100) String.format(java.util.Locale.US, "%.0f", v)
        else String.format(java.util.Locale.US, "%.1f", v)
}
