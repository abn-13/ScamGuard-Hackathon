package com.scamguard.spike

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.scamguard.spike.email.EmailListActivity
import com.scamguard.spike.registration.RegistrationActivity
import com.scamguard.spike.registration.UserSession
import com.scamguard.spike.sms.SmsListActivity
import com.scamguard.spike.ui.theme.ScamGuardSpikeTheme

/**
 * Home screen only. It does not touch SMS or Gmail itself -- it just launches the two
 * independent feasibility-spike screens, each of which owns its own data access mechanism.
 */
class MainActivity : ComponentActivity() {

    // No-op callback either way: RiskNotifier checks NotificationManagerCompat's
    // areNotificationsEnabled() before every notify() call, so a denial here just means
    // those checks silently skip showing a notification rather than crashing.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!UserSession.isRegistered(this)) {
            startActivity(Intent(this, RegistrationActivity::class.java))
            finish()
            return
        }

        // POST_NOTIFICATIONS is only a runtime permission from API 33 (Tiramisu) on --
        // below that, notifications just work once the channel exists. Asked here, once
        // registration is confirmed, so it's covered before either the SMS or Email
        // module first triggers a risky-message notification via RiskNotifier.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            ScamGuardSpikeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeScreen(
                        modifier = Modifier.padding(innerPadding),
                        onTestSms = { startActivity(Intent(this, SmsListActivity::class.java)) },
                        onTestEmail = { startActivity(Intent(this, EmailListActivity::class.java)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    modifier: Modifier = Modifier,
    onTestSms: () -> Unit,
    onTestEmail: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ScamGuard — Data Source Feasibility Spike")
        Text("Two independent data sources. Pick one to test.")
        Button(onClick = onTestSms) {
            Text("Test SMS Reading (device permission)")
        }
        Button(onClick = onTestEmail) {
            Text("Test Email Reading (Gmail OAuth)")
        }
    }
}
