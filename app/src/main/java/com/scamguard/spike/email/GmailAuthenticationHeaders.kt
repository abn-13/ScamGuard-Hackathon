package com.scamguard.spike.email

/**
 * Conservative filter for outer headers returned by the authenticated Gmail API.
 * An authserv-id is not a signature. This still relies on Gmail's receiver-side
 * header sanitization, and must never be used on a pasted/forwarded message body.
 */
internal object GmailAuthenticationHeaders {
    private val receiver = Regex("mx\\.google\\.com(?:\\s+1)?", RegexOption.IGNORE_CASE)

    fun select(headers: List<Pair<String, String>>): String? {
        val candidates = headers.filter { (name, value) ->
            name.equals("Authentication-Results", ignoreCase = true) &&
                value.length <= 16384 && value.contains(';') &&
                receiver.matches(value.substringBefore(';').trim())
        }
        // Multiple claims from the same service are ambiguous, even if identical.
        return candidates.singleOrNull()?.second
    }
}
