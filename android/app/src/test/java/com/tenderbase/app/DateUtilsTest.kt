package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Closing-date presentation: urgency tiers and labels from UTC instants. */
class DateUtilsTest {

    companion object {
        @JvmStatic
        @org.junit.BeforeClass
        fun setUtc() {
            // Labels compare calendar days: pin the JVM to UTC so the
            // assertions hold on any machine/CI timezone.
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }
    }

    private fun isoFromNow(days: Int = 0, hours: Int = 0): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_YEAR, days)
        cal.add(Calendar.HOUR_OF_DAY, hours)
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(cal.time)
    }

    /** The same wall-clock day as today, as late as possible. */
    private fun isoLaterToday(): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 30)
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(cal.time)
    }

    @Test
    fun `days until closing is rounded up`() {
        assertEquals(3, DateUtils.daysUntilClosing(isoFromNow(days = 2, hours = 5), null))
        assertEquals(1, DateUtils.daysUntilClosing(isoFromNow(hours = 2), null))
        assertEquals(-2, DateUtils.daysUntilClosing(isoFromNow(days = -2), null))
        assertNull(DateUtils.daysUntilClosing(null, null))
    }

    @Test
    fun `urgency tiers follow the discovery spec`() {
        assertEquals(DateUtils.Urgency.CLOSED, DateUtils.urgency(isoFromNow(days = -1), null))
        assertEquals(DateUtils.Urgency.TODAY, DateUtils.urgency(isoLaterToday(), null))
        assertEquals(DateUtils.Urgency.URGENT, DateUtils.urgency(isoFromNow(days = 4), null))
        assertEquals(DateUtils.Urgency.SOON, DateUtils.urgency(isoFromNow(days = 10), null))
        assertEquals(DateUtils.Urgency.NORMAL, DateUtils.urgency(isoFromNow(days = 30), null))
    }

    @Test
    fun `server deadline state wins over device date math`() {
        assertEquals(
            DateUtils.Urgency.CLOSED,
            DateUtils.urgency(isoFromNow(days = 30), null, deadlineState = "CLOSED")
        )
        assertEquals(
            DateUtils.Urgency.CLOSED,
            DateUtils.urgency(isoFromNow(days = 30), null, deadlineState = "EXPIRED")
        )
    }

    @Test
    fun `labels for each urgency tier`() {
        assertEquals("Closed", DateUtils.closesLabel(isoFromNow(days = -2), null))
        assertEquals(
            "Closed",
            DateUtils.closesLabel(isoFromNow(days = 30), null, deadlineState = "CLOSED")
        )
        assertEquals(
            "Cancelled",
            DateUtils.closesLabel(isoFromNow(days = 5), null, deadlineState = "CANCELLED")
        )
        assertEquals("Closing in 3 days", DateUtils.closesLabel(isoFromNow(days = 2, hours = 20), null))
        val soon = DateUtils.closesLabel(isoFromNow(days = 10), null)
        assertTrue(soon.startsWith("Closing soon · "))
        val far = DateUtils.closesLabel(isoFromNow(days = 40), null)
        assertTrue(far.startsWith("Closes "))
        assertFalse(far.contains("Closing in"))
    }

    @Test
    fun `closing_date fallback works without an instant`() {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.add(Calendar.DAY_OF_YEAR, 3)
        val onlyDate = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(cal.time)
        assertEquals("Closing in 3 days", DateUtils.closesLabel(null, onlyDate))
    }

    @Test
    fun `missing dates produce a clear label`() {
        assertEquals("Closing date n/a", DateUtils.closesLabel(null, null))
    }

    @Test
    fun `isUrgent only for short windows`() {
        assertTrue(DateUtils.isUrgent(isoFromNow(days = 2), null))
        assertFalse(DateUtils.isUrgent(isoFromNow(days = 2), null, deadlineState = "CLOSED"))
        assertFalse(DateUtils.isUrgent(isoFromNow(days = 30), null))
    }
}
