package com.scamguard.spike.sms

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/**
 * Computes `is_known_sender` for SMS: is this address already a saved contact?
 * Requires READ_CONTACTS (requested alongside READ_SMS in SmsListActivity).
 */
object ContactLookup {

    fun isKnownSender(context: Context, phoneNumber: String): Boolean {
        if (phoneNumber.isBlank()) return false
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup._ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } ?: false
        } catch (e: SecurityException) {
            // Permission not granted (yet) -- treat as unknown rather than crash.
            false
        }
    }
}
