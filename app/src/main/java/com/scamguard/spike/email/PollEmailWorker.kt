package com.scamguard.spike.email

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.scamguard.spike.backend.MessageSource
import com.scamguard.spike.backend.checkAndPersist
import com.scamguard.spike.registration.UserSession

/**
 * Periodic background check for email, mirroring PollSmsWorker's approach: poll, not
 * push -- Gmail has no simple on-device push either, and this way both sources share the
 * same proven mechanism instead of two different ones.
 *
 * The one extra step email needs is a fresh access token. Confirmed via direct testing
 * (not just API docs) that AuthorizationClient.authorize() succeeds silently, with zero
 * UI, from exactly this kind of no-Activity background context -- as long as the user
 * has granted Gmail access at least once through the interactive flow (EmailListActivity's
 * "Authorize Gmail access" button). If that grant is ever revoked, authorize() instead
 * returns a result requiring UI to resolve, which a background Worker can't show -- that
 * case, and having never authorized at all, both just skip this poll cycle rather than
 * fail loudly; the next successful interactive authorization fixes it going forward.
 */
class PollEmailWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (UserSession.getUserId(applicationContext) == null) return Result.success()

        val accessToken = GoogleAuthHelper.silentAccessTokenOrNull(applicationContext)
            ?: return Result.success()

        val (_, emails) = GmailApiClient.fetchProfileAndRecentEmails(accessToken)
        // Only the newest few each run, not the whole fetched batch -- see PollSmsWorker's
        // equivalent for why. Sorted defensively rather than trusting the Gmail API's
        // response order to already be newest-first.
        val newest = emails.sortedByDescending { it.timestampMillis }.take(CHECK_LIMIT)
        for (email in newest) {
            checkAndPersist(
                context = applicationContext,
                source = MessageSource.EMAIL,
                sender = email.sender,
                bodyText = email.bodyText,
                subject = email.subject,
                isKnownSender = email.isKnownSender,
                receivedAtMillis = email.timestampMillis,
                replyTo = email.replyTo,
                authenticationResults = email.authenticationResults
            )
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "email-poll"
        private const val CHECK_LIMIT = 5
    }
}
