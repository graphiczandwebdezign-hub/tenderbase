package com.tenderbase.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

/**
 * Closing-date presentation helpers.
 *
 * Deadline *state* (open/closing-soon/closed) always comes from the server's
 * `deadline_state`; these helpers only format the human label from the stored
 * server timestamp (closing_at is a UTC instant).
 */
object DateUtils {

    const val DAY_MS = 1000.0 * 60 * 60 * 24

    /** Whole days until the deadline (rounded up); <= 0 once passed; null if unknown. */
    fun daysUntilClosing(closingAt: String?, closingDate: String?): Int? {
        val d = parse(closingAt) ?: parseDate(closingDate) ?: return null
        val diffMs = d.time - Date().time
        return ceil(diffMs / DAY_MS).toInt()
    }

    /** True when the deadline instant has passed (fallback when no server state). */
    fun isClosed(closingAt: String?, closingDate: String?): Boolean =
        daysUntilClosing(closingAt, closingDate)?.let { it <= 0 } ?: false

    /**
     * Urgency tiers per the discovery spec:
     * CLOSED / TODAY / URGENT (1-6 days) / SOON (7-14 days) / NORMAL.
     */
    enum class Urgency { NORMAL, SOON, URGENT, TODAY, CLOSED }

    fun urgency(closingAt: String?, closingDate: String?, deadlineState: String? = null): Urgency {
        if (deadlineState in CLOSED_STATES) return Urgency.CLOSED
        val days = daysUntilClosing(closingAt, closingDate) ?: return Urgency.NORMAL
        return when {
            days <= 0 -> Urgency.CLOSED
            endsToday(closingAt, closingDate) -> Urgency.TODAY
            days <= 6 -> Urgency.URGENT
            days <= 14 -> Urgency.SOON
            else -> Urgency.NORMAL
        }
    }

    private val CLOSED_STATES = setOf("CLOSED", "EXPIRED", "CANCELLED")

    /** True when the deadline falls on the current calendar day. */
    private fun endsToday(closingAt: String?, closingDate: String?): Boolean {
        val d = parse(closingAt) ?: parseDate(closingDate) ?: return false
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return dayFmt.format(d) == dayFmt.format(Date())
    }

    /**
     * Human closing label:
     * "Closes today" / "Closing in 3 days" / "Closing soon · 4 Sep" /
     * "Closes 4 Sep 2026" / "Closed".
     */
    fun closesLabel(closingAt: String?, closingDate: String?, deadlineState: String? = null): String {
        if (deadlineState == "CANCELLED") return "Cancelled"
        val d = parse(closingAt) ?: parseDate(closingDate) ?: return "Closing date n/a"
        if (deadlineState in CLOSED_STATES || d.time <= Date().time) return "Closed"
        val days = daysUntilClosing(closingAt, closingDate) ?: return "Closing date n/a"
        return when {
            endsToday(closingAt, closingDate) -> "Closes today"
            days <= 6 -> "Closing in $days day" + if (days == 1) "" else "s"
            days <= 14 -> "Closing soon · ${shortDate(d)}"
            else -> "Closes ${prettyDate(d)}"
        }
    }

    /** True when closing within `withinDays` (used for the urgency accent). */
    fun isUrgent(closingAt: String?, closingDate: String?, withinDays: Int = 7): Boolean {
        val u = urgency(closingAt, closingDate)
        return u == Urgency.URGENT || u == Urgency.TODAY
    }

    fun prettyDate(closingAt: String?, closingDate: String?): String {
        val d = parse(closingAt) ?: parseDate(closingDate) ?: return "—"
        return prettyDate(d)
    }

    /**
     * Exact closing stamp combining relative labels' missing half:
     * "2 Sep 2026 · 11:00" when the instant is known, else "2 Sep 2026".
     */
    fun dateTimeLabel(closingAt: String?, closingDate: String?): String {
        val d = parse(closingAt) ?: parseDate(closingDate) ?: return "Date not listed"
        val datePart = SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(d)
        return if (closingAt != null) {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
            "$datePart · $time"
        } else datePart
    }

    /** "11:00" for the deadline card; null when no exact time is published. */
    fun closingTimeLabel(closingAt: String?): String? {
        val d = parse(closingAt) ?: return null
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(d)
    }

    /** Weekday for the deadline card: "Wednesday". */
    fun weekdayLabel(closingAt: String?, closingDate: String?): String? {
        val d = parse(closingAt) ?: parseDate(closingDate) ?: return null
        return SimpleDateFormat("EEEE", Locale.getDefault()).format(d)
    }

    /** The closing instant as epoch milliseconds, or null when unknown. */
    fun toMillis(closingAt: String?, closingDate: String?): Long? =
        parse(closingAt)?.time ?: parseDate(closingDate)?.time

    private fun prettyDate(d: Date): String =
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(d)

    private fun shortDate(d: Date): String =
        SimpleDateFormat("d MMM", Locale.getDefault()).format(d)

    private fun parse(iso: String?): Date? {
        if (iso.isNullOrBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                if (p.endsWith("'Z'")) f.timeZone = TimeZone.getTimeZone("UTC")
                return f.parse(iso)
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseDate(d: String?): Date? {
        if (d.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)
        } catch (_: Exception) { null }
    }
}
