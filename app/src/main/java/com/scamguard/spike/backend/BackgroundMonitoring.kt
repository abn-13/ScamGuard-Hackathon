package com.scamguard.spike.backend

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
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
    private const val PREFS_NAME = "scamguard_background_monitoring"
    private const val KEY_SMS_INITIAL_CHECK_DONE = "sms_initial_check_done"
    private const val KEY_EMAIL_INITIAL_CHECK_DONE = "email_initial_check_done"

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
        // No blind "immediate one-off run" enqueued here anymore -- this runs from
        // MainActivity.onCreate, before the user has necessarily granted SMS permission or
        // authorized Gmail yet. Confirmed live: an immediate run enqueued at this point
        // fires and skips ("READ_SMS not granted" / "no silent Gmail token available")
        // before those become available, burning the one guaranteed early check for
        // nothing -- see triggerInitialSmsCheck/triggerInitialEmailCheck below instead,
        // called once the actual prerequisite is confirmed ready.
    }

    /**
     * Fires an immediate background SMS check the moment READ_SMS/READ_CONTACTS are
     * actually granted, instead of waiting up to 15 minutes for the periodic schedule's
     * next cycle. Safe to call every time SmsListActivity confirms permission is granted
     * (including "already granted from a previous visit") -- guarded by a persisted flag
     * so the real enqueue only ever happens once per install; every later call is just a
     * cheap SharedPreferences read.
     */
    fun triggerInitialSmsCheck(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_SMS_INITIAL_CHECK_DONE, false)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "sms-poll-once",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PollSmsWorker>().build()
        )
        prefs.edit().putBoolean(KEY_SMS_INITIAL_CHECK_DONE, true).apply()
    }

    /**
     * Same idea for email -- call once a Gmail access token is actually in hand (silent or
     * interactive; EmailListActivity doesn't need to tell them apart, this is self-gating).
     * Without this flag, calling it from every successful silent re-auth (i.e. every normal
     * revisit to the Email screen once authorized) would re-enqueue a fresh ~50-call Gmail
     * fetch each time -- confirmed live that repeating that within a short test session is
     * enough to trip Gmail's per-minute RATE_LIMIT_EXCEEDED quota.
     */
    fun triggerInitialEmailCheck(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_EMAIL_INITIAL_CHECK_DONE, false)) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            "email-poll-once",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PollEmailWorker>().build()
        )
        prefs.edit().putBoolean(KEY_EMAIL_INITIAL_CHECK_DONE, true).apply()
    }
}
