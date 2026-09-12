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
        userId: Long,
        source: MessageSource,
        sender: String,
        bodyText: String,
        subject: String? = null,
        isKnownSender: Boolean,
        receivedAtMillis: Long,
        // Task 3b evidence -- email only, both optional/backward-compatible on the backend.
        replyTo: String? = null,
        authenticationResults: String? = null
    ): CheckState {
        val payload = JSONObject().apply {
            put("user_id", userId)
            put("source", source.wireValue)
            put("sender", sender)
            put("body_text", bodyText)
            if (!subject.isNullOrBlank()) put("subject", subject)
            if (!replyTo.isNullOrBlank()) put("reply_to", replyTo)
            if (!authenticationResults.isNullOrBlank()) {
                put("authentication_results", authenticationResults)
            }
            put("is_known_sender", isKnownSender)
            put("received_at", Instant.ofEpochMilli(receivedAtMillis).toString())
        }

        // Was 20s: with checks now running genuinely concurrently (see agent.py), a
        // full-inbox refresh can fire a dozen+ real Bedrock calls at once, and each one
        // (LLM turn + tool-use round-trips) can legitimately take well over 20s under
        // that contention -- not a hang, just real latency. Backend log confirmed several
        // calls were finishing successfully with valid verdicts after the old 20s timeout
        // had already given up on them.
        return postJson("/check-message", payload, readTimeoutMillis = 60_000).fold(
            onSuccess = { json ->
                CheckState.Done(
                    riskLevel = json.getString("risk_level"),
                    reason = json.getString("reason")
                )
            },
            onFailure = { e -> CheckState.Failed(e.message ?: e.javaClass.simpleName) }
        )
    }

    /** Registration step 1: create the protected person's row. Returns their new `id`. */
    suspend fun registerUser(username: String, phoneNumber: String, gmail: String): Result<Long> {
        val payload = JSONObject().apply {
            put("username", username)
            put("phone_number", phoneNumber)
            put("gmail", gmail)
        }
        return postJson("/users", payload).map { it.getLong("id") }
    }

    /** Registration step 2 (optional): register a family member to alert on risky messages. */
    suspend fun registerFamilyMember(
        userId: Long,
        username: String,
        phoneNumber: String
    ): Result<Unit> {
        val payload = JSONObject().apply {
            put("user_id", userId)
            put("username", username)
            put("phone_number", phoneNumber)
        }
        return postJson("/family-members", payload).map { }
    }

    /** Shared POST-JSON plumbing -- every backend call here follows the same shape. */
    private suspend fun postJson(
        path: String,
        payload: JSONObject,
        readTimeoutMillis: Int = 10_000
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val url = URL("${BackendConfig.BASE_URL}$path")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = readTimeoutMillis
                setRequestProperty("Content-Type", "application/json")
            }

            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseBody = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseCode !in 200..299) {
                val detail = runCatching { JSONObject(responseBody).optString("detail") }.getOrNull()
                return@withContext Result.failure(
                    Exception(if (!detail.isNullOrBlank()) detail else "HTTP $responseCode")
                )
            }

            Result.success(JSONObject(responseBody))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
