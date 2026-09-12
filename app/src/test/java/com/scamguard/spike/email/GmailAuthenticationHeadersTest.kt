package com.scamguard.spike.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GmailAuthenticationHeadersTest {
    private val trusted = "mx.google.com; dmarc=pass header.from=paypal.com"

    @Test fun selectsReceiverInsteadOfFirstHeader() {
        assertEquals(trusted, GmailAuthenticationHeaders.select(listOf(
            "Authentication-Results" to "attacker.example; dmarc=pass",
            "aUtHeNtIcAtIoN-rEsUlTs" to trusted
        )))
    }

    @Test fun rejectsMultipleReceiverClaims() {
        assertNull(GmailAuthenticationHeaders.select(listOf(
            "Authentication-Results" to trusted,
            "Authentication-Results" to trusted
        )))
    }

    @Test fun ignoresForwardedArcAndLookalikeServiceValues() {
        assertNull(GmailAuthenticationHeaders.select(listOf(
            "ARC-Authentication-Results" to trusted,
            "Subject" to trusted,
            "Authentication-Results" to "mx.google.com.attacker.example; dmarc=pass"
        )))
    }

    @Test fun supportsReceiverVersionAndMissingHeaders() {
        val versioned = "MX.GOOGLE.COM 1; dmarc=pass header.from=paypal.com"
        assertEquals(versioned, GmailAuthenticationHeaders.select(listOf(
            "Authentication-Results" to versioned
        )))
        assertNull(GmailAuthenticationHeaders.select(emptyList()))
    }
}
