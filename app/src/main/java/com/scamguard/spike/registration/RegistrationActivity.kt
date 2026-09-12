package com.scamguard.spike.registration

import android.content.Intent
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.scamguard.spike.MainActivity
import com.scamguard.spike.backend.ScamGuardApiClient
import com.scamguard.spike.ui.theme.ScamGuardSpikeTheme
import kotlinx.coroutines.launch

/**
 * First-run sign-up: creates the protected person's row on the backend (username, phone
 * number, gmail) and, optionally, one family member to alert on risky messages (username,
 * phone number) via on-device notification and SMS (GuardianAlerter). Shown by MainActivity
 * whenever UserSession has no stored user id yet.
 *
 * Telegram linking (TelegramLinkActivity, backend/app/telegram_link.py) is built and
 * tested but deliberately not wired in here -- SMS + on-device notification already cover
 * alerting, and the team decided not to expose Telegram in the UI for now. The code is
 * left in place rather than deleted in case that changes later.
 */
class RegistrationActivity : ComponentActivity() {

    private var isLoading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScamGuardSpikeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RegistrationScreen(
                        modifier = Modifier.padding(innerPadding),
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onSubmit = ::submit
                    )
                }
            }
        }
    }

    private fun submit(
        username: String,
        phoneNumber: String,
        gmail: String,
        familyUsername: String,
        familyPhoneNumber: String
    ) {
        errorMessage = null
        isLoading = true
        lifecycleScope.launch {
            val userResult = ScamGuardApiClient.registerUser(username, phoneNumber, gmail)
            val userId = userResult.getOrNull()
            if (userId == null) {
                isLoading = false
                errorMessage = "Registration failed: ${userResult.exceptionOrNull()?.message}"
                return@launch
            }
            UserSession.setUserId(this@RegistrationActivity, userId)

            if (familyUsername.isNotBlank() && familyPhoneNumber.isNotBlank()) {
                val familyResult = ScamGuardApiClient.registerFamilyMember(
                    userId, familyUsername, familyPhoneNumber
                )
                val family = familyResult.getOrNull()
                if (family == null) {
                    // The user account already exists at this point -- don't strand them on
                    // the registration screen over a family-member hiccup, just surface it.
                    isLoading = false
                    errorMessage =
                        "Account created, but adding your family member failed: " +
                            "${familyResult.exceptionOrNull()?.message}. You can retry from " +
                            "the backend directly for now."
                    return@launch
                }
                // Cached locally (not just sent to the backend) so GuardianAlerter can send
                // an SMS straight from this device -- including from a background Worker,
                // with no network round trip needed to look the number back up.
                UserSession.setGuardianPhoneNumber(this@RegistrationActivity, familyPhoneNumber)

                // family.telegramLinkCode/telegramLinkUrl are intentionally unused here --
                // see the class doc for why (Telegram linking isn't exposed in the UI).
            }

            startActivity(Intent(this@RegistrationActivity, MainActivity::class.java))
            finish()
        }
    }
}

@Composable
private fun RegistrationScreen(
    modifier: Modifier = Modifier,
    isLoading: Boolean,
    errorMessage: String?,
    onSubmit: (
        username: String,
        phoneNumber: String,
        gmail: String,
        familyUsername: String,
        familyPhoneNumber: String
    ) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var gmail by remember { mutableStateOf("") }
    var familyUsername by remember { mutableStateOf("") }
    var familyPhoneNumber by remember { mutableStateOf("") }

    val canSubmit = username.isNotBlank() && phoneNumber.isNotBlank() && gmail.isNotBlank()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Create your ScamGuard account")
        Text("This is the person whose SMS/Gmail is being protected.")

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = phoneNumber,
            onValueChange = { phoneNumber = it },
            label = { Text("Phone number") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = gmail,
            onValueChange = { gmail = it },
            label = { Text("Gmail address") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        Text("Family member to alert (optional)")
        Text("They'll be notified if a message is flagged risky. You can add this later instead.")

        OutlinedTextField(
            value = familyUsername,
            onValueChange = { familyUsername = it },
            label = { Text("Family member's username") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = familyPhoneNumber,
            onValueChange = { familyPhoneNumber = it },
            label = { Text("Family member's phone number") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        errorMessage?.let { Text(it) }

        Button(
            onClick = {
                onSubmit(username, phoneNumber, gmail, familyUsername, familyPhoneNumber)
            },
            enabled = canSubmit && !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "Creating account..." else "Create account")
        }
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
        }
    }
}
