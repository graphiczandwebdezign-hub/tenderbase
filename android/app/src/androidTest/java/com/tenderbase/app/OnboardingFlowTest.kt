package com.tenderbase.app

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sprint 0 regression harness for the reported crash ("app crashes on the
 * onboarding screen when I tap continue / finish").
 *
 * Walks the exact golden path on a fresh install: welcome → categories →
 * provinces → alerts → "Maybe later" — and asserts the app lands ALIVE on the
 * Home feed. The pre-fix code raced its own task teardown here
 * (`startActivity` + `finishAffinity`, audit finding C1); if the finish
 * navigation ever breaks again, or any onboarding page throws during
 * composition, this test fails red in CI *before* an APK can be published.
 *
 * Runs on the CI emulator matrix (API 26 / 29 / 34).
 *
 * One method on purpose: the app persistently marks onboarding done at the
 * end of the flow, so later methods would find it skipped. Preferences are
 * cleared up-front to guarantee the fresh-install condition.
 */
@RunWith(AndroidJUnit4::class)
class OnboardingFlowTest {

    /**
     * Empty rule + manual scenario launch: lets us wipe preferences BEFORE
     * the activity starts (a plain androidComposeRule launches too early).
     */
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun completeOnboardingFlowLandsAliveOnHomeFeed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        // Fresh-install precondition (idempotent: no-op on the very first run).
        context.getSharedPreferences(TenderRepository.PREFS_NAME, 0)
            .edit().clear().commit()

        // Pre-grant notifications so MainActivity's one-time permission prompt
        // (API 33+) can never interpose a system dialog during assertions.
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName, Manifest.permission.POST_NOTIFICATIONS
            )
        }

        ActivityScenario.launch(OnboardingActivity::class.java).use {
            composeRule.waitForIdle()

            // Page 1/4 — welcome.
            composeRule.onNodeWithText("Never miss a tender deadline").assertIsDisplayed()
            composeRule.onNodeWithText("Continue").assertIsDisplayed().performClick()
            composeRule.waitForIdle()

            // Page 2/4 — categories. Also exercise a chip toggle (VM path).
            composeRule.onNodeWithText("What work do you do?").assertIsDisplayed()
            composeRule.onNodeWithText("Cleaning").assertIsDisplayed().performClick()
            composeRule.onNodeWithText("Continue").assertIsDisplayed().performClick()
            composeRule.waitForIdle()

            // Page 3/4 — provinces.
            composeRule.onNodeWithText("Where do you operate?").assertIsDisplayed()
            composeRule.onNodeWithText("Continue").assertIsDisplayed().performClick()
            composeRule.waitForIdle()

            // Page 4/4 — deadline alerts; leave via the no-permission path.
            composeRule.onNodeWithText("Stay ahead of deadlines").assertIsDisplayed()
            composeRule.onNodeWithText("Maybe later").assertIsDisplayed().performClick()
        }

        // The crash this test guards: the app must be ALIVE on the feed here,
        // not dead on the launcher (C1 finish-navigation race).
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stay ahead of deadlines").assertDoesNotExist()
        composeRule.onNodeWithText("Find opportunities").assertIsDisplayed()

        // State contract: onboarding persisted ONCE, with the toggled chip.
        val prefs = context.getSharedPreferences(TenderRepository.PREFS_NAME, 0)
        assertTrue(prefs.getBoolean("is_onboarded", false))
        assertTrue(
            prefs.getStringSet("selected_categories", emptySet())
                ?.contains("Cleaning") == true
        )
    }
}
