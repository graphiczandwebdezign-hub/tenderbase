package com.tenderbase.app

/**
 * Bid workspace logic: checklist templates, progress, document grouping and
 * the shareable bid-pack text. Pure Kotlin — unit-testable on the JVM.
 */
object BidPack {

    /** Default checklist seeded for a tender's workspace (in order). */
    fun defaultChecklist(): List<String> = listOf(
        "Register as a supplier / CIDB",
        "Attend the briefing (if required)",
        "Collect and read all documents",
        "Prepare pricing and supporting documents",
        "Submit the bid before the closing time"
    )

    /** "2 of 5 complete" style progress, safe for empty checklists. */
    fun progressLabel(done: Int, total: Int): String = "$done of $total complete"

    /** Completion fraction 0..1 (0 when empty). */
    fun progressFraction(done: Int, total: Int): Float =
        if (total <= 0) 0f else done.toFloat() / total.toFloat()

    // ------------------------------------------------------ document grouping

    /** Display groups for tender documents, in a stable, useful order. */
    data class DocumentGroup(val title: String, val documents: List<TenderDoc>)

    /**
     * Group documents by their source type: notices first, then addenda
     * (deadline-critical), then annexures, then everything else. Untyped
     * documents land in "Documents".
     */
    fun groupDocuments(docs: List<TenderDoc>): List<DocumentGroup> {
        val rank = mapOf(
            "Tender notices" to 0,
            "Addenda & amendments" to 1,
            "Annexures" to 2,
            "Documents" to 3,
        )

        fun titleFor(type: String?): String = when {
            type == null -> "Documents"
            type.contains("notice", ignoreCase = true) -> "Tender notices"
            type.contains("amend", ignoreCase = true) ||
                type.contains("addend", ignoreCase = true) -> "Addenda & amendments"
            type.contains("annex", ignoreCase = true) -> "Annexures"
            else -> "Documents"
        }

        val byTitle = LinkedHashMap<String, MutableList<TenderDoc>>()
        for (doc in docs) {
            byTitle.getOrPut(titleFor(doc.type)) { mutableListOf() }.add(doc)
        }
        return byTitle.entries
            .sortedBy { rank[it.key] ?: 3 }
            .map { DocumentGroup(it.key, it.value) }
    }

    // ------------------------------------------------------------- export text

    /**
     * Plain-text bid pack: everything needed to hand the opportunity to a
     * colleague — meta, deadline, checklist state, notes and documents.
     */
    fun build(
        tender: Tender,
        note: String?,
        checklist: List<Pair<String, Boolean>>
    ): String = buildString {
        appendLine("BID PACK — ${tender.title}")
        appendLine()
        tender.organisation?.let { appendLine("Organisation: $it") }
        tender.reference?.let { appendLine("Reference: $it") }
        appendLine("Closing: ${DateUtils.prettyDate(tender.closingAt, tender.closingDate)}")
        tender.province?.let { appendLine("Province: $it") }
        tender.category?.let { appendLine("Category: $it") }
        appendLine("Source: ${tender.source ?: "—"}")

        if (checklist.isNotEmpty()) {
            appendLine()
            appendLine("Checklist (${progressLabel(checklist.count { it.second }, checklist.size)}):")
            for ((label, done) in checklist) {
                appendLine("  ${if (done) "[x]" else "[ ]"} $label")
            }
        }

        if (!note.isNullOrBlank()) {
            appendLine()
            appendLine("Notes:")
            appendLine(note.trim())
        }

        if (tender.documents.isNotEmpty()) {
            appendLine()
            appendLine("Documents:")
            for (g in groupDocuments(tender.documents)) {
                appendLine("  ${g.title}:")
                for (d in g.documents) {
                    appendLine("   - ${d.title}${d.fileSize?.let { " (${formatSize(it)})" } ?: ""}")
                }
            }
        }

        appendLine()
        appendLine("View in TenderBase: ${TenderActions.deepLinkFor(tender.id)}")
        tender.sourceUrl?.let { append("Source: $it") }
    }

    private fun formatSize(bytes: Long): String = TenderActions.formatFileSize(bytes) ?: "?"
}
