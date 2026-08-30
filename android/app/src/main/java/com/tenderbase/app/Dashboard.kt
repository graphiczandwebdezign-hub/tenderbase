package com.tenderbase.app

/**
 * Deadline command-centre logic: grouping saved tenders into urgency buckets
 * and hidden-tender filtering. Pure Kotlin — unit-testable on the JVM.
 */
object Dashboard {

    /** Urgency buckets for saved deadlines, displayed in this order. */
    enum class Bucket {
        CLOSED,      // deadline passed (or server says closed)
        TODAY,       // closes today
        THIS_WEEK,   // 2–7 days out
        TWO_WEEKS,   // 8–14 days out
        LATER,       // more than 14 days out
        NO_DATE      // no known deadline
    }

    /** Bucket for one tender; server deadline_state wins over date math. */
    fun bucketOf(t: Tender): Bucket {
        val urgency = DateUtils.urgency(t.closingAt, t.closingDate, t.deadlineState)
        return when (urgency) {
            DateUtils.Urgency.CLOSED -> Bucket.CLOSED
            DateUtils.Urgency.TODAY -> Bucket.TODAY
            DateUtils.Urgency.URGENT -> Bucket.THIS_WEEK   // 1–6 days
            DateUtils.Urgency.SOON -> Bucket.TWO_WEEKS     // 7–14 days
            DateUtils.Urgency.NORMAL -> Bucket.LATER
        }.takeIf { t.closingAt != null || t.closingDate != null } ?: Bucket.NO_DATE
    }

    /**
     * Group saved tenders by bucket, soonest first within each bucket.
     * Tenders without dates sort last within NO_DATE by title for stability.
     */
    fun groupByDeadline(tenders: List<Tender>): List<Pair<Bucket, List<Tender>>> {
        val byBucket = tenders.groupBy { bucketOf(it) }
        return Bucket.entries.mapNotNull { bucket ->
            val items = byBucket[bucket] ?: return@mapNotNull null
            val sorted = when (bucket) {
                Bucket.NO_DATE -> items.sortedBy { it.title.lowercase() }
                else -> items.sortedBy { DateUtils.toMillis(it.closingAt, it.closingDate) ?: Long.MAX_VALUE }
            }
            bucket to sorted
        }
    }

    /** Drop tenders the user has hidden (client-side personal dismiss). */
    fun filterHidden(tenders: List<Tender>, hiddenIds: Set<Int>): List<Tender> =
        if (hiddenIds.isEmpty()) tenders else tenders.filter { it.id !in hiddenIds }

    /** Discovery filters for the "closing this week" dashboard section. */
    fun closingThisWeekFilters(): SearchFilters =
        SearchFilters.closingWeekPreset().copy(status = StatusFilter.OPEN)
}
