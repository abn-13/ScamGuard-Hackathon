package com.scamguard.spike.email

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ApiException
import com.scamguard.spike.backend.CheckState
import com.scamguard.spike.backend.CheckStateLabel
import com.scamguard.spike.backend.MessageSource
import com.scamguard.spike.backend.ScamGuardApiClient
import com.scamguard.spike.ui.theme.ScamGuardSpikeTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

class EmailListActivity : ComponentActivity() {

    companion object {
        // How many of the most-recent fetched emails get auto-checked per fetchEmails()
        // call -- see loadInbox()'s equivalent in SmsListActivity for why: a real inbox
        // can have plenty of pre-existing mail the user already dealt with, and checking
        // all of it at once is needless backend load for messages that aren't actionable
        // "new" protection anyway.
        private const val INITIAL_CHECK_LIMIT = 10
    }

    private var accessToken by mutableStateOf<String?>(null)
    private var signedInEmail by mutableStateOf<String?>(null)
    private var isLoading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private val emails = mutableStateListOf<EmailMessageItem>()

    // Keyed on EmailMessageItem's own equals() (not checkState -- see that class), so a
    // message already given a verdict keeps it across fetchEmails() re-fetches instead of
    // being re-sent to the backend every "Refresh emails" tap. Only in-memory: a fresh
    // process re-checks everything once, which is fine for this spike.
    private val checkedResults = mutableMapOf<EmailMessageItem, CheckState.Done>()

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
                        onAuthorize = { startAuthorization() },
                        onRefresh = { accessToken?.let { fetchEmails(it) } }
                    )
                }
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
                items.take(INITIAL_CHECK_LIMIT).forEach { checkEmail(it) }
            } catch (e: Exception) {
                errorMessage = "Failed to fetch emails: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    /** Task 4: POST this already-fetched email (with its on-device is_known_sender) to the backend. */
    private fun checkEmail(item: EmailMessageItem) {
        checkedResults[item]?.let {
            item.checkState.value = it
            return
        }
        item.checkState.value = CheckState.Checking
        lifecycleScope.launch {
            val result = ScamGuardApiClient.checkMessage(
                source = MessageSource.EMAIL,
                sender = item.sender,
                bodyText = item.bodyText,
                subject = item.subject,
                isKnownSender = item.isKnownSender,
                receivedAtMillis = item.timestampMillis,
                replyTo = item.replyTo,
                authenticationResults = item.authenticationResults
            )
            item.checkState.value = result
            if (result is CheckState.Done) checkedResults[item] = result
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
    onAuthorize: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (signedInEmail == null) {
            Text("Authorize Gmail access to fetch recent messages via the Gmail API.")
            Button(onClick = onAuthorize, enabled = !isLoading) {
                Text(if (isLoading) "Waiting..." else "Authorize Gmail access")
            }
            errorMessage?.let { Text(it) }
            return@Column
        }

        Text("Signed in as $signedInEmail")
        Button(onClick = onRefresh, enabled = !isLoading) {
            Text(if (isLoading) "Loading..." else "Refresh emails")
        }
        errorMessage?.let { Text(it) }
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(emails) { email ->
                EmailMessageCard(email)
            }
        }
    }
}

@Composable
private fun EmailMessageCard(email: EmailMessageItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = email.sender)
            Text(text = formatTimestamp(email.timestampMillis))
            Text(text = email.subject)
            Text(text = email.snippet)
            CheckStateLabel(email.checkState.value)
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
