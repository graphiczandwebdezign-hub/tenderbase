package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * Sprint 5 bid workspace logic: checklist template/progress, document
 * grouping and the shareable bid-pack text. Pure JVM.
 */
class BidPackTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUtc() {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            Locale.setDefault(Locale.US)
        }
    }

    private fun tender(
        documents: List<TenderDoc> = emptyList(),
        sourceUrl: String? = "https://www.etenders.gov.za/42"
    ) = Tender(
        id = 42,
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
        closingDate = "2026-09-04",
        closingAt = "2026-09-04T11:00:00Z",
        sourceUrl = sourceUrl,
        documents = documents
    )

    private fun doc(title: String, type: String? = null) =
        TenderDoc(title = title, url = "https://x/$title.pdf", mime = "application/pdf",
                  fileSize = 1024L, type = type)

    // ------------------------------------------------------------ checklist

    @Test
    fun `default checklist is a sensible bid template`() {
        val template = BidPack.defaultChecklist()
        assertEquals(5, template.size)
        assertTrue(template.none { it.isBlank() })
        // Submitting is the final step.
        assertTrue(template.last().contains("Submit", ignoreCase = true))
    }

    @Test
    fun `progress label and fraction`() {
        assertEquals("0 of 5 complete", BidPack.progressLabel(0, 5))
        assertEquals("2 of 5 complete", BidPack.progressLabel(2, 5))
        assertEquals(0.4f, BidPack.progressFraction(2, 5))
        assertEquals(0f, BidPack.progressFraction(0, 0))
        assertEquals(1f, BidPack.progressFraction(5, 5))
    }

    // ------------------------------------------------------ doc grouping

    @Test
    fun `documents group by type in priority order`() {
        val groups = BidPack.groupDocuments(
            listOf(
                doc("Annexure B", type = "annex"),
                doc("Addendum 1", type = "addendum"),
                doc("Tender notice", type = "tenderNotice"),
                doc("Misc form", type = "submissionForm"),
                doc("Second notice", type = "notice"),
                doc("Untyped doc")
            )
        )
        assertEquals(
            listOf("Tender notices", "Addenda & amendments", "Annexures", "Documents"),
            groups.map { it.title }
        )
        assertEquals(listOf("Tender notice", "Second notice"), groups[0].documents.map { it.title })
        assertEquals(listOf("Addendum 1"), groups[1].documents.map { it.title })
        // Untyped and unknown types share the catch-all group, insertion order kept.
        assertEquals(listOf("Misc form", "Untyped doc"), groups[3].documents.map { it.title })
    }

    @Test
    fun `amendment types land in the addenda group`() {
        val groups = BidPack.groupDocuments(listOf(doc("Changed closing", type = "amendment")))
        assertEquals("Addenda & amendments", groups.single().title)
    }

    @Test
    fun `empty documents produce no groups`() {
        assertTrue(BidPack.groupDocuments(emptyList()).isEmpty())
    }

    // ---------------------------------------------------------- bid pack text

    @Test
    fun `bid pack carries meta, checklist state, notes and documents`() {
        val text = BidPack.build(
            tender(documents = listOf(doc("Tender notice", type = "tenderNotice"))),
            note = "Site visit 2 Sep, RFP contact: Thandi",
            checklist = listOf("Register" to true, "Submit" to false)
        )
        assertTrue(text.startsWith("BID PACK — Construction of a clinic"))
        assertTrue(text.contains("KwaZulu-Natal Department of Health"))
        assertTrue(text.contains("KZN-2026-00124"))
        assertTrue(text.contains("4 Sep 2026"))
        assertTrue(text.contains("Checklist (1 of 2 complete):"))
        assertTrue(text.contains("[x] Register"))
        assertTrue(text.contains("[ ] Submit"))
        assertTrue(text.contains("Site visit 2 Sep"))
        assertTrue(text.contains("Tender notices:"))
        assertTrue(text.contains("- Tender notice (1.0 KB)"))
        assertTrue(text.contains("tenderbase://tender/42"))
        assertTrue(text.contains("https://www.etenders.gov.za/42"))
    }

    @Test
    fun `bid pack omits empty sections`() {
        val text = BidPack.build(tender(), note = null, checklist = emptyList())
        assertFalse(text.contains("Checklist"))
        assertFalse(text.contains("Notes:"))
        assertFalse(text.contains("Documents:"))
    }

    @Test
    fun `blank note is treated as no note`() {
        val text = BidPack.build(tender(), note = "   ", checklist = emptyList())
        assertFalse(text.contains("Notes:"))
    }
}
