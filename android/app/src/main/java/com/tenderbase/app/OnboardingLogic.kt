package com.tenderbase.app

/**
 * Pure onboarding behaviour (Sprint 1): selection semantics and the little
 * decisions the flow makes. No Android imports so every rule is unit-tested
 * on the JVM and the picker can never drift between screens.
 *
 * Semantics contract: an EMPTY selection means "follow everything" — the same
 * interpretation the feed and quick chips already use.
 */
object OnboardingLogic {

    /** Toggle [option] inside [selected]; returns a new list (never mutates). */
    fun toggle(selected: List<String>, option: String): List<String> =
        if (option in selected) selected - option else selected + option

    /** "Select all" picks every option (explicit selection, not the empty set). */
    fun selectAll(options: List<String>): List<String> = options.toList()

    /** Chip renders selected when following-everything OR explicitly picked. */
    fun isChipSelected(selected: List<String>, option: String): Boolean =
        selected.isEmpty() || option in selected
}

/**
 * Single-fire guard for navigational taps (Sprint 1). Rapid double-taps on
 * Continue/Skip/Enable previously fired duplicate pager animations or two
 * finish paths; the guard swallows anything inside [windowMs].
 *
 * Pure JVM (uses [System.currentTimeMillis]); kick-off instant does not
 * matter because the first tap always passes.
 */
object ClickGuard {

    @Volatile
    private var lastAt: Long = 0L

    const val DEFAULT_WINDOW_MS = 600L

    @Synchronized
    fun tryClick(windowMs: Long = DEFAULT_WINDOW_MS): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastAt < windowMs) return false
        lastAt = now
        return true
    }

    /** Reset between flows/tests so a prior tap can't swallow the next flow. */
    @Synchronized
    fun reset() {
        lastAt = 0L
    }
}
