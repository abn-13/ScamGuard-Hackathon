package com.scamguard.spike.email

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ApiException
import com.scamguard.spike.backend.CheckState
import com.scamguard.spike.backend.CheckStateLabel
import com.scamguard.spike.backend.CheckedMessageStore
import com.scamguard.spike.backend.MessageSource
import com.scamguard.spike.ui.GateCard
import com.scamguard.spike.ui.ScreenHeader
import com.scamguard.spike.ui.theme.ScamGuardSpikeTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-screen inbox view. All actual checking happens in the background, on a schedule --
 * [PollEmailWorker] polls every 15 minutes whether this screen is open or not. This screen
 * never calls the backend itself; it only reads whatever PollEmailWorker has already
 * written to the shared [CheckedMessageStore] and displays it -- opening or refreshing this
 * screen is purely a local cache read, so it can't double-fire a guardian alert for
 * something the background worker already handled, and it stays instant regardless of
 * backend latency.
 */
class EmailListActivity : ComponentActivity() {

    private var accessToken by mutableStateOf<String?>(null)
    private var signedInEmail by mutableStateOf<String?>(null)
    private var isLoading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private val emails = mutableStateListOf<EmailMessageItem>()

    // Durable, not in-memory: PollEmailWorker can check an email and write its result here
    // before this Activity ever runs in this process. Reading from the same store means a
    // "Refresh emails" tap picks up background results instead of re-checking anything.
    private val checkedMessages by lazy { CheckedMessageStore.getInstance(this) }

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            isLoading = false
            errorMessage = "Gmail authorization was cancelled"
            return@registerForActivityResult
        }
        try {
            val authResult = GoogleAuthHelper.authorizationClient(this)
                .getAuthorizationResultFromIntent(data)
            onAuthorized(authResult.accessToken)
        } catch (e: ApiException) {
            isLoading = false
            errorMessage = "Authorization failed (status ${e.statusCode})"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ScamGuardSpikeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    EmailScreen(
                        modifier = Modifier.padding(innerPadding),
                        signedInEmail = signedInEmail,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        emails = emails,
                        onBack = { finish() },
                        onAuthorize = { startAuthorization() },
                        onRefresh = { accessToken?.let { fetchEmails(it) } }
                    )
                }
            }
        }

        trySilentAuthorization()
    }

    // accessToken/signedInEmail are plain in-memory fields -- a fresh EmailListActivity
    // instance (every time this screen is re-entered from Home) starts with neither, which
    // used to mean re-tapping "Authorize Gmail access" on every visit even though Google
    // already remembers the grant. Try the same zero-UI path PollEmailWorker uses for its
    // background checks before falling back to showing that button -- most re-visits should
    // never see it at all now.
    private fun trySilentAuthorization() {
        isLoading = true
        lifecycleScope.launch {
            val token = GoogleAuthHelper.silentAccessTokenOrNull(this@EmailListActivity)
            if (token != null) {
                onAuthorized(token)
            } else {
                // No prior grant, it was revoked, or the silent call failed -- any of
                // those need the interactive "Authorize Gmail access" button, not an
                // error message (this was an unprompted background attempt).
                isLoading = false
            }
        }
    }

    private fun startAuthorization() {
        errorMessage = null
        isLoading = true
        val request = GoogleAuthHelper.buildAuthorizationRequest()
        GoogleAuthHelper.authorizationClient(this).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pendingIntent = result.pendingIntent
                    if (pendingIntent == null) {
                        isLoading = false
                        errorMessage = "No way to complete authorization"
                        return@addOnSuccessListener
                    }
                    consentLauncher.launch(
                        IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                    )
                } else {
                    onAuthorized(result.accessToken)
                }
            }
            .addOnFailureListener { e ->
                isLoading = false
                errorMessage = "Authorization failed: ${e.message}"
            }
    }

    private fun onAuthorized(token: String?) {
        if (token == null) {
            isLoading = false
            errorMessage = "No access token returned"
            return
        }
        accessToken = token
        fetchEmails(token)
    }

    private fun fetchEmails(token: String) {
        isLoading = true
        errorMessage = null
        lifecycleScope.launch {
            try {
                val (email, items) = GmailApiClient.fetchProfileAndRecentEmails(token)
                signedInEmail = email
                emails.clear()
                emails.addAll(items)
                // Detection happens purely in the background (PollEmailWorker, every 15
                // minutes) -- fetching/refreshing this list never calls the backend itself,
                // it only shows whatever's already been checked and cached.
                items.forEach { showCachedResult(it) }
            } catch (e: Exception) {
                errorMessage = "Failed to fetch emails: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /** Reads this email's already-computed verdict from the shared cache, if any -- never
     * calls the backend. Leaves checkState at its default (CheckState.Idle) for a message
     * PollEmailWorker hasn't reached yet; it'll show up here on the next screen refresh
     * after that background poll runs. */
    private fun showCachedResult(item: EmailMessageItem) {
        lifecycleScope.launch {
            val key = CheckedMessageStore.keyFor(MessageSource.EMAIL, item.sender, item.timestampMillis)
            val existing = withContext(Dispatchers.IO) { checkedMessages.get(key) }
            if (existing != null) {
                item.checkState.value = CheckState.Done(existing.riskLevel, existing.reason)
            }
        }
    }
}

@Composable
private fun EmailScreen(
    modifier: Modifier = Modifier,
    signedInEmail: String?,
    isLoading: Boolean,
    errorMessage: String?,
    emails: List<EmailMessageItem>,
    onBack: () -> Unit,
    onAuthorize: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "Email reading", icon = Icons.Filled.Email, onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (signedInEmail == null) {
                GateCard(
                    icon = Icons.Filled.Email,
                    message = "Authorize Gmail access to fetch recent messages via the Gmail API.",
                    buttonLabel = "Authorize Gmail access",
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    onClick = onAuthorize
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Signed in as $signedInEmail",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(onClick = onRefresh, enabled = !isLoading) {
                    Text(if (isLoading) "Loading..." else "Refresh emails")
                }
            }
            errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(emails) { email ->
                    EmailMessageCard(email)
                }
            }
        }
    }
}

@Composable
private fun EmailMessageCard(email: EmailMessageItem) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.tertiaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Email, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = email.sender, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = formatTimestamp(email.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(text = email.subject, style = MaterialTheme.typography.titleSmall)
                Text(text = email.snippet, style = MaterialTheme.typography.bodyMedium)
                CheckStateLabel(email.checkState.value)
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
