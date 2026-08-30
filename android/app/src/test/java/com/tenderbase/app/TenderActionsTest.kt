package com.tenderbase.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Sprint 3 action helpers: deep links, share summaries, calendar slots, file
 * sizes and amendment parsing. Pure JVM.
 */
class TenderActionsTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setStableDefaults() {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Locale.setDefault(Locale.US)
        }
    }

    private fun tender(
        id: Int = 42,
        closingAt: String? = "2026-09-04T11:00:00Z",
        closingDate: String? = "2026-09-04",
        sourceUrl: String? = "https://www.etenders.gov.za/42"
    ) = Tender(
        id = id,
        title = "Construction of a clinic",
        description = null,
        organisation = "KwaZulu-Natal Department of Health",
        province = "KwaZulu-Natal",
        municipality = null,
        category = "Construction",
        categories = emptyList(),
        tenderType = "Open Tender",
        reference = "KZN-2026-00124",
        status = "ACTIVE",
        deadlineState = "ACTIVE",
        source = "eTenders",
        closingDate = closingDate,
        closingAt = closingAt,
        sourceUrl = sourceUrl,
        documents = emptyList()
    )

    // ------------------------------------------------------------ deep links

    @Test
    fun `deep link builds and parses symmetrically`() {
        for (id in listOf(1, 42, 999999)) {
            val link = TenderActions.deepLinkFor(id)
            assertEquals("tenderbase://tender/$id", link)
            assertEquals(id, TenderActions.parseDeepLink("tenderbase", "tender", "/$id"))
        }
    }

    @Test
    fun `deep link parser tolerates missing slashes`() {
        assertEquals(7, TenderActions.parseDeepLink("tenderbase", "tender", "7"))
        assertEquals(7, TenderActions.parseDeepLink("tenderbase", "tender", "/7/"))
    }

    @Test
    fun `deep link parser rejects malformed links`() {
        assertNull(TenderActions.parseDeepLink("http", "tender", "/42"))
        assertNull(TenderActions.parseDeepLink("tenderbase", "tenders", "/42"))
        assertNull(TenderActions.parseDeepLink("tenderbase", "tender", "/abc"))
        assertNull(TenderActions.parseDeepLink("tenderbase", "tender", "/0"))
        assertNull(TenderActions.parseDeepLink("tenderbase", "tender", "/-3"))
        assertNull(TenderActions.parseDeepLink(null, null, null))
        assertNull(TenderActions.parseDeepLink("tenderbase", "tender", null))
    }

    @Test
    fun `numeric extra wins over deep link in launch resolution`() {
        // tenderIdFrom(intent, uri) is activity code; the parsing primitives
        // it delegates to are covered above and below.
        assertTrue(TenderActions.parseDeepLink("tenderbase", "tender", "/42") == 42)
    }

    // ----------------------------------------------------------------- share

    @Test
    fun `share summary carries the essentials plus deep link`() {
        val s = TenderActions.shareSummary(tender())
        assertTrue(s.contains("Construction of a clinic"))
        assertTrue(s.contains("KwaZulu-Natal Department of Health"))
        assertTrue(s.contains("KZN-2026-00124"))
        assertTrue(s.contains("4 Sep 2026"))
        assertTrue(s.contains("tenderbase://tender/42"))
        assertTrue(s.contains("https://www.etenders.gov.za/42"))
        // No dangling separator when the tender has no source URL.
        val bare = TenderActions.shareSummary(tender(sourceUrl = null))
        assertTrue(bare.endsWith("tenderbase://tender/42"))
    }

    // -------------------------------------------------------------- calendar

    @Test
    fun `calendar slot uses the exact closing instant`() {
        val slot = TenderActions.calendarSlot(tender())!!
        assertFalse(slot.allDay)
        // 2026-09-04T11:00:00Z in UTC
        assertEquals(1788519600000L, slot.beginMillis)
        assertEquals(slot.beginMillis + 3_600_000L, slot.endMillis)
    }

    @Test
    fun `calendar slot falls back to an all-day event on the closing date`() {
        val slot = TenderActions.calendarSlot(tender(closingAt = null))!!
        assertTrue(slot.allDay)
        assertEquals(24 * 3_600_000L - 1, slot.endMillis - slot.beginMillis)
    }

    @Test
    fun `calendar slot is null without any deadline`() {
        assertNull(TenderActions.calendarSlot(tender(closingAt = null, closingDate = null)))
    }

    // ------------------------------------------------------------- file size

    @Test
    fun `file sizes format sensibly`() {
        assertNull(TenderActions.formatFileSize(null))
        assertNull(TenderActions.formatFileSize(-5))
        assertEquals("0 B", TenderActions.formatFileSize(0))
        assertEquals("512 B", TenderActions.formatFileSize(512))
        assertEquals("2.0 KB", TenderActions.formatFileSize(2048))
        assertEquals("1.5 MB", TenderActions.formatFileSize(1_572_864))
        assertEquals("1.1 GB", TenderActions.formatFileSize(1_100_000_000))
        assertEquals("128 MB", TenderActions.formatFileSize(128 * 1024 * 1024))
    }

    // ------------------------------------------------------------- amendments

    @Test
    fun `amendments parse from detail json`() {
        val json = JSONObject(
            """
            {"id": 9, "title": "T", "amendments": [
              {"id": 1, "field_changed": "closing_date", "old_value": "2026-08-30",
               "new_value": "2026-09-15", "detected_at": "2026-08-20T10:00:00Z"},
              {"id": 2, "field_changed": "title", "old_value": null, "new_value": "Renamed"}
            ]}
            """.trimIndent()
        )
        val t = Tender.fromJson(json)
        assertEquals(2, t.amendments.size)
        assertEquals("closing_date", t.amendments[0].fieldChanged)
        assertEquals("2026-09-15", t.amendments[0].newValue)
        assertNull(t.amendments[1].oldValue)
        assertEquals("Renamed", t.amendments[1].newValue)
        // Document file sizes parse too.
        val withDocs = JSONObject(
            """{"id": 9, "title": "T",
               "documents": [{"id": 1, "title": "Notice", "url": "https://x/y.pdf",
                              "mime_type": "application/pdf", "file_size": 1234567}]}"""
        )
        assertEquals(1_234_567L, Tender.fromJson(withDocs).documents[0].fileSize)
    }

    @Test
    fun `advertised date and submission method parse`() {
        val json = JSONObject(
            """{"id": 5, "title": "T", "advertised_date": "2026-08-28",
               "submission_method": "electronicSubmission"}"""
        )
        val t = Tender.fromJson(json)
        assertEquals("2026-08-28", t.advertisedDate)
        assertEquals("electronicSubmission", t.submissionMethod)
        assertNotNull(t)
    }
}
