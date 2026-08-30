package com.tenderbase.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved-search payload mapping: the client must send exactly the params the
 * discovery endpoint accepts (no sort) and rebuild identical filters from the
 * server's echo.
 */
class SavedSearchPayloadTest {

    private fun roundTrip(f: SearchFilters): SearchFilters =
        SearchFilters.fromSavedSearchPayload(f.toSavedSearchPayload())

    @Test
    fun `payload contains canonical filter keys and never sort`() {
        val f = SearchFilters(
            query = "construction",
            provinces = listOf("KwaZulu-Natal"),
            categories = listOf("Medical"),
            status = StatusFilter.OPEN,
            dateFilter = DateFilter.CLOSING_7D
        )
        val payload = f.toSavedSearchPayload()
        assertEquals("construction", payload.optString("search"))
        assertEquals("KwaZulu-Natal", payload.optString("province"))
        assertEquals("Medical", payload.optString("category"))
        assertEquals("open", payload.optString("status"))
        assertEquals("7d", payload.optString("closing_within"))
        assertEquals("closing_7d", payload.optString("date_key"))
        assertFalse(payload.has("sort"))
    }

    @Test
    fun `closing window round trip`() {
        assertEquals(
            DateFilter.CLOSING_7D,
            roundTrip(SearchFilters(dateFilter = DateFilter.CLOSING_7D)).dateFilter
        )
        assertEquals(
            DateFilter.CLOSING_14D,
            roundTrip(SearchFilters(dateFilter = DateFilter.CLOSING_14D)).dateFilter
        )
        assertEquals(
            DateFilter.CLOSING_30D,
            roundTrip(SearchFilters(dateFilter = DateFilter.CLOSING_30D)).dateFilter
        )
    }

    @Test
    fun `published window round trip via date_key`() {
        // Absolute bounds are relative to save-day; date_key keeps the intent.
        val out = roundTrip(SearchFilters(dateFilter = DateFilter.PUBLISHED_7D))
        assertEquals(DateFilter.PUBLISHED_7D, out.dateFilter)
        val today = SearchFilters.todayIso()
        assertEquals(SearchFilters.minusDays(today, 6), out.toQueryParams(today)["advertised_after"])
    }

    @Test
    fun `custom closing range round trip`() {
        val f = SearchFilters(
            dateFilter = DateFilter.CLOSING_CUSTOM,
            closingAfter = "2026-09-01",
            closingBefore = "2026-09-15"
        )
        val out = roundTrip(f)
        assertEquals(DateFilter.CLOSING_CUSTOM, out.dateFilter)
        assertEquals("2026-09-01", out.closingAfter)
        assertEquals("2026-09-15", out.closingBefore)
    }

    @Test
    fun `full filter set round trip`() {
        val f = SearchFilters(
            query = "clinic",
            provinces = listOf("Gauteng", "Limpopo"),
            categories = listOf("Construction"),
            sources = listOf("eTenders"),
            status = StatusFilter.CLOSING_SOON
        )
        val out = roundTrip(f)
        assertEquals(f.query, out.query)
        assertEquals(f.provinces, out.provinces)
        assertEquals(f.categories, out.categories)
        assertEquals(f.sources, out.sources)
        assertEquals(f.status, out.status)
    }

    @Test
    fun `server echo without date_key is still understood`() {
        // The server stores only real filter params; date_key is optional.
        val payload = JSONObject(
            """{"search":"road","province":"KwaZulu-Natal","closing_within":"14d"}"""
        )
        val out = SearchFilters.fromSavedSearchPayload(payload)
        assertEquals("road", out.query)
        assertEquals(listOf("KwaZulu-Natal"), out.provinces)
        assertEquals(DateFilter.CLOSING_14D, out.dateFilter)
    }

    @Test
    fun `closing week preset is open tenders closing within 7 days`() {
        val preset = SearchFilters.closingWeekPreset()
        assertEquals(DateFilter.CLOSING_7D, preset.dateFilter)
        assertEquals(SortOption.CLOSING, preset.sort)
        assertEquals("7d", preset.toQueryParams(SearchFilters.todayIso())["closing_within"])
        assertTrue(preset.hasActiveFilters())
    }

    @Test
    fun `summary describes the active filters`() {
        val f = SearchFilters(
            query = "construction",
            provinces = listOf("KwaZulu-Natal"),
            dateFilter = DateFilter.CLOSING_7D
        )
        val s = f.summary()
        assertTrue(s.contains("construction"))
        assertTrue(s.contains("KwaZulu-Natal"))
        assertTrue(s.contains("7d"))
        assertTrue(SearchFilters().summary().isEmpty())
    }

    @Test
    fun `empty payload yields default filters`() {
        val out = SearchFilters.fromSavedSearchPayload(JSONObject())
        assertEquals(SearchFilters(dateFilter = DateFilter.ANY), out.copy(dateFilter = DateFilter.ANY))
        assertNull(out.status)
    }
}
