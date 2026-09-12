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
import com.scamguard.spike.backend.BackgroundMonitoring
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

    // No-op callback either way: RiskNotifier/GuardianAlerter each re-check their own
    // permission (areNotificationsEnabled() / SEND_SMS) before acting, so a denial here
    // just means those checks silently skip rather than crash.
    private val alertPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (!UserSession.isRegistered(this)) {
            startActivity(Intent(this, RegistrationActivity::class.java))
            finish()
            return
        }

        BackgroundMonitoring.schedule(this)
        requestMissingAlertPermissions()

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

    // POST_NOTIFICATIONS is only a runtime permission from API 33 (Tiramisu) on -- below
    // that, notifications just work once the channel exists. SEND_SMS is only asked for at
    // all if a guardian was actually registered (see RegistrationActivity/UserSession) --
    // no point prompting for it otherwise. Asked here, once registration is confirmed, so
    // both are covered before the SMS/Email modules can first trigger an alert.
    private fun requestMissingAlertPermissions() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (UserSession.getGuardianPhoneNumber(this@MainActivity) != null &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.SEND_SMS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.SEND_SMS)
            }
        }
        if (missing.isNotEmpty()) {
            alertPermissionLauncher.launch(missing.toTypedArray())
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
