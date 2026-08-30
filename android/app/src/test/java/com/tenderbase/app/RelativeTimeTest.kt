package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR

    @Test
    fun `labels roll over at the documented thresholds`() {
        assertEquals("", RelativeTime.label(1000, 0))
        assertEquals("just now", RelativeTime.label(1000, 2000))
        assertEquals("just now", RelativeTime.label(0, MIN - 1))
        assertEquals("12 min ago", RelativeTime.label(12 * MIN, 0))
        assertEquals("3 h ago", RelativeTime.label(3 * HOUR + 30 * MIN, 0))
        assertEquals("yesterday", RelativeTime.label(DAY + 1, 0))
        assertEquals("4 days ago", RelativeTime.label(4 * DAY, 0))
    }
}
