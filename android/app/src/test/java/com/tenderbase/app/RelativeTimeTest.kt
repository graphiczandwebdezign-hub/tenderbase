package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR

    @Test
    fun `labels roll over at the documented thresholds`() {
        val now = 1_769_900_000_000L
        assertEquals("", RelativeTime.label(now, 0))
        assertEquals("just now", RelativeTime.label(now, now + MIN))
        assertEquals("just now", RelativeTime.label(now, now - (MIN - 1)))
        assertEquals("12 min ago", RelativeTime.label(now, now - 12 * MIN))
        assertEquals("3 h ago", RelativeTime.label(now, now - (3 * HOUR + 30 * MIN)))
        assertEquals("yesterday", RelativeTime.label(now, now - (DAY + 1)))
        assertEquals("4 days ago", RelativeTime.label(now, now - 4 * DAY))
    }
}
