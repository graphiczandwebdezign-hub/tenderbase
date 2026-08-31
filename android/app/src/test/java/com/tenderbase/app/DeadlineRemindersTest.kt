package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Local deadline reminders: which saved tenders are due right now. */
class DeadlineRemindersTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUtc() {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }
    }

    private fun tender(
        id: Int,
        hoursOut: Long?,
        deadlineState: String? = "ACTIVE"
    ) = Tender(
        id = id,
        title = "T$id",
        description = null,
        organisation = null,
        province = null,
        municipality = null,
        category = null,
        categories = emptyList(),
        tenderType = null,
        reference = null,
        status = "ACTIVE",
        deadlineState = deadlineState,
        source = null,
        closingDate = null,
        closingAt = hoursOut?.let {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.add(Calendar.MILLISECOND, (it * 3_600_000L).toInt())
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(cal.time)
        },
        sourceUrl = null,
        documents = emptyList()
    )

    @Test
    fun `due within the window and not already reminded`() {
        val saved = listOf(
            tender(1, hoursOut = 24),           // due
            tender(2, hoursOut = 5),            // due (very soon)
            tender(3, hoursOut = 72),           // outside 48h
            tender(4, hoursOut = -1),           // already closed
            tender(5, hoursOut = null),         // no deadline
            tender(6, hoursOut = 30, deadlineState = "CLOSED") // server says closed
        )
        val due = DeadlineReminders.due(saved, alreadyRemindedIds = emptySet())
        assertEquals(listOf(1, 2), due.map { it.id })
    }

    @Test
    fun `already reminded ids are excluded`() {
        val saved = listOf(tender(1, hoursOut = 24), tender(2, hoursOut = 24))
        val due = DeadlineReminders.due(saved, alreadyRemindedIds = setOf(1))
        assertEquals(listOf(2), due.map { it.id })
    }

    @Test
    fun `custom window is respected`() {
        val saved = listOf(tender(1, hoursOut = 70))
        assertTrue(DeadlineReminders.due(saved, emptySet(), withinHours = 48).isEmpty())
        assertEquals(listOf(1), DeadlineReminders.due(saved, emptySet(), withinHours = 72).map { it.id })
    }

    @Test
    fun `empty saved list yields nothing`() {
        assertTrue(DeadlineReminders.due(emptyList(), emptySet()).isEmpty())
    }
}
