package com.scamguard.spike.backend

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.scamguard.spike.email.PollEmailWorker
import com.scamguard.spike.sms.PollSmsWorker
import java.util.concurrent.TimeUnit

/**
 * Schedules all periodic background-monitoring work. Safe to call on every app launch --
 * enqueueUniquePeriodicWork with KEEP is a no-op if the work is already scheduled, so this
 * doesn't reset the schedule or duplicate jobs on repeated calls.
 *
 * 15 minutes is Android's enforced minimum interval for periodic work, not a choice made
 * here -- there's no faster option without a push mechanism. Confirmed by testing that
 * SMS_RECEIVED doesn't reach this app on this platform (see PollSmsWorker), and Gmail has
 * no simple on-device push either (would need Cloud Pub/Sub + a public backend endpoint).
 */
object BackgroundMonitoring {
    private val POLL_INTERVAL = 15L to TimeUnit.MINUTES

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)

        workManager.enqueueUniquePeriodicWork(
            PollSmsWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PollSmsWorker>(POLL_INTERVAL.first, POLL_INTERVAL.second).build()
        )

        workManager.enqueueUniquePeriodicWork(
            PollEmailWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<PollEmailWorker>(POLL_INTERVAL.first, POLL_INTERVAL.second).build()
        )
    }
}
