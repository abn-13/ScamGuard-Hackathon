package com.scamguard.spike.backend

import android.content.Context
import com.scamguard.spike.registration.UserSession

/**
 * Shared by every background poller (PollSmsWorker today, an email poller once its
 * silent-token-refresh question is resolved): checks one message against the backend and
 * persists the result, skipping it entirely if already checked. Pulled out as its own
 * function rather than duplicated per source, since both pollers need exactly this.
 *
 * Deliberately does NOT post a notification -- that's separate in-progress work. The
 * point where a medium/high risk_level becomes known is marked below.
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
    val store = CheckedMessageStore(context)
    val key = CheckedMessageStore.keyFor(source, sender, receivedAtMillis)
    if (store.get(key) != null) return false

    val userId = UserSession.getUserId(context) ?: return false

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

    if (result !is CheckState.Done) return false

    store.put(key, result.riskLevel, result.reason)
    // NOTE(teammate working on notifications): this is where a medium/high risk_level is
    // known and the user could be alerted on-device. result.riskLevel / result.reason /
    // sender are all available here.
    return true
}
