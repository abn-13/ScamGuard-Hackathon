package com.scamguard.spike.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.scamguard.spike.backend.MessageSource
import com.scamguard.spike.registration.UserSession

/**
 * Sends a real SMS to the registered family member's phone straight from this device when
 * a checked message comes back medium or high risk. Deliberately device-native rather than
 * a backend/Telegram integration: the phone number is already collected at registration
 * (see RegistrationActivity), the protected person's own phone already has SMS hardware,
 * and this needs zero external account/service to work -- the fastest path to an actually
 * working alert. Telegram delivery (backend/app/alerts.py) is a separate, still-stubbed
 * channel that can layer on top of this later; the two aren't mutually exclusive.
 *
 * Called from the same places as [RiskNotifier] (SmsListActivity, EmailListActivity,
 * checkAndPersist) so both the on-device notification and the guardian SMS fire together.
 */
object GuardianAlerter {

    fun alertIfRisky(
        context: Context,
        source: MessageSource,
        sender: String,
        riskLevel: String,
        reason: String
    ) {
        if (!isRiskyEnoughToAlert(riskLevel)) return

        val guardianPhoneNumber = UserSession.getGuardianPhoneNumber(context) ?: return

        // No permission (never granted, or denied) -- fail silently, same as RiskNotifier
        // does for POST_NOTIFICATIONS. The in-app CheckStateLabel and any device
        // notification are still the fallback.
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) return

        val sourceLabel = if (source == MessageSource.SMS) "SMS" else "email"
        val body = "ScamGuard alert: a ${riskLevel.uppercase()} risk $sourceLabel from " +
            "$sender was just flagged. Reason: $reason"

        // sendTextMessage() itself throws for bodies over ~160 chars -- divide/multipart
        // handles that instead of silently truncating an alert that names the actual scam.
        runCatching {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(body)
            smsManager.sendMultipartTextMessage(guardianPhoneNumber, null, parts, null, null)
        }
    }
}
