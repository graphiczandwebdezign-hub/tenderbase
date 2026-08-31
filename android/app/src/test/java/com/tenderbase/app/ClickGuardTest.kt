package com.tenderbase.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Double-tap guard (Sprint 1): first tap passes, instant retries are swallowed. */
class ClickGuardTest {

    @Test
    fun `first tap always passes`() {
        ClickGuard.reset()
        assertTrue(ClickGuard.tryClick())
    }

    @Test
    fun `an immediate second tap is swallowed`() {
        ClickGuard.reset()
        assertTrue(ClickGuard.tryClick())
        assertFalse(ClickGuard.tryClick())
    }

    @Test
    fun `a tap after the window passes again`() {
        ClickGuard.reset()
        ClickGuard.tryClick(windowMs = 50)
        Thread.sleep(80)
        assertTrue(ClickGuard.tryClick(windowMs = 50))
    }

    @Test
    fun `reset clears the guard`() {
        ClickGuard.reset()
        ClickGuard.tryClick()
        ClickGuard.reset()
        assertTrue(ClickGuard.tryClick())
    }
}
