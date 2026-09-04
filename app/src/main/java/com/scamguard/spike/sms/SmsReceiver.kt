package com.scamguard.spike.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Registered dynamically (see [SmsListActivity]) rather than in the manifest, since the
 * spike only needs to prove live capture while the screen is open.
 */
class SmsReceiver(
    private val onMessagesReceived: (List<SmsMessageItem>) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val received = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            ?.map { smsMessage ->
                SmsMessageItem(
                    sender = smsMessage.originatingAddress ?: "(unknown)",
                    timestampMillis = smsMessage.timestampMillis,
                    body = smsMessage.messageBody ?: ""
                )
            }
            .orEmpty()

        if (received.isNotEmpty()) {
            onMessagesReceived(received)
        }
    }
}
