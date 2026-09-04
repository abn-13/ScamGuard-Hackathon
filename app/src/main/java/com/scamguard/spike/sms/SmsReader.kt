package com.scamguard.spike.sms

import android.content.Context
import android.provider.Telephony

/** Reads existing messages from the device's SMS inbox via the content provider. */
object SmsReader {

    private const val MAX_MESSAGES = 50

    fun readInbox(context: Context): List<SmsMessageItem> {
        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.DATE,
            Telephony.Sms.BODY
        )

        val messages = mutableListOf<SmsMessageItem>()
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)

            while (cursor.moveToNext() && messages.size < MAX_MESSAGES) {
                messages += SmsMessageItem(
                    sender = cursor.getString(addressIndex) ?: "(unknown)",
                    timestampMillis = cursor.getLong(dateIndex),
                    body = cursor.getString(bodyIndex) ?: ""
                )
            }
        }
        return messages
    }
}
