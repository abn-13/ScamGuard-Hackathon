package com.scamguard.spike.notifications

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.scamguard.spike.MainActivity
import com.scamguard.spike.backend.MessageSource

/**
 * Shows a system notification when a checked SMS/email comes back medium or high risk.
 * Deliberately a plain reusable entry point (not tied to any Activity) so both the
 * foreground list screens (SmsListActivity/EmailListActivity) and any future background
 * monitoring service can call the same [notifyIfRisky] after getting a `CheckState.Done`
 * result, and get consistent behavior either way.
 */
object RiskNotifier {

    private const val CHANNEL_ID = "risk_alerts"

    // Mirrors the backend's own threshold for alerting family over Telegram (see
    // backend/app/main.py: `verdict.risk_level in (RiskLevel.medium, RiskLevel.high)`) so
    // the on-device notification and the family alert fire for the same set of messages.
    private val NOTIFY_LEVELS = setOf("medium", "high")

    // areNotificationsEnabled() below is the real runtime guard for POST_NOTIFICATIONS
    // (API 33+); lint can't see that from here, hence the suppression.
    @SuppressLint("MissingPermission")
    fun notifyIfRisky(
        context: Context,
        source: MessageSource,
        sender: String,
        riskLevel: String,
        reason: String
    ) {
        if (riskLevel.lowercase() !in NOTIFY_LEVELS) return

        ensureChannel(context)

        val manager = NotificationManagerCompat.from(context)
        // Permission not granted (denied, or never asked pre-API 33) -- fail silently
        // rather than crash. The in-app CheckStateLabel is still the fallback UI.
        if (!manager.areNotificationsEnabled()) return

        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sourceLabel = if (source == MessageSource.SMS) "SMS" else "email"
        val body = "$sender: $reason"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ ${riskLevel.uppercase()} RISK $sourceLabel")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()

        // One notification per distinct (source, sender, reason) so a batch of different
        // risky messages don't overwrite each other's tray entry, while re-checking the
        // exact same message twice (e.g. after a restart) just updates its own.
        val notificationId = (source.wireValue + sender + reason).hashCode()
        manager.notify(notificationId, notification)
    }

    private fun ensureChannel(context: Context) {
        // minSdk is 26 (== Build.VERSION_CODES.O), so notification channels always exist
        // on every supported device -- no version-gate needed. Creating a channel with an
        // id that already exists is a no-op, so this is safe to call before every notify().
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Risk alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts for SMS/email messages ScamGuard flags as medium or high risk."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
