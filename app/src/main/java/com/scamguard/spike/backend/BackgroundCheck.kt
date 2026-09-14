package com.scamguard.spike.backend

import android.content.Context
import android.util.Log
import com.scamguard.spike.notifications.GuardianAlerter
import com.scamguard.spike.notifications.RiskNotifier
import com.scamguard.spike.registration.UserSession

private const val TAG = "ScamGuard"

/**
 * Shared by every background poller (PollSmsWorker today, an email poller once its
 * silent-token-refresh question is resolved): checks one message against the backend and
 * persists the result, skipping it entirely if already checked. Pulled out as its own
 * function rather than duplicated per source, since both pollers need exactly this.
 *
 * @return true if a check actually ran against the backend, false if skipped (already
 * checked, or no registered user yet).
 */
suspend fun checkAndPersist(
    context: Context,
    source: MessageSource,
    sender: String,
    bodyText: String,
    subject: String? = null,
    isKnownSender: Boolean,
    receivedAtMillis: Long,
    replyTo: String? = null,
    authenticationResults: String? = null
): Boolean {
    val store = CheckedMessageStore.getInstance(context)
    val key = CheckedMessageStore.keyFor(source, sender, receivedAtMillis)
    if (store.get(key) != null) {
        Log.d(TAG, "checkAndPersist: already cached, skipping ($key)")
        return false
    }

    val userId = UserSession.getUserId(context)
    if (userId == null) {
        Log.w(TAG, "checkAndPersist: no registered user_id yet, skipping ($key)")
        return false
    }

    Log.d(TAG, "checkAndPersist: calling backend for $key")
    val result = ScamGuardApiClient.checkMessage(
        userId = userId,
        source = source,
        sender = sender,
        bodyText = bodyText,
        subject = subject,
        isKnownSender = isKnownSender,
        receivedAtMillis = receivedAtMillis,
        replyTo = replyTo,
        authenticationResults = authenticationResults
    )

    if (result is CheckState.Failed && result.isMissingUser) {
        Log.w(TAG, "checkAndPersist: backend no longer knows user_id=$userId, recovering")
        UserSession.recoverFromMissingUser(context)
        return false
    }
    if (result is CheckState.Failed) {
        // This used to fail completely silently -- no log, no UI, nothing -- which made a
        // real network/backend error indistinguishable from "never ran at all". Log it so
        // a stuck "no risk label ever shows up" report can actually be diagnosed from
        // Logcat instead of guessed at.
        Log.e(TAG, "checkAndPersist: backend call failed for $key: ${result.message}")
        return false
    }
    if (result !is CheckState.Done) return false

    Log.d(TAG, "checkAndPersist: got ${result.riskLevel} for $key")
    store.put(key, result.riskLevel, result.reason)
    RiskNotifier.notifyIfRisky(context, source, sender, result.riskLevel, result.reason)
    GuardianAlerter.alertIfRisky(context, source, sender, result.riskLevel, result.reason)
    return true
}
