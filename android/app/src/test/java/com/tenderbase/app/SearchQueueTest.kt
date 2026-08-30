package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline saved-search queue: encode/decode/add/remove semantics. */
class SearchQueueTest {

    @Test
    fun `null and corrupt json decode to empty`() {
        assertTrue(SearchQueue.decode(null).isEmpty())
        assertTrue(SearchQueue.decode("").isEmpty())
        assertTrue(SearchQueue.decode("not json {").isEmpty())
        assertTrue(SearchQueue.decode("[1,2,3]").isEmpty())
    }

    @Test
    fun `add and remove round trip`() {
        val withOne = SearchQueue.add(null, "KZN", "{\"search\":\"construction\"}")
        val entries = SearchQueue.decode(withOne)
        assertEquals(1, entries.size)
        assertEquals("KZN", entries[0].name)
        assertEquals("{\"search\":\"construction\"}", entries[0].payload)

        val withTwo = SearchQueue.add(withOne, "Gauteng", "{\"province\":\"Gauteng\"}")
        assertEquals(2, SearchQueue.decode(withTwo).size)

        val removed = SearchQueue.remove(withTwo, "KZN")
        val remaining = SearchQueue.decode(removed)
        assertEquals(1, remaining.size)
        assertEquals("Gauteng", remaining[0].name)
    }

    @Test
    fun `re-adding the same name replaces the payload`() {
        val first = SearchQueue.add(null, "Watch", "{\"search\":\"old\"}")
        val second = SearchQueue.add(first, "Watch", "{\"search\":\"new\"}")
        val entries = SearchQueue.decode(second)
        assertEquals(1, entries.size)
        assertEquals("{\"search\":\"new\"}", entries[0].payload)
    }

    @Test
    fun `removing an absent name is a no-op`() {
        val json = SearchQueue.add(null, "A", "{}")
        assertEquals(SearchQueue.decode(json), SearchQueue.decode(SearchQueue.remove(json, "B")))
    }

    @Test
    fun `empty name entries are ignored on decode`() {
        assertEquals(0, SearchQueue.decode("""[{"payload":"{}"}]""").size)
    }
}
