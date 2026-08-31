package com.tenderbase.app

import android.app.Application
import android.os.StrictMode
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** App entry: crash capture first, periodic pre-cache/reminder worker second. */
class TenderBaseApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Sprint 0 (audit C2): capture every fatal crash to on-device storage
        // BEFORE anything else runs — the handler must exist before any code
        // that could throw.
        CrashReporter.install(this)
        installStrictMode()

        // Honor a chosen theme before the first activity renders (avoids a flash).
        val mode = getSharedPreferences(TenderRepository.PREFS_NAME, MODE_PRIVATE)
            .getString("theme_mode", null)
        AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                "light" -> AppCompatDelegate.MODE_NIGHT_NO
                "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<PrecacheWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            PrecacheWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /**
     * Debug builds surface disk/network-on-main and lifecycle violations in
     * logcat (log-only — never takes the app down, so instrumented tests and
     * daily dogfooding stay green while violations become visible).
     */
    private fun installStrictMode() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
        )
    }
}
