package com.tenderbase.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Discovery state for the tender list screen: free-text query, facet filters,
 * quick date windows, custom closing range and sort order.
 *
 * Pure Kotlin (no Android imports) so it is unit-testable on the JVM and can
 * be serialized into fragment/saved-instance bundles as a JSON string.
 */
enum class SortOption(val key: String) {
    NEWEST("newest"),
    CLOSING("closing"),
    UPDATED("updated"),
    RELEVANCE("relevance");

    companion object {
        fun fromKey(key: String?): SortOption =
            entries.firstOrNull { it.key == key } ?: NEWEST
    }
}

enum class StatusFilter(val key: String) {
    OPEN("open"),
    CLOSING_SOON("closing_soon"),
    CLOSED("closed");

    companion object {
        fun fromKey(key: String?): StatusFilter? =
            entries.firstOrNull { it.key == key }
    }
}

enum class DateFilter(val key: String) {
    ANY("any"),
    PUBLISHED_TODAY("published_today"),
    PUBLISHED_7D("published_7d"),
    PUBLISHED_30D("published_30d"),
    CLOSING_7D("closing_7d"),
    CLOSING_14D("closing_14d"),
    CLOSING_30D("closing_30d"),
    CLOSING_CUSTOM("closing_custom");

    companion object {
        fun fromKey(key: String?): DateFilter =
            entries.firstOrNull { it.key == key } ?: ANY
    }
}

/**
 * Document-availability refinement (filter sheet + the "With documents"
 * quick chip). Mapped onto the API's `has_documents` / `document_type`
 * params so counts and pagination stay server-accurate; [passes] mirrors the
 * semantics locally for cached/offline rows.
 */
enum class DocumentFilter(val key: String) {
    ANY("any"),
    HAS_DOCS("has_docs"),
    HAS_NOTICE("has_notice"),
    HAS_SPEC("has_spec");

    companion object {
        fun fromKey(key: String?): DocumentFilter =
            entries.firstOrNull { it.key == key } ?: ANY
    }
}

/** True when [doc] looks like a tender notice (type or title word match). */
private fun isNoticeDoc(doc: TenderDoc): Boolean {
    val haystack = listOfNotNull(doc.type, doc.title).joinToString(" ").lowercase()
    return "notice" in haystack || "advertisement" in haystack
}

/** True when [doc] looks like the TOR / specification / scope of work. */
private fun isSpecDoc(doc: TenderDoc): Boolean {
    val haystack = listOfNotNull(doc.type, doc.title).joinToString(" ").lowercase()
    return listOf("spec", "tor", "terms of reference", "scope of work", "brief").any { it in haystack }
}

fun DocumentFilter.passes(t: Tender): Boolean = when (this) {
    DocumentFilter.ANY -> true
    DocumentFilter.HAS_DOCS -> t.documents.isNotEmpty()
    DocumentFilter.HAS_NOTICE -> t.documents.any { isNoticeDoc(it) }
    DocumentFilter.HAS_SPEC -> t.documents.any { isSpecDoc(it) }
}

data class SearchFilters(
    val query: String = "",
    val provinces: List<String> = emptyList(),
    val categories: List<String> = emptyList(),
    val status: StatusFilter? = null,
    val dateFilter: DateFilter = DateFilter.ANY,
    /** Inclusive yyyy-MM-dd bounds for the custom closing-date window. */
    val closingAfter: String? = null,
    val closingBefore: String? = null,
    val sources: List<String> = emptyList(),
    val sort: SortOption = SortOption.NEWEST,
    /** Substring match on procuring organisation (server-side param). */
    val organisation: String? = null,
    /** On-device refinement: which documents a row must offer. */
    val docs: DocumentFilter = DocumentFilter.ANY
) {

    /** Number of active facet filters (query excluded) — drives the badge. */
    fun activeFilterCount(): Int =
        provinces.size + categories.size + sources.size +
            (if (status != null) 1 else 0) +
            (if (dateFilter != DateFilter.ANY) 1 else 0) +
            (if (!organisation.isNullOrBlank()) 1 else 0) +
            (if (docs != DocumentFilter.ANY) 1 else 0)

    fun hasActiveFilters(): Boolean = activeFilterCount() > 0

    fun isDefault(): Boolean = query.isBlank() && !hasActiveFilters()

    /**
     * Map to TenderBase API query parameters (values NOT url-encoded here).
     *
     * @param todayIso today's date as yyyy-MM-dd (device calendar day), used
     * for the relative published/closing windows.
     */
    fun toQueryParams(todayIso: String): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        if (provinces.isNotEmpty()) params["province"] = provinces.joinToString(",")
        if (categories.isNotEmpty()) params["category"] = categories.joinToString(",")
        if (sources.isNotEmpty()) params["source"] = sources.joinToString(",")
        if (organisation != null && organisation.isNotBlank()) {
            params["organisation"] = organisation.trim()
        }

        status?.let { params["status"] = it.key }
        when (dateFilter) {
            DateFilter.PUBLISHED_TODAY -> {
                params["advertised_after"] = todayIso
                params["advertised_before"] = todayIso
            }
            DateFilter.PUBLISHED_7D -> {
                params["advertised_after"] = minusDays(todayIso, 6)
                params["advertised_before"] = todayIso
            }
            DateFilter.PUBLISHED_30D -> {
                params["advertised_after"] = minusDays(todayIso, 29)
                params["advertised_before"] = todayIso
            }
            DateFilter.CLOSING_7D -> params["closing_within"] = "7d"
            DateFilter.CLOSING_14D -> params["closing_within"] = "14d"
            DateFilter.CLOSING_30D -> params["closing_within"] = "30d"
            DateFilter.CLOSING_CUSTOM -> {
                // Only meaningful when at least one bound is chosen.
                closingAfter?.let { params["closing_after"] = it }
                closingBefore?.let { params["closing_before"] = it }
            }
            DateFilter.ANY -> Unit
        }
        when (docs) {
            DocumentFilter.ANY -> Unit
            DocumentFilter.HAS_DOCS -> params["has_documents"] = "true"
            DocumentFilter.HAS_NOTICE -> {
                params["has_documents"] = "true"
                params["document_type"] = "notice"
            }
            DocumentFilter.HAS_SPEC -> {
                params["has_documents"] = "true"
                params["document_type"] = "specification"
            }
        }
        // Relevance without a query is meaningless — the API falls back to
        // newest, so say that honestly instead of pretending to rank.
        val sortKey = if (sort == SortOption.RELEVANCE && query.isBlank()) SortOption.NEWEST.key else sort.key
        params["sort"] = sortKey
        return params
    }

    // ---------------------------------------------------------- serialization

    fun toJson(): String {
        val o = JSONObject()
        o.put("q", query)
        o.put("provinces", JSONArray(provinces))
        o.put("categories", JSONArray(categories))
        status?.let { o.put("status", it.key) }
        o.put("date", dateFilter.key)
        closingAfter?.let { o.put("ca", it) }
        closingBefore?.let { o.put("cb", it) }
        o.put("sources", JSONArray(sources))
        o.put("sort", sort.key)
        if (!organisation.isNullOrBlank()) o.put("org", organisation)
        if (docs != DocumentFilter.ANY) o.put("docs", docs.key)
        return o.toString()
    }

    override fun toString(): String = toJson()

    /**
     * Canonical saved-search payload for the server: the same filter params
     * GET /tenders accepts (no `sort` — alerts don't care about ordering).
     * `date_key` is a client hint for lossless round-tripping; the server
     * ignores it when matching.
     */
    fun toSavedSearchPayload(): JSONObject {
        val params = toQueryParams(todayIso())
        val o = JSONObject()
        for ((k, v) in params) {
            if (k != "sort") o.put(k, v)
        }
        o.put("date_key", dateFilter.key)
        return o
    }

    // -------------------------------------------------------------- summary

    /** Short human summary for chips/lists, e.g. "construction · KZN · < 7d". */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (query.isNotBlank()) parts.add("“$query”")
        parts.addAll(provinces)
        parts.addAll(categories)
        parts.addAll(sources)
        status?.let {
            parts.add(
                when (it) {
                    StatusFilter.OPEN -> "Open"
                    StatusFilter.CLOSING_SOON -> "Closing soon"
                    StatusFilter.CLOSED -> "Closed"
                }
            )
        }
        if (filtersHasOrg()) parts.add("org: $organisation")
        if (docs != DocumentFilter.ANY) parts.add(docsLabel())
        if (dateFilter != DateFilter.ANY) parts.add(dateLabel())
        return parts.joinToString(" · ")
    }

    private fun filtersHasOrg() = organisation != null && organisation.isNotBlank()

    private fun docsLabel(): String = when (docs) {
        DocumentFilter.ANY -> ""
        DocumentFilter.HAS_DOCS -> "Has documents"
        DocumentFilter.HAS_NOTICE -> "Has notice"
        DocumentFilter.HAS_SPEC -> "Has TOR/spec"
    }

    private fun dateLabel(): String = when (dateFilter) {
        DateFilter.ANY -> ""
        DateFilter.PUBLISHED_TODAY -> "Published today"
        DateFilter.PUBLISHED_7D -> "Published 7d"
        DateFilter.PUBLISHED_30D -> "Published 30d"
        DateFilter.CLOSING_7D -> "Closing < 7d"
        DateFilter.CLOSING_14D -> "Closing < 14d"
        DateFilter.CLOSING_30D -> "Closing < 30d"
        DateFilter.CLOSING_CUSTOM -> "Closes ${listOfNotNull(closingAfter, closingBefore).joinToString("–")}"
    }

    companion object {
        fun fromJson(json: String?): SearchFilters {
            if (json.isNullOrBlank()) return SearchFilters()
            return try {
                val o = JSONObject(json)
                SearchFilters(
                    query = o.optString("q", ""),
                    provinces = o.optJSONArray("provinces").toStringList(),
                    categories = o.optJSONArray("categories").toStringList(),
                    status = StatusFilter.fromKey(o.optString("status").ifEmpty { null }),
                    dateFilter = DateFilter.fromKey(o.optString("date").ifEmpty { DateFilter.ANY.key }),
                    closingAfter = o.optString("ca").ifEmpty { null },
                    closingBefore = o.optString("cb").ifEmpty { null },
                    sources = o.optJSONArray("sources").toStringList(),
                    sort = SortOption.fromKey(o.optString("sort").ifEmpty { SortOption.NEWEST.key }),
                    organisation = o.optString("org").ifEmpty { null },
                    docs = DocumentFilter.fromKey(o.optString("docs").ifEmpty { null })
                )
            } catch (_: Exception) {
                SearchFilters()
            }
        }

        /** Rebuild discovery state from a saved-search payload (server echo). */
        fun fromSavedSearchPayload(payload: JSONObject): SearchFilters {
            fun list(key: String): List<String> =
                payload.optString(key).split(",").map { it.trim() }.filter { it.isNotEmpty() }

            val dateKey = payload.optString("date_key")
            var dateFilter = when (payload.optString("closing_within")) {
                "7d" -> DateFilter.CLOSING_7D
                "14d" -> DateFilter.CLOSING_14D
                "30d" -> DateFilter.CLOSING_30D
                else -> null
            } ?: DateFilter.fromKey(dateKey.ifEmpty { DateFilter.ANY.key })
            val closingAfter = payload.optString("closing_after").ifEmpty { null }
            val closingBefore = payload.optString("closing_before").ifEmpty { null }
            if (dateFilter == DateFilter.ANY && (closingAfter != null || closingBefore != null)) {
                dateFilter = DateFilter.CLOSING_CUSTOM
            }
            val docs = when {
                payload.optString("document_type").contains("notice", true) -> DocumentFilter.HAS_NOTICE
                payload.optString("document_type").isNotEmpty() -> DocumentFilter.HAS_SPEC
                payload.optBoolean("has_documents", false) -> DocumentFilter.HAS_DOCS
                else -> DocumentFilter.ANY
            }
            return SearchFilters(
                query = payload.optString("search"),
                provinces = list("province"),
                categories = list("category"),
                sources = list("source"),
                status = StatusFilter.fromKey(payload.optString("status").ifEmpty { null }),
                dateFilter = dateFilter,
                closingAfter = closingAfter,
                closingBefore = closingBefore,
                organisation = payload.optString("organisation").ifEmpty { null },
                docs = docs
            )
        }

        /** Deadline-intelligence preset: everything open, closing this week. */
        fun closingWeekPreset(sort: SortOption = SortOption.CLOSING): SearchFilters =
            SearchFilters(dateFilter = DateFilter.CLOSING_7D, sort = sort)

        private fun org.json.JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            val out = ArrayList<String>(length())
            for (i in 0 until length()) out.add(optString(i))
            return out
        }

        /** Today's date as yyyy-MM-dd in the device's default timezone. */
        fun todayIso(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)

        /** Subtract days from a yyyy-MM-dd string, returning yyyy-MM-dd. */
        fun minusDays(dayIso: String, days: Int): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val cal = Calendar.getInstance()
            cal.time = fmt.parse(dayIso) ?: return dayIso
            cal.add(Calendar.DAY_OF_YEAR, -days)
            return fmt.format(cal.time)
        }
    }
}
