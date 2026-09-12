package com.scamguard.spike.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.scamguard.spike.backend.MessageSource
import com.scamguard.spike.backend.checkAndPersist

/**
 * Periodic background check for SMS, whether the app is open or not.
 *
 * This was originally built as an instant, broadcast-driven background receiver
 * (SmsBackgroundReceiver, since removed) -- confirmed via adb's own broadcast-dispatch log
 * (`dumpsys activity broadcasts`) that on this device/OS, SMS_RECEIVED is delivered
 * exclusively to the default SMS app (Google Messages) and never reaches any other app's
 * receiver, dynamic or manifest-declared. Since we can't rely on being notified the
 * instant a message arrives, this re-reads the inbox on a schedule instead and checks
 * whatever's new -- the same approach email background monitoring needs anyway, since
 * Gmail has no simple on-device push either.
 *
 * Cheap to run often: SmsReader caps the inbox read at 50 messages, and checkAndPersist
 * skips anything already in the CheckedMessageStore with just a local SQLite lookup, so a
 * real network call only happens for genuinely new messages.
 */
class PollSmsWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        // Quietly no-op until the user has visited the SMS screen and granted READ_SMS/
        // READ_CONTACTS at least once -- not a failure worth retrying sooner for.
        val hasPermission = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return Result.success()

        val inbox = SmsReader.readInbox(applicationContext)
        for (message in inbox) {
            val isKnown = ContactLookup.isKnownSender(applicationContext, message.sender)
            checkAndPersist(
                context = applicationContext,
                source = MessageSource.SMS,
                sender = message.sender,
                bodyText = message.body,
                isKnownSender = isKnown,
                receivedAtMillis = message.timestampMillis
            )
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "sms-poll"
    }
}
