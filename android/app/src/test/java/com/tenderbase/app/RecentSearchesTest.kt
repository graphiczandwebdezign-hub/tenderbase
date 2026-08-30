package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchesTest {

    @Test
    fun `add prepends and dedupes case-insensitively`() {
        var json: String? = null
        json = RecentSearches.add(json, "construction gauteng")
        json = RecentSearches.add(json, "security")
        json = RecentSearches.add(json, "Construction Gauteng")
        assertEquals(listOf("Construction Gauteng", "security"), RecentSearches.decode(json))
    }

    @Test
    fun `list is capped at MAX keeping newest`() {
        var json: String? = null
        for (i in 1..12) json = RecentSearches.add(json, "q$i")
        val out = RecentSearches.decode(json)
        assertEquals(RecentSearches.MAX, out.size)
        assertEquals("q12", out.first())
        assert("q1" !in out)
    }

    @Test
    fun `remove and clear and garbage input`() {
        var json = RecentSearches.add(null, "a")
        json = RecentSearches.add(json, "b")
        assertEquals(listOf("b"), RecentSearches.decode(RecentSearches.remove(json, " A ")))
        assertEquals(emptyList<String>(), RecentSearches.decode(RecentSearches.clear()))
        assertEquals(emptyList<String>(), RecentSearches.decode("{not json"))
        assertEquals(emptyList<String>(), RecentSearches.decode(null))
    }

    @Test
    fun `blank queries are ignored`() {
        val json = RecentSearches.add(null, "   ")
        assertEquals(emptyList<String>(), RecentSearches.decode(json))
    }
}
