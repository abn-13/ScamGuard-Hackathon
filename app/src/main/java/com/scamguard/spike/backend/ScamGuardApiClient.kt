package com.scamguard.spike.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Matches `MessageSource` in the backend's `app/models.py`. */
enum class MessageSource(val wireValue: String) {
    SMS("sms"),
    EMAIL("email")
}

/**
 * UI-facing state of one message's backend check, held per-item so each row in the list
 * can render its own progress/result independently.
 */
sealed class CheckState {
    data object Idle : CheckState()
    data object Checking : CheckState()
    data class Done(val riskLevel: String, val reason: String) : CheckState()
    data class Failed(val message: String) : CheckState()
}

/**
 * Talks to the backend's `POST /check-message` (see `backend/app/main.py` +
 * `backend/app/schemas.py::IncomingMessage`). Plain HttpURLConnection + org.json --
 * both already on the Android platform -- so this doesn't need a new HTTP dependency.
 */
object ScamGuardApiClient {

    suspend fun checkMessage(
        source: MessageSource,
        sender: String,
        bodyText: String,
        subject: String? = null,
        isKnownSender: Boolean,
        receivedAtMillis: Long
    ): CheckState = withContext(Dispatchers.IO) {
        try {
            val url = URL("${BackendConfig.BASE_URL}/check-message")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json")
            }

            val payload = JSONObject().apply {
                put("user_id", BackendConfig.USER_ID)
                put("source", source.wireValue)
                put("sender", sender)
                put("body_text", bodyText)
                if (!subject.isNullOrBlank()) put("subject", subject)
                put("is_known_sender", isKnownSender)
                put("received_at", Instant.ofEpochMilli(receivedAtMillis).toString())
            }

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                val detail = runCatching { JSONObject(responseBody).optString("detail") }.getOrNull()
                return@withContext CheckState.Failed(
                    if (!detail.isNullOrBlank()) detail else "HTTP $responseCode"
                )
            }

            val json = JSONObject(responseBody)
            CheckState.Done(
                riskLevel = json.getString("risk_level"),
                reason = json.getString("reason")
            )
        } catch (e: Exception) {
            CheckState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
