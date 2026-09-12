package com.scamguard.spike.backend

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.scamguard.spike.sms.PollSmsWorker
import java.util.concurrent.TimeUnit

/**
 * Schedules all periodic background-monitoring work. Safe to call on every app launch --
 * enqueueUniquePeriodicWork with KEEP is a no-op if the work is already scheduled, so this
 * doesn't reset the schedule or duplicate jobs on repeated calls.
 *
 * 15 minutes is Android's enforced minimum interval for periodic work, not a choice made
 * here -- there's no faster option without a push mechanism (which doesn't exist for SMS
 * on this platform, see PollSmsWorker, and would need Gmail Pub/Sub + a public backend
 * endpoint for email).
 */
object BackgroundMonitoring {
    private val POLL_INTERVAL = 15L to TimeUnit.MINUTES

    fun schedule(context: Context) {
        val smsRequest = PeriodicWorkRequestBuilder<PollSmsWorker>(
            POLL_INTERVAL.first, POLL_INTERVAL.second
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PollSmsWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            smsRequest
        )
    }
}
