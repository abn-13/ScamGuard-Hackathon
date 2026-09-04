package com.scamguard.spike.sms

import android.Manifest
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Telephony
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
import com.scamguard.spike.ui.theme.ScamGuardSpikeTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SmsListActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS
    )

    private var permissionGranted by mutableStateOf(false)
    private val messages = mutableStateListOf<SmsMessageItem>()
    private var receiver: SmsReceiver? = null

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

    override fun onStart() {
        super.onStart()
        if (permissionGranted) registerLiveReceiver()
    }

    override fun onStop() {
        super.onStop()
        receiver?.let { runCatching { unregisterReceiver(it) } }
        receiver = null
    }

    private fun hasPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun loadInbox() {
        messages.clear()
        messages.addAll(SmsReader.readInbox(this))
        registerLiveReceiver()
    }

    private fun registerLiveReceiver() {
        if (receiver != null) return
        val newReceiver = SmsReceiver { incoming ->
            // New messages first, matching the DESC sort loadInbox() already applied.
            messages.addAll(0, incoming)
        }
        ContextCompat.registerReceiver(
            this,
            newReceiver,
            IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
            // SMS_RECEIVED_ACTION is a protected system broadcast -- only the system can
            // send it, so NOT_EXPORTED (no other app can trigger this receiver) is correct.
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiver = newReceiver
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
            Text("This screen needs READ_SMS and RECEIVE_SMS to read your inbox.")
            Button(onClick = onRequestPermission) {
                Text("Grant SMS permissions")
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
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
