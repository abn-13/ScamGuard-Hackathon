package com.scamguard.spike.sms

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.scamguard.spike.backend.CheckState
import com.scamguard.spike.backend.CheckStateLabel
import com.scamguard.spike.backend.CheckedMessageStore
import com.scamguard.spike.backend.MessageSource
import com.scamguard.spike.backend.ScamGuardApiClient
import com.scamguard.spike.notifications.GuardianAlerter
import com.scamguard.spike.notifications.RiskNotifier
import com.scamguard.spike.registration.UserSession
import com.scamguard.spike.ui.theme.ScamGuardSpikeTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * On-screen inbox view. This is one of two ways a message gets checked -- the other is
 * [PollSmsWorker], which runs periodically in the background whether this screen is open
 * or not (see that class for why: SMS_RECEIVED broadcast delivery to this app doesn't work
 * on this platform, confirmed by testing, so there's no live-push path here). Both read
 * and write the same [CheckedMessageStore], so whichever gets to a message first is the
 * one that's shown.
 */
class SmsListActivity : ComponentActivity() {

    companion object {
        // How many of the most-recent existing messages get auto-checked on first
        // load/refresh, not the whole inbox -- see loadInbox().
        private const val INITIAL_CHECK_LIMIT = 10
    }

    // READ_CONTACTS is needed to compute is_known_sender (Task 4), alongside the
    // SMS-reading permission the spike already requested.
    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS
    )

    private var permissionGranted by mutableStateOf(false)
    private val messages = mutableStateListOf<SmsMessageItem>()

    // Durable, not in-memory: PollSmsWorker can check a message and write its result here
    // before this Activity ever runs in this process -- e.g. a text arrives while the app
    // is closed. Reading from the same store means a re-check on "Refresh inbox" reuses
    // that result instead of hitting the backend again.
    private val checkedMessages by lazy { CheckedMessageStore(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionGranted = results.values.all { it }
        if (permissionGranted) loadInbox()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        permissionGranted = hasPermissions()

        setContent {
            ScamGuardSpikeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SmsScreen(
                        modifier = Modifier.padding(innerPadding),
                        permissionGranted = permissionGranted,
                        messages = messages,
                        onRequestPermission = { permissionLauncher.launch(requiredPermissions) },
                        onRefresh = { loadInbox() }
                    )
                }
            }
        }

        if (permissionGranted) loadInbox()
    }

    private fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadInbox() {
        messages.clear()
        val inbox = SmsReader.readInbox(this)
        messages.addAll(inbox)
        // Only auto-check the most recent few on load, not the whole inbox: a real
        // install can have dozens of pre-existing messages the user already read and
        // dealt with, and checking every one of them at once is needless backend load
        // for messages that aren't actionable "new" protection anyway. inbox is already
        // DESC by date (see SmsReader), so take() keeps the newest ones. Older messages
        // stay listed with no verdict rather than silently getting one later.
        inbox.take(INITIAL_CHECK_LIMIT).forEach { checkMessage(it) }
    }

    /** Task 4: compute is_known_sender on-device, then POST to the backend and show the verdict. */
    private fun checkMessage(item: SmsMessageItem) {
        item.checkState.value = CheckState.Checking
        lifecycleScope.launch {
            val key = CheckedMessageStore.keyFor(MessageSource.SMS, item.sender, item.timestampMillis)
            val existing = withContext(Dispatchers.IO) { checkedMessages.get(key) }
            if (existing != null) {
                item.checkState.value = CheckState.Done(existing.riskLevel, existing.reason)
                return@launch
            }

            val userId = UserSession.getUserId(this@SmsListActivity)
            if (userId == null) {
                item.checkState.value = CheckState.Failed("Not registered yet")
                return@launch
            }
            val isKnown = withContext(Dispatchers.IO) {
                ContactLookup.isKnownSender(this@SmsListActivity, item.sender)
            }
            val result = ScamGuardApiClient.checkMessage(
                userId = userId,
                source = MessageSource.SMS,
                sender = item.sender,
                bodyText = item.body,
                isKnownSender = isKnown,
                receivedAtMillis = item.timestampMillis
            )
            item.checkState.value = result
            if (result is CheckState.Done) {
                withContext(Dispatchers.IO) {
                    checkedMessages.put(key, result.riskLevel, result.reason)
                }
                RiskNotifier.notifyIfRisky(
                    this@SmsListActivity, MessageSource.SMS, item.sender, result.riskLevel, result.reason
                )
                GuardianAlerter.alertIfRisky(
                    this@SmsListActivity, MessageSource.SMS, item.sender, result.riskLevel, result.reason
                )
            }
        }
    }
}

@Composable
private fun SmsScreen(
    modifier: Modifier = Modifier,
    permissionGranted: Boolean,
    messages: List<SmsMessageItem>,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        if (!permissionGranted) {
            Text("This screen needs SMS and Contacts permission to read your inbox and check senders.")
            Button(onClick = onRequestPermission) {
                Text("Grant permissions")
            }
            return@Column
        }

        Text("${messages.size} message(s)", modifier = Modifier.padding(bottom = 8.dp))
        Button(onClick = onRefresh) {
            Text("Refresh inbox")
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { message ->
                SmsMessageCard(message)
            }
        }
    }
}

@Composable
private fun SmsMessageCard(message: SmsMessageItem) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = message.sender)
            Text(text = formatTimestamp(message.timestampMillis))
            Text(text = message.body)
            CheckStateLabel(message.checkState.value)
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
