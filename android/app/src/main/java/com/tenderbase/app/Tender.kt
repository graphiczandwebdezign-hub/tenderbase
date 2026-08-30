package com.tenderbase.app

import org.json.JSONArray
import org.json.JSONObject

/** A single tender, mapped from the TenderBase JSON API. */
data class TenderDoc(
    val title: String,
    val url: String,
    val mime: String?,
    val fileSize: Long? = null,
    val type: String? = null
)

/** An amendment recorded for a tender (detail endpoint only). */
data class TenderAmendment(
    val fieldChanged: String,
    val oldValue: String?,
    val newValue: String?,
    val detectedAt: String?
)

data class Tender(
    val id: Int,
    val title: String,
    val description: String?,
    val organisation: String?,
    val province: String?,
    val municipality: String? = null,
    val category: String?,
    val categories: List<String>,
    val tenderType: String?,
    val reference: String? = null,
    val status: String?,
    /** Server-computed lifecycle state: ACTIVE|CLOSING_SOON|CLOSED|EXPIRED|CANCELLED. */
    val deadlineState: String? = null,
    val source: String? = null,
    val closingDate: String?,
    val closingAt: String?,
    val sourceUrl: String?,
    val documents: List<TenderDoc>,
    val amendments: List<TenderAmendment> = emptyList(),
    val advertisedDate: String? = null,
    val submissionMethod: String? = null
) {
    /**
     * Badge label from the *server's* deadline state (never the device clock).
     * Falls back to date arithmetic for offline-cached rows without state.
     */
    fun badgeLabel(): String = when (deadlineState) {
        "CLOSING_SOON" -> "CLOSING SOON"
        "ACTIVE", "AMENDED" -> "OPEN"
        "CLOSED", "EXPIRED" -> "CLOSED"
        "CANCELLED" -> "CANCELLED"
        else -> when {
            status.equals("CANCELLED", true) -> "CANCELLED"
            status.equals("CLOSED", true) || status.equals("EXPIRED", true) -> "CLOSED"
            DateUtils.isClosed(closingAt, closingDate) -> "CLOSED"
            else -> "OPEN"
        }
    }

    companion object {
        fun fromJson(o: JSONObject): Tender {
            val cats = mutableListOf<String>()
            o.optJSONArray("categories")?.let { arr ->
                for (i in 0 until arr.length()) cats.add(arr.optString(i))
            }
            val docs = mutableListOf<TenderDoc>()
            o.optJSONArray("documents")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val d = arr.optJSONObject(i) ?: continue
                    val url = d.optString("url", "")
                    if (url.isNotEmpty()) {
                        docs.add(
                            TenderDoc(
                                title = d.optString("title").ifEmpty { "Document" },
                                url = url,
                                mime = d.optString("mime_type", null),
                                fileSize = d.optLong("file_size", -1).takeIf { it >= 0 },
                                type = d.optString("type", null)?.takeIf { it.isNotEmpty() && it != "null" }
                            )
                        )
                    }
                }
            }
            return Tender(
                id = o.optInt("id"),
                title = o.optString("title", "(untitled tender)"),
                description = o.optString("description", null)?.takeIf { it.isNotEmpty() && it != "null" },
                organisation = o.optString("organisation", null)?.takeIf { it.isNotEmpty() && it != "null" },
                province = o.optString("province", null)?.takeIf { it.isNotEmpty() && it != "null" },
                municipality = o.optString("municipality", null)?.takeIf { it.isNotEmpty() && it != "null" },
                category = o.optString("category", null)?.takeIf { it.isNotEmpty() && it != "null" },
                categories = cats,
                tenderType = o.optString("tender_type", null)?.takeIf { it.isNotEmpty() && it != "null" },
                reference = o.optString("tender_number", null)?.takeIf { it.isNotEmpty() && it != "null" },
                status = o.optString("status", null),
                deadlineState = o.optString("deadline_state", null)?.takeIf { it.isNotEmpty() && it != "null" },
                source = o.optString("source", null)?.takeIf { it.isNotEmpty() && it != "null" },
                closingDate = o.optString("closing_date", null)?.takeIf { it.isNotEmpty() && it != "null" },
                closingAt = o.optString("closing_at", null)?.takeIf { it.isNotEmpty() && it != "null" },
                sourceUrl = o.optString("source_url", null)?.takeIf { it.isNotEmpty() && it != "null" },
                documents = docs,
                amendments = amendmentsFrom(o.optJSONArray("amendments")),
                advertisedDate = o.optString("advertised_date", null)?.takeIf { it.isNotEmpty() && it != "null" },
                submissionMethod = o.optString("submission_method", null)?.takeIf { it.isNotEmpty() && it != "null" }
            )
        }

        private fun amendmentsFrom(arr: JSONArray?): List<TenderAmendment> {
            if (arr == null) return emptyList()
            val out = mutableListOf<TenderAmendment>()
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                out.add(
                    TenderAmendment(
                        fieldChanged = a.optString("field_changed"),
                        oldValue = a.optString("old_value", null)?.takeIf { it.isNotEmpty() && it != "null" },
                        newValue = a.optString("new_value", null)?.takeIf { it.isNotEmpty() && it != "null" },
                        detectedAt = a.optString("detected_at", null)?.takeIf { it.isNotEmpty() && it != "null" }
                    )
                )
            }
            return out
        }

        fun listFromArray(arr: JSONArray): List<Tender> {
            val out = ArrayList<Tender>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(fromJson(it)) }
            }
            return out
        }
    }
}
