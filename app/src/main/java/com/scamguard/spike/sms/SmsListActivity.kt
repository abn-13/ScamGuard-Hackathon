package com.scamguard.spike.sms

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material.icons.filled.Sms
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.scamguard.spike.backend.BackgroundMonitoring
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
 * [PollSmsWorker] polls every 15 minutes whether this screen is open or not (see that class
 * for why: SMS_RECEIVED broadcast delivery to this app doesn't work on this platform,
 * confirmed by testing, so there's no live-push path here). This screen never calls the
 * backend itself; it only reads whatever PollSmsWorker has already written to the shared
 * [CheckedMessageStore] and displays it -- opening or refreshing this screen is purely a
 * local cache read, so it can't double-fire a guardian alert for something the background
 * worker already handled, and it stays instant regardless of backend latency.
 */
class SmsListActivity : ComponentActivity() {

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
    private val checkedMessages by lazy { CheckedMessageStore.getInstance(this) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionGranted = results.values.all { it }
        if (permissionGranted) {
            loadInbox()
            BackgroundMonitoring.triggerInitialSmsCheck(this)
        }
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
                        onBack = { finish() },
                        onRequestPermission = { permissionLauncher.launch(requiredPermissions) },
                        onRefresh = { loadInbox() }
                    )
                }
            }
        }

        if (permissionGranted) {
            loadInbox()
            // Covers the "permission was already granted on a previous visit" case --
            // triggerInitialSmsCheck is a no-op past the very first successful call
            // (self-gated by its own persisted flag), so calling it here every time this
            // screen opens is cheap and correct, not a repeat trigger.
            BackgroundMonitoring.triggerInitialSmsCheck(this)
        }
    }

    private fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadInbox() {
        messages.clear()
        val inbox = SmsReader.readInbox(this)
        messages.addAll(inbox)
        Log.d(TAG, "loadInbox: read ${inbox.size} message(s) from device inbox")
        // Detection happens purely in the background (PollSmsWorker, every 15 minutes) --
        // loading/refreshing this screen never calls the backend itself, it only shows
        // whatever's already been checked and cached.
        inbox.forEach { showCachedResult(it) }
    }

    /** Reads this message's already-computed verdict from the shared cache, if any -- never
     * calls the backend. Leaves checkState at its default (CheckState.Idle) for a message
     * PollSmsWorker hasn't reached yet; it'll show up here on the next screen refresh after
     * that background poll runs. */
    private fun showCachedResult(item: SmsMessageItem) {
        lifecycleScope.launch {
            val key = CheckedMessageStore.keyFor(MessageSource.SMS, item.sender, item.timestampMillis)
            val existing = withContext(Dispatchers.IO) { checkedMessages.get(key) }
            if (existing != null) {
                Log.d(TAG, "showCachedResult: cache HIT for $key -> ${existing.riskLevel}")
                item.checkState.value = CheckState.Done(existing.riskLevel, existing.reason)
            } else {
                Log.d(TAG, "showCachedResult: cache MISS for $key")
            }
        }
    }

    private companion object {
        const val TAG = "ScamGuard"
    }
}

@Composable
private fun SmsScreen(
    modifier: Modifier = Modifier,
    permissionGranted: Boolean,
    messages: List<SmsMessageItem>,
    onBack: () -> Unit,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "SMS reading", icon = Icons.Filled.Sms, onBack = onBack)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (!permissionGranted) {
                GateCard(
                    icon = Icons.Filled.Sms,
                    message = "This screen needs SMS and Contacts permission to read your inbox and check senders.",
                    buttonLabel = "Grant permissions",
                    onClick = onRequestPermission
                )
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${messages.size} message(s)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                FilledTonalButton(onClick = onRefresh) {
                    Text("Refresh inbox")
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(messages) { message ->
                    SmsMessageCard(message)
                }
            }
        }
    }
}

@Composable
private fun SmsMessageCard(message: SmsMessageItem) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Sms, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = message.sender, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = formatTimestamp(message.timestampMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(text = message.body, style = MaterialTheme.typography.bodyMedium)
                CheckStateLabel(message.checkState.value)
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
