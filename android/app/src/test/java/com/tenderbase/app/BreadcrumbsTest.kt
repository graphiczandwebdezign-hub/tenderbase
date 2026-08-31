package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Breadcrumb ring buffer: bounded, FIFO, order-preserving (Sprint 0). */
class BreadcrumbsTest {

    @Test
    fun `keeps entries in insertion order`() {
        val b = Breadcrumbs(capacity = 8)
        b.add("first")
        b.add("second")
        assertEquals(listOf("first", "second"), b.snapshot())
    }

    @Test
    fun `never exceeds capacity and drops the oldest first`() {
        val b = Breadcrumbs(capacity = 3)
        repeat(6) { b.add("event-$it") }
        assertEquals(3, b.size())
        assertEquals(listOf("event-3", "event-4", "event-5"), b.snapshot())
    }

    @Test
    fun `capacity of one keeps only the latest`() {
        val b = Breadcrumbs(capacity = 1)
        b.add("old")
        b.add("new")
        assertEquals(listOf("new"), b.snapshot())
    }

    @Test
    fun `clear empties the trail`() {
        val b = Breadcrumbs()
        b.add("something")
        b.clear()
        assertTrue(b.snapshot().isEmpty())
    }

    @Test
    fun `snapshot is a copy, not a live view`() {
        val b = Breadcrumbs()
        b.add("a")
        val snap = b.snapshot()
        b.add("b")
        assertEquals(listOf("a"), snap)
        assertEquals(listOf("a", "b"), b.snapshot())
    }
}
