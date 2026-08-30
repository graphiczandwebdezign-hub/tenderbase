package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Discovery state tests: query params, JSON round-trip and filter accounting.
 * Pure JVM — SearchFilters has no Android dependencies.
 */
class FiltersTest {

    private val today = "2026-08-30"

    @Test
    fun `default filters send only sort`() {
        val params = SearchFilters().toQueryParams(today)
        assertEquals(mapOf("sort" to "newest"), params)
    }

    @Test
    fun `province category and source map to comma lists`() {
        val f = SearchFilters(
            provinces = listOf("KwaZulu-Natal", "Gauteng"),
            categories = listOf("Construction"),
            sources = listOf("eTenders")
        )
        val params = f.toQueryParams(today)
        assertEquals("KwaZulu-Natal,Gauteng", params["province"])
        assertEquals("Construction", params["category"])
        assertEquals("eTenders", params["source"])
    }

    @Test
    fun `status maps to derived lifecycle alias`() {
        val f = SearchFilters(status = StatusFilter.CLOSING_SOON)
        assertEquals("closing_soon", f.toQueryParams(today)["status"])
    }

    @Test
    fun `published windows map to advertised date bounds`() {
        val f = SearchFilters(dateFilter = DateFilter.PUBLISHED_7D)
        val params = f.toQueryParams(today)
        assertEquals("2026-08-25", params["advertised_after"])
        assertEquals(today, params["advertised_before"])
        assertNull(params["closing_within"])
    }

    @Test
    fun `today window is inclusive`() {
        val f = SearchFilters(dateFilter = DateFilter.PUBLISHED_TODAY)
        val params = f.toQueryParams(today)
        assertEquals(today, params["advertised_after"])
        assertEquals(today, params["advertised_before"])
    }

    @Test
    fun `closing windows map to closing_within`() {
        assertEquals("7d", SearchFilters(dateFilter = DateFilter.CLOSING_7D).toQueryParams(today)["closing_within"])
        assertEquals("14d", SearchFilters(dateFilter = DateFilter.CLOSING_14D).toQueryParams(today)["closing_within"])
        assertEquals("30d", SearchFilters(dateFilter = DateFilter.CLOSING_30D).toQueryParams(today)["closing_within"])
    }

    @Test
    fun `custom closing range maps to explicit bounds`() {
        val f = SearchFilters(
            dateFilter = DateFilter.CLOSING_CUSTOM,
            closingAfter = "2026-09-01",
            closingBefore = "2026-09-15"
        )
        val params = f.toQueryParams(today)
        assertEquals("2026-09-01", params["closing_after"])
        assertEquals("2026-09-15", params["closing_before"])
    }

    @Test
    fun `relevance without a query falls back to newest honestly`() {
        val f = SearchFilters(sort = SortOption.RELEVANCE, query = "")
        assertEquals("newest", f.toQueryParams(today)["sort"])
        val withQuery = f.copy(query = "construction")
        assertEquals("relevance", withQuery.toQueryParams(today)["sort"])
    }

    @Test
    fun `json round trip preserves all fields`() {
        val f = SearchFilters(
            query = "clinic",
            provinces = listOf("Gauteng"),
            categories = listOf("Construction", "Medical"),
            status = StatusFilter.OPEN,
            dateFilter = DateFilter.CLOSING_CUSTOM,
            closingAfter = "2026-09-01",
            closingBefore = "2026-09-30",
            sources = listOf("eTenders"),
            sort = SortOption.UPDATED
        )
        assertEquals(f, SearchFilters.fromJson(f.toJson()))
    }

    @Test
    fun `json round trip with defaults`() {
        assertEquals(SearchFilters(), SearchFilters.fromJson(SearchFilters().toJson()))
    }

    @Test
    fun `corrupt json falls back to defaults`() {
        assertEquals(SearchFilters(), SearchFilters.fromJson("not json {"))
        assertEquals(SearchFilters(), SearchFilters.fromJson(null))
    }

    @Test
    fun `active filter count and defaults`() {
        val none = SearchFilters()
        assertTrue(none.isDefault())
        assertEquals(0, none.activeFilterCount())

        val some = SearchFilters(
            query = "x",
            provinces = listOf("Gauteng"),
            categories = listOf("Medical"),
            sources = listOf("eTenders"),
            status = StatusFilter.OPEN,
            dateFilter = DateFilter.CLOSING_7D
        )
        assertFalse(some.isDefault())
        assertEquals(5, some.activeFilterCount())
        // Query is not a "filter" for the badge.
    }

    @Test
    fun `minusDays crosses month boundaries`() {
        assertEquals("2026-07-31", SearchFilters.minusDays("2026-08-01", 1))
        assertEquals("2025-12-31", SearchFilters.minusDays("2026-01-01", 1))
        assertEquals("2026-08-01", SearchFilters.minusDays("2026-08-30", 29))
    }

    @Test
    fun `organisation and document filters reach the API query`() {
        val f = SearchFilters(organisation = "  City of Tshwane  ", docs = DocumentFilter.HAS_DOCS)
        val q = f.toQueryParams("2026-08-30")
        assertEquals("City of Tshwane", q["organisation"])
        assertEquals("true", q["has_documents"])
        assertFalse(q.containsKey("document_type"))

        val notice = SearchFilters(docs = DocumentFilter.HAS_NOTICE).toQueryParams("2026-08-30")
        assertEquals("true", notice["has_documents"])
        assertEquals("notice", notice["document_type"])

        val spec = SearchFilters(docs = DocumentFilter.HAS_SPEC).toQueryParams("2026-08-30")
        assertEquals("specification", spec["document_type"])

        // ANY adds nothing.
        val any = SearchFilters().toQueryParams("2026-08-30")
        assertFalse(any.containsKey("has_documents"))
        assertFalse(any.containsKey("organisation"))
    }

    @Test
    fun `document filter counts as an active filter chip source`() {
        assertTrue(SearchFilters(docs = DocumentFilter.HAS_DOCS).hasActiveFilters())
        assertFalse(SearchFilters(docs = DocumentFilter.ANY).hasActiveFilters())
        assertEquals(1, SearchFilters(organisation = "NHLS").activeFilterCount())
    }

    @Test
    fun `saved search payload round-trips the new filters`() {
        val f = SearchFilters(
            query = "security",
            organisation = "SAPS",
            docs = DocumentFilter.HAS_NOTICE,
            status = StatusFilter.CLOSING_SOON,
        )
        val back = SearchFilters.fromSavedSearchPayload(f.toSavedSearchPayload())
        assertEquals(f.query, back.query)
        assertEquals(f.organisation, back.organisation)
        assertEquals(f.docs, back.docs)
        assertEquals(f.status, back.status)
    }
}

