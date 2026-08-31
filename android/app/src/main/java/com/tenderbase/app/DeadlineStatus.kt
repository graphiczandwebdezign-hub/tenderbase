package com.tenderbase.app

/**
 * Deadline urgency tiers for the redesigned UI (FIND → … → NEVER MISS THE
 * DEADLINE). Pure Kotlin so the exact tier boundaries are unit-tested.
 *
 * Colour is never the only signal: every tier carries a text label and is
 * paired with an icon by the UI layer.
 *
 * Boundaries (product spec):
 *  - SAFE:        closing in more than 14 days
 *  - UPCOMING:    closing in 7–14 days
 *  - CLOSING_SOON closing in 2–6 days
 *  - URGENT:      closing within 48 hours (or today)
 *  - CLOSED:      deadline passed / server says closed
 *  - NONE:        no closing date on the record
 */
enum class DeadlineTier { NONE, CLOSED, URGENT, CLOSING_SOON, UPCOMING, SAFE }

object DeadlineStatus {

    /** Whole days up to which the deadline counts as urgent (48 h window). */
    const val URGENT_DAYS = 2
    const val CLOSING_SOON_DAYS = 6
    const val UPCOMING_DAYS = 14

    private val CLOSED_STATES = setOf("CLOSED", "EXPIRED", "CANCELLED")

    /**
     * Tier from days-until-closing (as produced by [DateUtils.daysUntilClosing],
     * already rounded up) and the server's `deadline_state`. The server state
     * always wins for closures; the device clock only refines the open tiers.
     */
    fun tierOf(daysUntil: Int?, deadlineState: String?): DeadlineTier {
        if (deadlineState in CLOSED_STATES) return DeadlineTier.CLOSED
        if (daysUntil == null) return DeadlineTier.NONE
        return when {
            daysUntil <= 0 -> DeadlineTier.CLOSED
            daysUntil <= URGENT_DAYS -> DeadlineTier.URGENT
            daysUntil <= CLOSING_SOON_DAYS -> DeadlineTier.CLOSING_SOON
            daysUntil <= UPCOMING_DAYS -> DeadlineTier.UPCOMING
            else -> DeadlineTier.SAFE
        }
    }

    /** Convenience: tier straight from a [Tender]. */
    fun tierOf(t: Tender): DeadlineTier =
        tierOf(DateUtils.daysUntilClosing(t.closingAt, t.closingDate), t.deadlineState)

    /** True when a saved tender deserves the "closing within 48h" banner. */
    fun isClosingWithin48h(t: Tender): Boolean =
        tierOf(t) == DeadlineTier.URGENT &&
            !(t.deadlineState in CLOSED_STATES) &&
            !DateUtils.isClosed(t.closingAt, t.closingDate)

    /**
     * Strong, human urgency label for cards and the deadline block:
     * "Closes today" / "Closes in 2 days" / "Closes in 9 days" /
     * "Closes in 23 days" / "Closed" / "Cancelled" / "No deadline listed".
     */
    fun label(daysUntil: Int?, deadlineState: String?): String {
        if (deadlineState.equals("CANCELLED", ignoreCase = true)) return "Cancelled"
        val tier = tierOf(daysUntil, deadlineState)
        return when (tier) {
            DeadlineTier.CLOSED -> "Closed"
            DeadlineTier.NONE -> "No deadline listed"
            DeadlineTier.SAFE -> if (daysUntil == null) "Open" else "Closes in $daysUntil days"
            else -> {
                val d = daysUntil ?: 0
                when {
                    d <= 0 -> "Closes today"
                    d == 1 -> "Closes in 1 day"
                    else -> "Closes in $d days"
                }
            }
        }
    }

    /** Short badge word used in compact rows: "2 DAYS" etc. Text, never colour-only. */
    fun shortBadge(daysUntil: Int?, deadlineState: String?): String {
        val tier = tierOf(daysUntil, deadlineState)
        return when (tier) {
            DeadlineTier.CLOSED -> if (deadlineState.equals("CANCELLED", true)) "CANCELLED" else "CLOSED"
            DeadlineTier.NONE -> "NO DATE"
            DeadlineTier.URGENT -> {
                val d = daysUntil ?: 0
                when {
                    d <= 0 -> "CLOSING TODAY"
                    d == 1 -> "LAST DAY"
                    else -> "$d DAYS LEFT"
                }
            }
            DeadlineTier.CLOSING_SOON -> "CLOSING SOON"
            DeadlineTier.UPCOMING -> "UPCOMING"
            DeadlineTier.SAFE -> "OPEN"
        }
    }
}
