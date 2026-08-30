package com.tenderbase.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Background reliability worker (Sprint 6):
 *  1. Pre-caches the discovery feed and closing-this-week tenders so a cold
 *     start offline still shows real data.
 *  2. Posts local deadline reminders for saved tenders closing soon (works
 *     even when FCM is unavailable — data comes from Room + the pre-cache).
 */
class PrecacheWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = TenderRepository(applicationContext)
        return try {
            // 1. Pre-cache live data.
            val feed = ApiClient.fetchTenders(page = 1, limit = 50)
            repo.cacheTenders(feed.items)
            val week = ApiClient.fetchTenders(
                page = 1, limit = 20, filters = Dashboard.closingThisWeekFilters()
            )
            repo.cacheTenders(week.items)

            // 2. Local deadline reminders from saved tenders.
            remindClosingDeadlines(repo)
            Result.success()
        } catch (_: Exception) {
            // Network unavailable — retry with backoff, but stop after a few
            // attempts so a persistent failure can't retry forever.
            if (runAttemptCount >= 4) Result.failure() else Result.retry()
        }
    }

    private fun remindClosingDeadlines(repo: TenderRepository) {
        val saved = repo.getSavedTenders().map { it.toTender() }
        val reminded = repo.remindedTenderIds()
        val due = DeadlineReminders.due(saved, reminded)
        if (due.isEmpty()) return

        val manager = NotificationManagerCompat.from(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 &&
            !manager.areNotificationsEnabled()
        ) return

        ensureChannel(manager)
        for (tender in due) {
            val intent = Intent(applicationContext, DetailActivity::class.java).apply {
                putExtra(DetailActivity.EXTRA_ID, tender.id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                applicationContext,
                tender.id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(
                applicationContext, CHANNEL_ID
            )
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(applicationContext.getString(R.string.reminder_title))
                .setContentText(
                    applicationContext.getString(
                        R.string.reminder_body,
                        tender.title,
                        DateUtils.closesLabel(tender.closingAt, tender.closingDate, tender.deadlineState)
                    )
                )
                .setStyle(
                    NotificationCompat.BigTextStyle.bigText(
                        applicationContext.getString(
                            R.string.reminder_body,
                            tender.title,
                            DateUtils.closesLabel(tender.closingAt, tender.closingDate, tender.deadlineState)
                        )
                    )
                )
                .setAutoCancel(true)
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            try {
                manager.notify(tender.id, notification)
                repo.markReminded(tender.id)
            } catch (_: SecurityException) {
                // Permission revoked mid-flight; stop reminding silently.
                return
            }
        }
    }

    private fun ensureChannel(manager: NotificationManagerCompat) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = applicationContext.getString(R.string.reminder_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "tenderbase_notifications"
        const val UNIQUE_WORK_NAME = "tenderbase_precache"
    }
}
