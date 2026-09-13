package com.scamguard.spike.registration

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.scamguard.spike.MainActivity
import com.scamguard.spike.backend.ScamGuardApiClient
import com.scamguard.spike.ui.theme.ScamGuardSpikeTheme
import kotlinx.coroutines.launch

/**
 * Shown right after registration, only when a family member was added and doesn't already
 * have a telegram_chat_id (see RegistrationActivity/backend/app/telegram_link.py). Lets
 * them send the one-time code to the bot, then checks on demand whether it's been picked
 * up yet -- there's no push/webhook here, so nothing links itself automatically.
 */
class TelegramLinkActivity : ComponentActivity() {

    private var isChecking by mutableStateOf(false)
    private var linked by mutableStateOf(false)
    private var statusMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val familyMemberId = intent.getLongExtra(EXTRA_FAMILY_MEMBER_ID, -1L)
        val linkCode = intent.getStringExtra(EXTRA_LINK_CODE).orEmpty()
        val linkUrl = intent.getStringExtra(EXTRA_LINK_URL)
        val guardianUsername = intent.getStringExtra(EXTRA_GUARDIAN_USERNAME).orEmpty()

        setContent {
            ScamGuardSpikeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TelegramLinkScreen(
                        modifier = Modifier.padding(innerPadding),
                        guardianUsername = guardianUsername,
                        linkCode = linkCode,
                        linkUrl = linkUrl,
                        isChecking = isChecking,
                        linked = linked,
                        statusMessage = statusMessage,
                        onOpenTelegram = { url -> openTelegram(url) },
                        onCheckStatus = { checkStatus(familyMemberId) },
                        onContinue = { goToMain() }
                    )
                }
            }
        }
    }

    private fun openTelegram(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { statusMessage = "Couldn't open Telegram -- is it installed?" }
    }

    private fun checkStatus(familyMemberId: Long) {
        if (familyMemberId < 0) return
        isChecking = true
        statusMessage = null
        lifecycleScope.launch {
            val result = ScamGuardApiClient.checkTelegramLink(familyMemberId)
            isChecking = false
            result.fold(
                onSuccess = { isLinked ->
                    linked = isLinked
                    statusMessage = if (isLinked) {
                        "Linked! They'll get Telegram alerts from now on."
                    } else {
                        "Not linked yet -- make sure they've sent the code, then try again."
                    }
                },
                onFailure = { e -> statusMessage = "Check failed: ${e.message}" }
            )
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        const val EXTRA_FAMILY_MEMBER_ID = "family_member_id"
        const val EXTRA_LINK_CODE = "link_code"
        const val EXTRA_LINK_URL = "link_url"
        const val EXTRA_GUARDIAN_USERNAME = "guardian_username"
    }
}

@Composable
private fun TelegramLinkScreen(
    modifier: Modifier = Modifier,
    guardianUsername: String,
    linkCode: String,
    linkUrl: String?,
    isChecking: Boolean,
    linked: Boolean,
    statusMessage: String?,
    onOpenTelegram: (String) -> Unit,
    onCheckStatus: () -> Unit,
    onContinue: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Link $guardianUsername's Telegram (optional)",
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            "They'll also get a Telegram message whenever a risky message is flagged, " +
                "in addition to the SMS alert already set up. Send this code to our bot " +
                "on Telegram to link it -- skip this if you'd rather do it later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (linkUrl != null) {
            Button(onClick = { onOpenTelegram(linkUrl) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open Telegram")
            }
        }
        Text("Code: $linkCode", style = MaterialTheme.typography.titleMedium)

        OutlinedButton(
            onClick = onCheckStatus,
            enabled = !isChecking && !linked,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isChecking) "Checking..." else "I've sent it -- check status")
        }
        if (isChecking) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 4.dp))
        }
        statusMessage?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (linked) {
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Continue")
            }
        } else {
            TextButton(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
                Text("Skip for now")
            }
        }
    }
}
