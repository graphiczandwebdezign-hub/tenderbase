package com.tenderbase.app

import org.json.JSONArray

/**
 * Recent-search list storage: newest first, deduped case-insensitively,
 * capped at [MAX]. Stored as a JSON array in the app prefs so the state is
 * trivially restorable and never blocks startup. Pure Kotlin — unit-tested.
 */
object RecentSearches {

    const val MAX = 8

    fun decode(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotBlank()) out.add(s)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(list: List<String>): String {
        val arr = JSONArray()
        for (s in list) arr.put(s)
        return arr.toString()
    }

    /** Add (or move to front) [query]; blank queries are ignored. */
    fun add(json: String?, query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return encode(decode(json))
        val kept = decode(json).filterNot { it.equals(trimmed, ignoreCase = true) }
        val next = mutableListOf(trimmed)
        next += kept
        return encode(next.take(MAX))
    }

    fun remove(json: String?, query: String): String {
        val target = query.trim()
        return encode(decode(json).filterNot { it.equals(target, ignoreCase = true) })
    }

    fun clear(): String = "[]"
}
