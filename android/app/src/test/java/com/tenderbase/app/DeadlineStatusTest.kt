package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tier boundaries from the design spec: SAFE >14, UPCOMING 7–14, SOON 2–6, URGENT <=2, CLOSED <=0. */
class DeadlineStatusTest {

    @Test
    fun `boundaries map to the documented tiers`() {
        assertEquals(DeadlineTier.NONE, DeadlineStatus.tierOf(null, null))
        assertEquals(DeadlineTier.CLOSED, DeadlineStatus.tierOf(0, null))
        assertEquals(DeadlineTier.CLOSED, DeadlineStatus.tierOf(-3, null))
        assertEquals(DeadlineTier.URGENT, DeadlineStatus.tierOf(1, null))
        assertEquals(DeadlineTier.URGENT, DeadlineStatus.tierOf(2, null))
        assertEquals(DeadlineTier.CLOSING_SOON, DeadlineStatus.tierOf(3, null))
        assertEquals(DeadlineTier.CLOSING_SOON, DeadlineStatus.tierOf(6, null))
        assertEquals(DeadlineTier.UPCOMING, DeadlineStatus.tierOf(7, null))
        assertEquals(DeadlineTier.UPCOMING, DeadlineStatus.tierOf(14, null))
        assertEquals(DeadlineTier.SAFE, DeadlineStatus.tierOf(15, null))
    }

    @Test
    fun `server closure wins over a future date`() {
        assertEquals(DeadlineTier.CLOSED, DeadlineStatus.tierOf(9, "CLOSED"))
        assertEquals(DeadlineTier.CLOSED, DeadlineStatus.tierOf(4, "CANCELLED"))
        assertEquals(DeadlineTier.CLOSED, DeadlineStatus.tierOf(30, "EXPIRED"))
    }

    @Test
    fun `labels carry words so colour is never the only signal`() {
        assertEquals("Closed", DeadlineStatus.label(0, null))
        assertEquals("Cancelled", DeadlineStatus.label(5, "CANCELLED"))
        assertEquals("No deadline listed", DeadlineStatus.label(null, null))
        assertEquals("Closes in 1 day", DeadlineStatus.label(1, null))
        assertEquals("Closes in 9 days", DeadlineStatus.label(9, null))
        assertEquals("Closes in 23 days", DeadlineStatus.label(23, null))
    }

    @Test
    fun `short badges are uppercase and distinct`() {
        assertEquals("2 DAYS LEFT", DeadlineStatus.shortBadge(2, null))
        assertEquals("LAST DAY", DeadlineStatus.shortBadge(1, null))
        assertEquals("CLOSING SOON", DeadlineStatus.shortBadge(4, null))
        assertEquals("NO DATE", DeadlineStatus.shortBadge(null, null))
        assertEquals("CANCELLED", DeadlineStatus.shortBadge(3, "CANCELLED"))
    }
}
