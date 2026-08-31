package com.tenderbase.app

/**
 * In-app changelog (Sprint 11): version notes shown as a "What's new" dialog
 * when the app is UPDATED (fresh installs stay silent — onboarding already
 * covers them). Pure Kotlin so the semantics are unit-testable.
 */
object Changelog {

    data class ReleaseNotes(val version: String, val highlights: List<String>)

    /** Newest first. The first entry is the current release. */
    val releases: List<ReleaseNotes> = listOf(
        ReleaseNotes(
            version = "1.2",
            highlights = listOf(
                "• Complete visual redesign — new brand identity, light & dark themes",
                "• Bottom navigation: Home, Search, Saved, Alerts, More",
                "• Feed shows results summary with active-filter chips and instant re-search",
                "• Tender detail is now a bid workspace: deadlines, documents, checklist, notes",
                "• Download and share documents straight from the tender",
                "• Friendlier offline / error handling with retry everywhere"
            )
        ),
        ReleaseNotes(
            version = "1.1",
            highlights = listOf(
                "• Deadline command centre: closing-this-week and saved buckets at a glance",
                "• Bid workspace: checklist + notes per tender, shareable bid pack",
                "• Workspaces back up to the server and restore on a new device",
                "• Offline: queue saved searches and pre-cache the feed",
                "• Local deadline reminders — closing within 48 hours",
                "• Documents grouped by type, addenda first"
            )
        ),
        ReleaseNotes(
            version = "1.0",
            highlights = listOf(
                "• Discover South African tenders with search + filters",
                "• Save searches and get alerts for new matches",
                "• Tender detail with documents, calendar slot and sharing"
            )
        ),
    )

    fun latestVersion(): String = releases.first().version

    /**
     * Whether a "What's new" dialog should be shown: only on an update
     * (previous version recorded and different). Fresh installs and
     * same-version restarts stay quiet.
     */
    fun shouldShow(current: String, lastSeen: String?): Boolean =
        lastSeen != null && lastSeen != current

    /** Notes for [current], or null if that version isn't in the log. */
    fun notesFor(current: String): ReleaseNotes? =
        releases.firstOrNull { it.version == current }
}
