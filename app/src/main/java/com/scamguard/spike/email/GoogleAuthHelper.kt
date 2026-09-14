package com.scamguard.spike.email

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.services.gmail.GmailScopes
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Wraps Google's Authorization Client -- the current replacement for the legacy
 * GoogleSignInClient.requestScopes() flow -- to request the Gmail readonly scope.
 * This is a cloud OAuth flow, not an Android runtime permission, kept fully separate
 * from the sms/ package on purpose.
 */
object GoogleAuthHelper {

    private val gmailReadonlyScope = Scope(GmailScopes.GMAIL_READONLY)

    fun authorizationClient(context: Context): AuthorizationClient =
        Identity.getAuthorizationClient(context)

    fun buildAuthorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(gmailReadonlyScope))
            .build()

    /**
     * Tries to get a fresh access token with zero UI, relying on a scope grant from an
     * earlier interactive authorize() call. Confirmed via direct testing (not just API
     * docs) that this succeeds silently as long as the user has granted Gmail access at
     * least once before -- originally written for PollEmailWorker's no-Activity background
     * context, and just as useful in EmailListActivity.onCreate so re-opening the screen
     * doesn't force the user through the consent screen again. Returns null if there's no
     * prior grant, it was revoked, or the call fails -- all of which need the interactive
     * flow (a UI a background Worker can't show, but EmailListActivity's own
     * "Authorize Gmail access" button can) to resolve.
     */
    suspend fun silentAccessTokenOrNull(context: Context): String? {
        val request = buildAuthorizationRequest()
        val client = authorizationClient(context)

        val result = suspendCoroutine<AuthorizationResult?> { cont ->
            client.authorize(request)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        } ?: return null

        // hasResolution() == true means re-consent is needed (revoked, or never granted).
        if (result.hasResolution()) return null
        return result.accessToken
    }
}
