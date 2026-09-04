package com.scamguard.spike.email

/** One Gmail message as read via the Gmail API. Kept local to the email module on purpose. */
data class EmailMessageItem(
    val sender: String,
    val timestampMillis: Long,
    val subject: String,
    val snippet: String
)
