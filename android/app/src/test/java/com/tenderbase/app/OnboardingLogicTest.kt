package com.tenderbase.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Picker semantics (Sprint 1): the same rules drive the onboarding pages and
 * keep "empty means follow everything" honest — the regression the audit
 * flagged as M1 drift risk.
 */
class OnboardingLogicTest {

    @Test
    fun `toggle adds an unselected option`() {
        val out = OnboardingLogic.toggle(listOf("Construction"), "Cleaning")
        assertEquals(listOf("Construction", "Cleaning"), out)
    }

    @Test
    fun `toggle removes a selected option`() {
        val out = OnboardingLogic.toggle(listOf("Construction", "Cleaning"), "Cleaning")
        assertEquals(listOf("Construction"), out)
    }

    @Test
    fun `toggle never mutates the caller's list`() {
        val original = listOf("Gauteng")
        OnboardingLogic.toggle(original, "Limpopo")
        assertEquals(listOf("Gauteng"), original)
    }

    @Test
    fun `empty selection means following everything`() {
        assertTrue(OnboardingLogic.isChipSelected(emptyList(), "Security"))
        assertTrue(OnboardingLogic.isChipSelected(emptyList(), "anything at all"))
    }

    @Test
    fun `chip is selected only when it was picked, once a pick exists`() {
        val selected = listOf("Construction")
        assertTrue(OnboardingLogic.isChipSelected(selected, "Construction"))
        assertFalse(OnboardingLogic.isChipSelected(selected, "Cleaning"))
    }

    @Test
    fun `select all returns the full explicit option list`() {
        val options = listOf("A", "B", "C")
        assertEquals(options, OnboardingLogic.selectAll(options))
        // and it is a copy, not the same instance quietly shared
        val picked = OnboardingLogic.selectAll(options)
        assertTrue(OnboardingLogic.toggle(picked, "A") != options)
    }
}
