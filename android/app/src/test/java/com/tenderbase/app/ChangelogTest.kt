package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "What's new" semantics: show on updates only, never fresh installs. */
class ChangelogTest {

    @Test
    fun `latest version is the newest release`() {
        assertEquals("1.1", Changelog.latestVersion())
    }

    @Test
    fun `shows on update from an older version`() {
        assertTrue(Changelog.shouldShow("1.1", "1.0"))
    }

    @Test
    fun `fresh install stays silent`() {
        assertFalse(Changelog.shouldShow("1.1", null))
    }

    @Test
    fun `same version restart stays silent`() {
        assertFalse(Changelog.shouldShow("1.1", "1.1"))
    }

    @Test
    fun `notes exist for known versions only`() {
        assertEquals("1.1", Changelog.notesFor("1.1")?.version)
        assertNull(Changelog.notesFor("9.9"))
        // every release carries highlights
        Changelog.releases.forEach { assertTrue(it.highlights.isNotEmpty()) }
    }

    @Test
    fun `notes are newest-first and versioned`() {
        assertEquals(Changelog.releases.map { it.version }, Changelog.releases.sortedByDescending { it.version }.map { it.version })
    }
}
