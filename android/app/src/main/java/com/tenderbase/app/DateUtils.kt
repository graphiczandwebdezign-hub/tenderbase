package com.tenderbase.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil

object DateUtils {

    /** Human "closes in N days" / "closes today" / "closed" from an ISO instant. */
    fun closesLabel(closingAt: String?, closingDate: String?): String {
        val d = parse(closingAt) ?: parseDate(closingDate) ?: return "Closing date n/a"
        val now = Date()
        val diffMs = d.time - now.time
        if (diffMs <= 0) return "Closed"
        val days = ceil(diffMs / (1000.0 * 60 * 60 * 24)).toInt()
        return when {
            days <= 0 -> "Closes today"
            days == 1 -> "Closes tomorrow"
            days <= 60 -> "Closes in $days days"
            else -> "Closes ${prettyDate(d)}"
        }
    }

    /** True when closing within `withinDays` (used for the urgency accent). */
    fun isUrgent(closingAt: String?, closingDate: String?, withinDays: Int = 7): Boolean {
        val d = parse(closingAt) ?: parseDate(closingDate) ?: return false
        val diffMs = d.time - Date().time
        if (diffMs <= 0) return false
        val days = ceil(diffMs / (1000.0 * 60 * 60 * 24)).toInt()
        return days in 0..withinDays
    }

    fun prettyDate(closingAt: String?, closingDate: String?): String {
        val d = parse(closingAt) ?: parseDate(closingDate) ?: return "—"
        return prettyDate(d)
    }

    private fun prettyDate(d: Date): String =
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(d)

    private fun parse(iso: String?): Date? {
        if (iso.isNullOrBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (p in patterns) {
            try {
                val f = SimpleDateFormat(p, Locale.US)
                if (p.endsWith("'Z'")) f.timeZone = TimeZone.getTimeZone("UTC")
                return f.parse(iso)
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseDate(d: String?): Date? {
        if (d.isNullOrBlank()) return null
        return try {
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d)
        } catch (_: Exception) { null }
    }
}
