package com.tenderbase.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.tenderbase.app.ui.screens.OnboardingScreen
import com.tenderbase.app.ui.theme.TenderBaseTheme
import com.tenderbase.app.ui.vm.OnboardingViewModel

/**
 * First-launch flow (spec §14): brand promise → categories → provinces →
 * deadline alerts. Skippable; finishing marks the preference and heads to the
 * feed. Notification permission is requested only from the final page.
 *
 * Sprint 0+1 (audit finding C1): leaving the flow previously did
 * `startActivity(MainActivity)` + `finishAffinity()` — a race that could
 * tear down the just-launched main screen on the same task (the reported
 * "crashes when I tap continue"). Now there is ONE exit path, implemented as
 * a task-replacing launch: NEW_TASK | CLEAR_TASK + finish(), with a
 * double-tap guard on every exit button.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var repo: TenderRepository
    private val vm: OnboardingViewModel by viewModels()

    /** Single re-entrancy lock: whatever fires first (Skip / Maybe later /
     * permission result) wins; every other exit signal becomes a no-op. */
    @Volatile
    private var finishing = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        CrashReporter.breadcrumb("ob: notif permission result granted=$granted")
        completeOnboarding("enable-alerts")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = TenderRepository(applicationContext)
        if (repo.isOnboarded()) {
            routeToMain()
            return
        }
        ClickGuard.reset()
        enableEdgeToEdge()
        setContent {
            TenderBaseTheme {
                OnboardingScreen(
                    vm = vm,
                    onFinish = {
                        if (ClickGuard.tryClick()) {
                            CrashReporter.breadcrumb("ob: skip tapped")
                            completeOnboarding("skip")
                        }
                    },
                    onRequestNotifications = { requestNotifications() },
                )
            }
        }
    }

    private fun requestNotifications() {
        if (!ClickGuard.tryClick()) return
        CrashReporter.breadcrumb("ob: enable deadline alerts tapped (api=${Build.VERSION.SDK_INT})")
        // Mark BEFORE the system dialog: MainActivity honours the same flag
        // and must not ask a second time on the very first feed open (H2).
        repo.setNotifPermissionAsked()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            completeOnboarding("enable-alerts")
        }
    }

    /**
     * The ONLY way out of onboarding. Persists via the ViewModel (one write),
     * then replaces the task with MainActivity: the old
     * startActivity()+finishAffinity() race cannot strand the user on the
     * launcher anymore because the new task is created atomically with its
     * root activity.
     */
    private fun completeOnboarding(via: String) {
        if (finishing) return
        finishing = true
        CrashReporter.breadcrumb("ob: completing via $via")
        vm.completeOnboarding()
        routeToMain()
    }

    private fun routeToMain() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            )
        )
        finish()
    }
}
