package com.tenderbase.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.tenderbase.app.ui.screens.OnboardingScreen
import com.tenderbase.app.ui.theme.TenderBaseTheme

/**
 * First-launch flow (spec §14): brand promise → categories → provinces →
 * deadline alerts. Skippable; finishing marks the preference and heads to the
 * feed. Notification permission is requested only from the final page.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var repo: TenderRepository

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { finishOnboarding() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = TenderRepository(this)
        if (repo.isOnboarded()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            TenderBaseTheme {
                OnboardingScreen(
                    onFinish = { finishOnboarding() },
                    onRequestNotifications = { requestNotifications() },
                )
            }
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            finishOnboarding()
        }
    }

    private fun finishOnboarding() {
        repo.setOnboarded(true)
        startActivity(Intent(this, MainActivity::class.java))
        finishAffinity()
    }
}
