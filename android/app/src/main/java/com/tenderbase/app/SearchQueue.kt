package com.tenderbase.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Offline queue for saved-search creation: entries are kept as JSON until the
 * network allows a sync. Pure Kotlin — the caller owns persistence.
 */
object SearchQueue {

    data class Entry(val name: String, val payload: String)

    fun decode(json: String?): List<Entry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isNotEmpty()) out.add(Entry(name, o.optString("payload")))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun encode(entries: List<Entry>): String {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("name", e.name).put("payload", e.payload))
        }
        return arr.toString()
    }

    /** Add (or replace, by name) an entry; returns the new JSON. */
    fun add(json: String?, name: String, payload: String): String {
        val kept = decode(json).filterNot { it.name == name }
        return encode(kept + Entry(name, payload))
    }

    /** Remove an entry by name; returns the new JSON. */
    fun remove(json: String?, name: String): String =
        encode(decode(json).filterNot { it.name == name })
}
