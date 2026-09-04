package com.scamguard.spike.email

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thin wrapper around the generated Gmail REST client. Fetches recent messages via
 * users.messages.list, which pulls from the account's full mail history -- not just mail
 * that arrives after the app is installed -- proving historical access, not just push.
 */
object GmailApiClient {

    private const val MAX_RESULTS = 25L

    private fun buildService(accessToken: String): Gmail {
        val credential = GoogleCredential.Builder().build().setAccessToken(accessToken)
        return Gmail.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("ScamGuard Spike")
            .build()
    }

    /** Returns the signed-in address plus recent messages, given a Gmail-scoped access token. */
    suspend fun fetchProfileAndRecentEmails(accessToken: String): Pair<String, List<EmailMessageItem>> =
        withContext(Dispatchers.IO) {
            val service = buildService(accessToken)

            val profileEmail = service.users().getProfile("me").execute().emailAddress
                ?: "(unknown)"

            val messageRefs = service.users().messages().list("me")
                .setMaxResults(MAX_RESULTS)
                .execute()
                .messages.orEmpty()

            val items = messageRefs.map { ref ->
                val full = service.users().messages().get("me", ref.id)
                    .setFormat("metadata")
                    .setMetadataHeaders(listOf("From", "Subject"))
                    .execute()

                val headers = full.payload?.headers.orEmpty()
                EmailMessageItem(
                    sender = headers.find { it.name == "From" }?.value ?: "(unknown)",
                    subject = headers.find { it.name == "Subject" }?.value ?: "(no subject)",
                    snippet = full.snippet ?: "",
                    timestampMillis = full.internalDate ?: 0L
                )
            }

            profileEmail to items
        }
}
