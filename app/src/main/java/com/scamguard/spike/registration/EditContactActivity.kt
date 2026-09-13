package com.scamguard.spike.registration

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.scamguard.spike.backend.ScamGuardApiClient
import com.scamguard.spike.ui.ScreenHeader
import com.scamguard.spike.ui.theme.ScamGuardSpikeTheme
import kotlinx.coroutines.launch

/**
 * One screen that covers both "add a guardian" (skipped at registration) and "edit the
 * guardian already on file", chosen by whether [UserSession.getGuardianId] is set --
 * avoids a separate entry point for each case. Visual direction: the "card-based" mockup
 * the team picked (tinted header band, circular avatar, an elevated card holding
 * icon-prefixed fields) -- see the design canvas from that round for the reference mocks.
 */
class EditContactActivity : ComponentActivity() {

    private var isLoading by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val isNewGuardian = UserSession.getGuardianId(this) == null
        val initialUsername = UserSession.getGuardianUsername(this).orEmpty()
        val initialPhoneNumber = UserSession.getGuardianPhoneNumber(this).orEmpty()

        setContent {
            ScamGuardSpikeTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    EditContactScreen(
                        modifier = Modifier.padding(innerPadding),
                        isNewGuardian = isNewGuardian,
                        initialUsername = initialUsername,
                        initialPhoneNumber = initialPhoneNumber,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onBack = { finish() },
                        onSave = ::save
                    )
                }
            }
        }
    }

    private fun save(username: String, phoneNumber: String) {
        errorMessage = null
        isLoading = true
        lifecycleScope.launch {
            val guardianId = UserSession.getGuardianId(this@EditContactActivity)
            val result = if (guardianId != null) {
                ScamGuardApiClient.updateFamilyMember(guardianId, username, phoneNumber)
            } else {
                val userId = UserSession.getUserId(this@EditContactActivity)
                if (userId == null) {
                    isLoading = false
                    errorMessage = "Not registered yet."
                    return@launch
                }
                ScamGuardApiClient.registerFamilyMember(userId, username, phoneNumber)
            }

            val guardian = result.getOrNull()
            if (guardian == null) {
                isLoading = false
                errorMessage = "Save failed: ${result.exceptionOrNull()?.message}"
                return@launch
            }

            UserSession.setGuardian(this@EditContactActivity, guardian.id, username, phoneNumber)
            finish()
        }
    }
}

@Composable
private fun EditContactScreen(
    modifier: Modifier = Modifier,
    isNewGuardian: Boolean,
    initialUsername: String,
    initialPhoneNumber: String,
    isLoading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onSave: (username: String, phoneNumber: String) -> Unit
) {
    var username by remember { mutableStateOf(initialUsername) }
    var phoneNumber by remember { mutableStateOf(initialPhoneNumber) }
    val canSave = username.isNotBlank() && phoneNumber.isNotBlank() && !isLoading

    Column(modifier = modifier.fillMaxSize()) {

        ScreenHeader(
            title = if (isNewGuardian) "Add guardian contact" else "Edit contact",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {

            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(76.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(34.dp)
                )
            }

            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "This person gets a text (and Telegram, if linked) when a message " +
                            "is flagged risky.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Name") },
                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it },
                        label = { Text("Phone number") },
                        leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }

            errorMessage?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }

            Column(
                modifier = Modifier.padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = { onSave(username, phoneNumber) },
                    enabled = canSave,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (isLoading) "  Saving..." else "  Save changes",
                    )
                }
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}
