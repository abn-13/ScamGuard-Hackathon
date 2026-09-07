package com.scamguard.spike.sms

import androidx.compose.runtime.mutableStateOf
import com.scamguard.spike.backend.CheckState

/** One SMS message as read from the device. Kept local to the SMS module on purpose. */
data class SmsMessageItem(
    val sender: String,
    val timestampMillis: Long,
    val body: String
) {
    /**
     * Not a constructor property on purpose: keeps this out of equals()/hashCode() (two
     * items are still "the same message" regardless of check progress) and lets Compose
     * observe just this field for recomposition as the backend call progresses.
     */
    val checkState = mutableStateOf<CheckState>(CheckState.Idle)
}
