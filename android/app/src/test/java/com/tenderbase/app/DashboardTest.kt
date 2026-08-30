package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Sprint 4 dashboard logic: deadline buckets, grouping order, hidden-tender
 * filtering and the closing-this-week preset. Pure JVM.
 */
class DashboardTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUtc() {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }
    }

    private fun tender(
        id: Int,
        daysOut: Int? = 10,
        deadlineState: String? = "ACTIVE"
    ) = Tender(
        id = id,
        title = "Tender $id",
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
        closingAt = daysOut?.let {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.add(Calendar.DAY_OF_YEAR, it)
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(cal.time)
        },
        sourceUrl = null,
        documents = emptyList()
    )

    @Test
    fun `buckets follow the urgency tiers`() {
        assertEquals(Dashboard.Bucket.CLOSED, Dashboard.bucketOf(tender(1, daysOut = -2)))
        assertEquals(Dashboard.Bucket.CLOSED, Dashboard.bucketOf(tender(2, daysOut = 30, deadlineState = "CLOSED")))
        assertEquals(Dashboard.Bucket.THIS_WEEK, Dashboard.bucketOf(tender(3, daysOut = 3)))
        assertEquals(Dashboard.Bucket.TWO_WEEKS, Dashboard.bucketOf(tender(4, daysOut = 10)))
        assertEquals(Dashboard.Bucket.LATER, Dashboard.bucketOf(tender(5, daysOut = 40)))
        assertEquals(Dashboard.Bucket.NO_DATE, Dashboard.bucketOf(tender(6, daysOut = null)))
    }

    @Test
    fun `grouping is ordered by urgency and soonest first`() {
        val tenders = listOf(
            tender(1, daysOut = 5),
            tender(2, daysOut = 1),
            tender(3, daysOut = -1),
            tender(4, daysOut = 100),
            tender(5, daysOut = 2)
        )
        val groups = Dashboard.groupByDeadline(tenders)
        assertEquals(
            listOf(
                Dashboard.Bucket.CLOSED,
                Dashboard.Bucket.THIS_WEEK,
                Dashboard.Bucket.LATER
            ),
            groups.map { it.first }
        )
        // Soonest first within a bucket.
        assertEquals(listOf(2, 5, 1), groups[1].second.map { it.id })
    }

    @Test
    fun `no-date tenders group last and sort by title`() {
        val tenders = listOf(
            tender(1, daysOut = null).let { it.copy(title = "Zebra") },
            tender(2, daysOut = null).let { it.copy(title = "Alpha") }
        )
        val groups = Dashboard.groupByDeadline(tenders)
        assertEquals(1, groups.size)
        assertEquals(Dashboard.Bucket.NO_DATE, groups[0].first)
        assertEquals(listOf("Alpha", "Zebra"), groups[0].second.map { it.title })
    }

    @Test
    fun `hidden tenders are filtered out`() {
        val tenders = listOf(tender(1), tender(2), tender(3))
        assertEquals(tenders, Dashboard.filterHidden(tenders, emptySet()))
        assertEquals(
            listOf(1, 3),
            Dashboard.filterHidden(tenders, setOf(2)).map { it.id }
        )
        assertEquals(emptyList<Tender>(), Dashboard.filterHidden(tenders, setOf(1, 2, 3)))
    }

    @Test
    fun `closing this week preset is open tenders within 7 days by deadline`() {
        val f = Dashboard.closingThisWeekFilters()
        assertEquals(StatusFilter.OPEN, f.status)
        assertEquals(DateFilter.CLOSING_7D, f.dateFilter)
        assertEquals(SortOption.CLOSING, f.sort)
        val params = f.toQueryParams(SearchFilters.todayIso())
        assertEquals("open", params["status"])
        assertEquals("7d", params["closing_within"])
        assertEquals("closing", params["sort"])
        assertTrue(f.hasActiveFilters())
    }
}
