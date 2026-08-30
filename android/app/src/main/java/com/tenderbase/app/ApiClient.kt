package com.tenderbase.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Tiny TenderBase API client using only the JDK's HttpURLConnection — no
 * networking library needed. All calls are suspend functions off the main
 * thread.
 */
object ApiClient {

    // The live TenderBase service. Change here to point at another deployment.
    const val BASE_URL = "https://tenderbase-api.onrender.com"

    // Read-only data key. Safe to ship in the client for a public read API;
    // rotate/replace with per-user keys when you add accounts.
    private const val API_KEY = "22phzXr7bunJ3r2gzgrynej4I71rf+kGIeu43NLsABM="

    data class Page(
        val items: List<Tender>,
        val total: Int,
        val page: Int,
        val totalPages: Int
    )

    data class FacetItem(val name: String, val count: Int)
    data class Facets(
        val provinces: List<FacetItem>,
        val categories: List<FacetItem>,
        val sources: List<FacetItem>
    )

    /**
     * GET /api/v1/tenders with the full discovery state (search, facet
     * filters, date window, sort) and server-side pagination.
     */
    suspend fun fetchTenders(
        page: Int = 1,
        limit: Int = 25,
        filters: SearchFilters = SearchFilters()
    ): Page = withContext(Dispatchers.IO) {
        val params = filters.toQueryParams(SearchFilters.todayIso())
            .map { (k, v) -> k to v }
            .toMutableList()
        params.add("page" to page.toString())
        params.add("limit" to limit.toString())
        if (filters.query.isNotBlank()) params.add("search" to filters.query.trim())
        val url = "$BASE_URL/api/v1/tenders?" + params.joinToString("&") { (k, v) ->
            "$k=${enc(v)}"
        }
        val body = get(url)
        val root = JSONObject(body)
        val arr = root.optJSONArray("data") ?: org.json.JSONArray()
        val pg = root.optJSONObject("pagination")
        Page(
            items = Tender.listFromArray(arr),
            total = pg?.optInt("total") ?: arr.length(),
            page = pg?.optInt("page") ?: page,
            totalPages = pg?.optInt("total_pages") ?: 1
        )
    }

    /** GET /api/v1/tenders/facets — filter options with live counts. */
    suspend fun fetchFacets(): Facets = withContext(Dispatchers.IO) {
        val root = JSONObject(get("$BASE_URL/api/v1/tenders/facets"))
        Facets(
            provinces = facetList(root, "provinces"),
            categories = facetList(root, "categories"),
            sources = facetList(root, "sources")
        )
    }

    private fun facetList(root: JSONObject, key: String): List<FacetItem> {
        val arr = root.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<FacetItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name")
            if (name.isNotEmpty()) out.add(FacetItem(name, o.optInt("count")))
        }
        return out
    }

    /** GET /api/v1/tenders/{id} -> a single tender with full detail. */
    suspend fun fetchTender(id: Int): Tender = withContext(Dispatchers.IO) {
        val body = get("$BASE_URL/api/v1/tenders/$id")
        Tender.fromJson(JSONObject(body))
    }

    /** GET /api/v1/categories -> list of {slug,name} as display names. */
    suspend fun fetchCategories(): List<String> = withContext(Dispatchers.IO) {
        try {
            val arr = org.json.JSONArray(get("$BASE_URL/api/v1/categories"))
            val out = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(o.optString("name").ifEmpty { o.optString("slug") })
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ------------------------------------------------------ saved searches

    data class SavedSearchInfo(
        val id: Int,
        val name: String,
        val alertsEnabled: Boolean,
        val payload: JSONObject,
        val createdAt: String?
    )

    /** GET /api/v1/saved-searches?client_id=… */
    suspend fun fetchSavedSearches(clientId: String): List<SavedSearchInfo> =
        withContext(Dispatchers.IO) {
            val root = JSONObject(get("$BASE_URL/api/v1/saved-searches?client_id=${enc(clientId)}"))
            val arr = root.optJSONArray("searches") ?: org.json.JSONArray()
            val out = ArrayList<SavedSearchInfo>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    SavedSearchInfo(
                        id = o.optInt("id"),
                        name = o.optString("name"),
                        alertsEnabled = o.optBoolean("alerts_enabled", true),
                        payload = o.optJSONObject("filters") ?: JSONObject(),
                        createdAt = o.optString("created_at").ifEmpty { null }
                    )
                )
            }
            out
        }

    /** POST /api/v1/saved-searches — throws on 409 duplicate name. */
    suspend fun createSavedSearch(
        clientId: String,
        name: String,
        payload: JSONObject
    ): SavedSearchInfo = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("client_id", clientId)
            .put("name", name)
            .put("filters", payload)
        val root = JSONObject(
            request("POST", "$BASE_URL/api/v1/saved-searches", body)
        )
        SavedSearchInfo(
            id = root.optInt("id"),
            name = root.optString("name"),
            alertsEnabled = root.optBoolean("alerts_enabled", true),
            payload = root.optJSONObject("filters") ?: JSONObject(),
            createdAt = root.optString("created_at").ifEmpty { null }
        )
    }

    /** PATCH /api/v1/saved-searches/{id}/alerts */
    suspend fun setSavedSearchAlerts(clientId: String, id: Int, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            request(
                "PATCH",
                "$BASE_URL/api/v1/saved-searches/$id/alerts",
                JSONObject().put("client_id", clientId).put("alerts_enabled", enabled)
            )
        }
    }

    /** DELETE /api/v1/saved-searches/{id} */
    suspend fun deleteSavedSearch(clientId: String, id: Int) {
        withContext(Dispatchers.IO) {
            request(
                "DELETE",
                "$BASE_URL/api/v1/saved-searches/$id?client_id=${enc(clientId)}",
                null
            )
        }
    }

    /** POST /api/v1/notifications/register-device (for saved-search alerts). */
    suspend fun registerDevice(clientId: String, deviceToken: String) {
        withContext(Dispatchers.IO) {
            request(
                "POST",
                "$BASE_URL/api/v1/notifications/register-device",
                JSONObject()
                    .put("client_id", clientId)
                    .put("device_token", deviceToken)
                    .put("platform", "android")
            )
        }
    }

    // ------------------------------------------------------------ transport

    private fun get(urlStr: String): String = request("GET", urlStr, null)

    private fun request(method: String, urlStr: String, body: JSONObject?): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.setRequestProperty("X-API-Key", API_KEY)
        conn.setRequestProperty("Accept", "application/json")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        // The free Render tier cold-starts (~50s), so allow a generous timeout.
        conn.connectTimeout = 20000
        conn.readTimeout = 70000
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw ApiException(code, text)
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** HTTP error with its status code so callers can react (e.g. 409 dup). */
    class ApiException(val statusCode: Int, detail: String) :
        RuntimeException("HTTP $statusCode: $detail")
}
