package com.scamguard.spike.sms

/** One SMS message as read from the device. Kept local to the SMS module on purpose. */
data class SmsMessageItem(
    val sender: String,
    val timestampMillis: Long,
    val body: String
)
