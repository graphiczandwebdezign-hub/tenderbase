package com.tenderbase.app

import java.util.Calendar
import java.util.Locale

/**
 * Notification centre grouping: Today / Yesterday / Earlier by local calendar
 * day, plus a lightweight classification of what the alert is about. The
 * history rows carry free-form titles, so the kind is derived from the words
 * the backend already uses ("matching", "closing", "updated", "document"…).
 */
enum class NotificationBucket { TODAY, YESTERDAY, EARLIER }

/** Alert kinds shown with distinct icon + label (colour never alone). */
enum class NotificationKind { NEW_MATCH, DEADLINE, DEADLINE_CHANGED, SAVED_UPDATED, DOCUMENT, GENERAL }

object NotificationGroups {

    /** Local calendar day-start (millis) for the day containing [ts]. */
    fun dayStart(ts: Long): Long {
        val cal = Calendar.getInstance(Locale.getDefault())
        cal.timeInMillis = ts
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun bucketOf(ts: Long, now: Long = System.currentTimeMillis()): NotificationBucket {
        val dayStart = dayStart(ts)
        val todayStart = dayStart(now)
        return when {
            dayStart >= todayStart -> NotificationBucket.TODAY
            dayStart >= todayStart - DAY_MS -> NotificationBucket.YESTERDAY
            else -> NotificationBucket.EARLIER
        }
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Group preserving the input order (already sorted DESC by timestamp). */
    fun <T> groupBy(
        items: List<T>,
        timestamp: (T) -> Long,
        now: Long = System.currentTimeMillis()
    ): Map<NotificationBucket, List<T>> =
        items.groupBy { bucketOf(timestamp(it), now) }
            .toSortedMap(compareBy { it.ordinal })

    /** Derive the alert kind from the stored title/body text. */
    fun kindOf(title: String, body: String): NotificationKind {
        val t = title.lowercase(Locale.US)
        val b = body.lowercase(Locale.US)
        return when {
            t.contains("closing") || t.contains("deadline") || t.contains("due soon") ->
                if (t.contains("chang")) NotificationKind.DEADLINE_CHANGED else NotificationKind.DEADLINE
            t.contains("match") || t.contains("new tender") || t.contains("opportunity") -> NotificationKind.NEW_MATCH
            t.contains("document") -> NotificationKind.DOCUMENT
            t.contains("updated") || b.contains("updated") -> NotificationKind.SAVED_UPDATED
            else -> NotificationKind.GENERAL
        }
    }
}
