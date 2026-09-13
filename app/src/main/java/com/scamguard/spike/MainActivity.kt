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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.scamguard.spike.backend.BackgroundMonitoring
import com.scamguard.spike.email.EmailListActivity
import com.scamguard.spike.registration.EditContactActivity
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

    // Held as state (not just read once in onCreate) and refreshed in onResume: coming
    // back from EditContactActivity re-enters here via onResume, not onCreate, so a plain
    // one-time read would keep showing the pre-edit guardian info until the app restarted.
    private var guardianUsername by mutableStateOf<String?>(null)
    private var guardianPhoneNumber by mutableStateOf<String?>(null)

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
                        guardianUsername = guardianUsername,
                        guardianPhoneNumber = guardianPhoneNumber,
                        onTestSms = { startActivity(Intent(this, SmsListActivity::class.java)) },
                        onTestEmail = { startActivity(Intent(this, EmailListActivity::class.java)) },
                        onEditContact = { startActivity(Intent(this, EditContactActivity::class.java)) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        guardianUsername = UserSession.getGuardianUsername(this)
        guardianPhoneNumber = UserSession.getGuardianPhoneNumber(this)
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
    guardianUsername: String?,
    guardianPhoneNumber: String?,
    onTestSms: () -> Unit,
    onTestEmail: () -> Unit,
    onEditContact: () -> Unit
) {
    Column(modifier = modifier.fillMaxSize()) {

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    "ScamGuard",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Text(
                "Everything's being watched",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            FeatureTile(
                title = "Test SMS reading",
                subtitle = "Device permission",
                icon = Icons.Filled.Sms,
                iconContainerColor = MaterialTheme.colorScheme.primaryContainer,
                iconTint = MaterialTheme.colorScheme.primary,
                onClick = onTestSms
            )
            FeatureTile(
                title = "Test email reading",
                subtitle = "Gmail OAuth",
                icon = Icons.Filled.Email,
                iconContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                iconTint = MaterialTheme.colorScheme.tertiary,
                onClick = onTestEmail
            )
            FeatureTile(
                title = "Edit contact info",
                subtitle = if (guardianUsername != null && guardianPhoneNumber != null) {
                    "$guardianUsername · $guardianPhoneNumber"
                } else {
                    "Add a guardian to alert on risky messages"
                },
                icon = Icons.Filled.Edit,
                iconContainerColor = MaterialTheme.colorScheme.surface,
                iconTint = MaterialTheme.colorScheme.secondary,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                elevated = false,
                onClick = onEditContact
            )
        }
    }
}

/**
 * One "elevated tile" entry point -- shared shape for all three Home actions, matching the
 * card-based mockup direction (icon circle, title/subtitle, trailing chevron). The
 * edit-contact tile is the one exception (`elevated = false` + a tinted container instead
 * of a white elevated surface) to visually set it apart as "your data" rather than "run a
 * check", same distinction the mockup drew.
 */
@Composable
private fun FeatureTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconContainerColor: Color,
    iconTint: Color,
    onClick: () -> Unit,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    elevated: Boolean = true
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(iconContainerColor, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = iconTint)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = contentColor)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.75f)
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = contentColor)
        }
    }

    if (elevated) {
        ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor)
        ) { content() }
    }
}
