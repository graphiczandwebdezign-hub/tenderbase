package com.tenderbase.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tender JSON mapping: new discovery fields, tolerant parsing, badges. */
class TenderParsingTest {

    private fun fullTenderJson(): JSONObject = JSONObject(
        """
        {
          "id": 42,
          "source": "eTenders",
          "tender_number": "KZN-2026-00124",
          "title": "Construction of a community clinic",
          "description": "Building works",
          "organisation": "KwaZulu-Natal Department of Transport",
          "province": "KwaZulu-Natal",
          "municipality": "eThekwini",
          "category": "Construction",
          "categories": ["construction"],
          "tender_type": "Open Tender",
          "status": "ACTIVE",
          "deadline_state": "CLOSING_SOON",
          "closing_date": "2026-09-04",
          "closing_at": "2026-09-04T11:00:00Z",
          "submission_method": "electronicSubmission",
          "source_url": "https://example.gov.za/42",
          "is_sample": false,
          "documents": [
            {"id": 1, "title": "Notice", "url": "https://example.gov.za/42.pdf", "type": "tenderNotice"}
          ]
        }
        """.trimIndent()
    )

    @Test
    fun `parses all discovery fields`() {
        val t = Tender.fromJson(fullTenderJson())
        assertEquals(42, t.id)
        assertEquals("KZN-2026-00124", t.reference)
        assertEquals("eTenders", t.source)
        assertEquals("eThekwini", t.municipality)
        assertEquals("CLOSING_SOON", t.deadlineState)
        assertEquals("KwaZulu-Natal Department of Transport", t.organisation)
        assertEquals(listOf("construction"), t.categories)
        assertEquals(1, t.documents.size)
    }

    @Test
    fun `missing optional fields default to null`() {
        val t = Tender.fromJson(JSONObject("""{"id": 7, "title": "Minimal"}"""))
        assertEquals(7, t.id)
        assertEquals("Minimal", t.title)
        assertNull(t.reference)
        assertNull(t.source)
        assertNull(t.deadlineState)
        assertNull(t.municipality)
        assertEquals(0, t.documents.size)
    }

    @Test
    fun `badge label derives from server deadline state`() {
        fun tender(state: String?) = Tender.fromJson(
            fullTenderJson().putOpt("deadline_state", state)
        )
        assertEquals("OPEN", tender("ACTIVE").badgeLabel())
        assertEquals("CLOSING SOON", tender("CLOSING_SOON").badgeLabel())
        assertEquals("CLOSED", tender("CLOSED").badgeLabel())
        assertEquals("CLOSED", tender("EXPIRED").badgeLabel())
        assertEquals("CANCELLED", tender("CANCELLED").badgeLabel())
    }

    @Test
    fun `badge label falls back to dates for cached rows`() {
        val base = fullTenderJson().put("deadline_state", JSONObject.NULL)
        val past = base.put("closing_at", "2020-01-01T00:00:00Z")
        assertEquals("CLOSED", Tender.fromJson(past).badgeLabel())
        val future = base.put("closing_at", "2099-01-01T00:00:00Z")
        assertEquals("OPEN", Tender.fromJson(future).badgeLabel())
    }
}
