package com.scamguard.spike.email

import android.util.Base64
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.model.MessagePart
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
                // "full" (not just "metadata") so we get the actual body to send the
                // backend for judging, not just the From/Subject headers -- and, unlike
                // "metadata" with setMetadataHeaders(...), "full" already returns every
                // header on the message, so Reply-To/Authentication-Results need no
                // extra request.
                val full = service.users().messages().get("me", ref.id)
                    .setFormat("full")
                    .execute()

                val headers = full.payload?.headers.orEmpty()
                val bodyText = extractPlainText(full.payload).ifBlank { full.snippet ?: "" }

                // Task 4's "Gmail thread history" signal for is_known_sender: more than one
                // message in this conversation means there's been back-and-forth already.
                val threadMessageCount = full.threadId?.let { threadId ->
                    service.users().threads().get("me", threadId)
                        .setFormat("minimal")
                        .execute()
                        .messages?.size
                } ?: 1

                EmailMessageItem(
                    sender = headers.find { it.name == "From" }?.value ?: "(unknown)",
                    subject = headers.find { it.name == "Subject" }?.value ?: "(no subject)",
                    snippet = full.snippet ?: "",
                    bodyText = bodyText,
                    timestampMillis = full.internalDate ?: 0L,
                    isKnownSender = threadMessageCount > 1,
                    // Backend Task 3b evidence (both optional/backward-compatible):
                    replyTo = findHeader(headers, "Reply-To"),
                    // Only inspect outer Gmail API headers. Never assume the first
                    // occurrence is trusted; ambiguous receiving-service claims are omitted.
                    authenticationResults = GmailAuthenticationHeaders.select(
                        headers.map { it.name.orEmpty() to it.value.orEmpty() }
                    )
                )
            }

            profileEmail to items
        }

    private fun findHeader(headers: List<com.google.api.services.gmail.model.MessagePartHeader>, name: String): String? =
        headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    /**
     * Walks the MIME part tree for a text/plain body, decoding the URL-safe base64 Gmail
     * uses. Falls back to text/html (tags stripped) if no plain-text part exists, and to
     * "" (the caller then falls back to the snippet) if there's no readable body at all.
     */
    private fun extractPlainText(part: MessagePart?): String {
        if (part == null) return ""

        val data = part.body?.data
        if (part.mimeType == "text/plain" && data != null) {
            return decodeBase64Url(data)
        }

        part.parts?.forEach { child ->
            val text = extractPlainText(child)
            if (text.isNotBlank()) return text
        }

        if (part.mimeType == "text/html" && data != null) {
            return decodeBase64Url(data).replace(Regex("<[^>]*>"), " ")
        }

        return ""
    }

    private fun decodeBase64Url(data: String): String =
        String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
}
