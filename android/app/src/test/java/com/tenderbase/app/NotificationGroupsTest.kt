package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class NotificationGroupsTest {

    private val DAY_MS = 24L * 60 * 60 * 1000

    @Test
    fun `buckets split at local midnight not rolling 24h`() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.AUGUST, 30, 0, 30, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val justAfterMidnight = cal.timeInMillis
        // 36h before 00:30 is the *previous day's* 12:30 — two days back on
        // the rolling clock, but the day before on the calendar.
        assertEquals(
            NotificationBucket.YESTERDAY,
            NotificationGroups.bucketOf(justAfterMidnight - 36L * 60 * 60 * 1000, justAfterMidnight)
        )
    }

    @Test
    fun `today yesterday earlier boundaries`() {
        val now = System.currentTimeMillis()
        val todayStart = NotificationGroups.dayStart(now)
        assertEquals(NotificationBucket.TODAY, NotificationGroups.bucketOf(now, now))
        assertEquals(NotificationBucket.TODAY, NotificationGroups.bucketOf(todayStart, now))
        assertEquals(NotificationBucket.YESTERDAY, NotificationGroups.bucketOf(todayStart - 1, now))
        assertEquals(NotificationBucket.EARLIER, NotificationGroups.bucketOf(todayStart - DAY_MS - 1, now))
    }

    @Test
    fun `groupBy is ordered today to earlier and keeps insertion order inside a bucket`() {
        val now = System.currentTimeMillis()
        val todayStart = NotificationGroups.dayStart(now)
        val items = listOf(
            "a" to now,
            "b" to (todayStart - DAY_MS),
            "c" to (todayStart - DAY_MS - 1),
            "d" to todayStart,
        )
        val grouped = NotificationGroups.groupBy(items, { it.second })
        assertEquals(
            listOf(NotificationBucket.TODAY, NotificationBucket.YESTERDAY, NotificationBucket.EARLIER),
            grouped.keys.toList()
        )
        assertEquals(listOf("a", "d"), grouped[NotificationBucket.TODAY]!!.map { it.first })
    }

    @Test
    fun `kindOf sniffs the message subject`() {
        assertEquals(NotificationKind.DEADLINE, NotificationGroups.kindOf("Deadline approaching", "closes soon"))
        assertEquals(NotificationKind.DEADLINE_CHANGED, NotificationGroups.kindOf("Deadline changed", "moved"))
        assertEquals(NotificationKind.NEW_MATCH, NotificationGroups.kindOf("New tender matches your search", "x"))
        assertEquals(NotificationKind.GENERAL, NotificationGroups.kindOf("Hello", "there"))
    }
}
