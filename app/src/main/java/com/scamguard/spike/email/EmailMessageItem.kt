package com.scamguard.spike.email

import androidx.compose.runtime.mutableStateOf
import com.scamguard.spike.backend.CheckState

/** One Gmail message as read via the Gmail API. Kept local to the email module on purpose. */
data class EmailMessageItem(
    val sender: String,
    val timestampMillis: Long,
    val subject: String,
    val snippet: String,
    // Best-effort plain-text body sent to the backend for judging -- richer signal than
    // the snippet alone (e.g. the actual link text/URL further down the message).
    val bodyText: String,
    // Computed on-device from Gmail thread history (Task 4): true when this message is
    // part of a multi-message thread, i.e. there's been back-and-forth with this sender.
    val isKnownSender: Boolean
) {
    /** Not a constructor property on purpose -- see SmsMessageItem for why. */
    val checkState = mutableStateOf<CheckState>(CheckState.Idle)
}
