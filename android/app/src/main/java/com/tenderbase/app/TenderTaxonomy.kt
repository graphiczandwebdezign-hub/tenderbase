package com.tenderbase.app

/**
 * Single source of truth for the tender taxonomy + search suggestions.
 * Used by onboarding, preferences and the search screen so the lists can
 * never drift apart.
 */
object TenderTaxonomy {

    val PROVINCES = listOf(
        "Eastern Cape",
        "Free State",
        "Gauteng",
        "KwaZulu-Natal",
        "Limpopo",
        "Mpumalanga",
        "Northern Cape",
        "North West",
        "Western Cape",
        "National"
    )

    val CATEGORIES = listOf(
        "Construction",
        "Civil Works",
        "IT & Technology",
        "Catering",
        "Cleaning",
        "Security",
        "Transport",
        "Professional Services",
        "Supplies",
        "Engineering",
        "Medical",
        "Agriculture",
        "Other"
    )

    /** Tap-to-search starters shown before the user has typed anything. */
    val SUGGESTED_SEARCHES = listOf(
        "Construction tenders",
        "IT tenders",
        "Cleaning services",
        "Catering",
        "Security services",
        "Transport"
    )

    /** Display name for a category value coming from the API (kebab/snake safe). */
    fun displayName(raw: String): String =
        raw.replace('-', ' ').replace('_', ' ').trim().split(" ")
            .joinToString(" ") { w ->
                if (w.length <= 2 && w != "IT") w.lowercase()
                else w.replaceFirstChar { it.uppercase() }
            }
}
