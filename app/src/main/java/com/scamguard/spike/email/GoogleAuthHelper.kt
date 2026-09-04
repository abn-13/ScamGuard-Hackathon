package com.scamguard.spike.email

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.api.services.gmail.GmailScopes

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
}
