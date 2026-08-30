package com.tenderbase.app

/**
 * Local deadline reminders: which saved tenders should produce a device
 * notification right now. Pure Kotlin — the caller owns notification posting
 * and the "already reminded" persistence.
 */
object DeadlineReminders {

    const val DEFAULT_WINDOW_HOURS = 48L

    /**
     * Saved tenders that are still open and close within [withinHours], minus
     * the ones already reminded. Server deadline_state wins over date math.
     */
    fun due(
        saved: List<Tender>,
        alreadyRemindedIds: Set<Int>,
        withinHours: Long = DEFAULT_WINDOW_HOURS
    ): List<Tender> {
        val windowMs = withinHours * 3_600_000L
        return saved.filter { t ->
            if (t.id in alreadyRemindedIds) return@filter false
            val state = t.deadlineState
            if (state == "CLOSED" || state == "EXPIRED" || state == "CANCELLED") return@filter false
            val millis = DateUtils.toMillis(t.closingAt, t.closingDate) ?: return@filter false
            val remaining = millis - System.currentTimeMillis()
            remaining in 1..windowMs
        }
    }
}
