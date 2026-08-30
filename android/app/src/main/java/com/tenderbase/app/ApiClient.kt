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

    data class Page(val items: List<Tender>, val total: Int, val page: Int, val totalPages: Int)

    /** GET /api/v1/tenders with optional search + category + province filters. */
    suspend fun fetchTenders(
        page: Int = 1,
        limit: Int = 25,
        search: String? = null,
        category: String? = null,
        province: String? = null
    ): Page = withContext(Dispatchers.IO) {
        val sb = StringBuilder("$BASE_URL/api/v1/tenders?page=$page&limit=$limit")
        if (!search.isNullOrBlank()) sb.append("&search=").append(enc(search))
        if (!category.isNullOrBlank()) sb.append("&category=").append(enc(category))
        if (!province.isNullOrBlank()) sb.append("&province=").append(enc(province))
        val body = get(sb.toString())
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

    private fun get(urlStr: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("X-API-Key", API_KEY)
        conn.setRequestProperty("Accept", "application/json")
        // The free Render tier cold-starts (~50s), so allow a generous timeout.
        conn.connectTimeout = 20000
        conn.readTimeout = 70000
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code: $text")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }
}
