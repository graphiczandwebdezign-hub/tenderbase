package com.tenderbase.app

/**
 * Compact relative-time labels ("Updated 12 min ago"). Pure so the wording
 * boundaries are unit-tested rather than eyeballed.
 */
object RelativeTime {

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 60 * MINUTE_MS
    private const val DAY_MS = 24 * HOUR_MS

    /** "just now", "12 min ago", "3 h ago", "Yesterday", or "" when unknown. */
    fun label(nowMs: Long, thenMs: Long): String {
        if (thenMs <= 0L) return ""
        val diff = nowMs - thenMs
        if (diff < 0L) return "just now"
        return when {
            diff < MINUTE_MS -> "just now"
            diff < HOUR_MS -> "${diff / MINUTE_MS} min ago"
            diff < DAY_MS -> "${diff / HOUR_MS} h ago"
            diff < 2 * DAY_MS -> "yesterday"
            else -> "${diff / DAY_MS} days ago"
        }
    }
}
