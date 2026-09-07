package com.scamguard.spike.backend

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared risk-verdict rendering used by both the SMS and Email message cards. */
@Composable
fun CheckStateLabel(state: CheckState) {
    when (state) {
        is CheckState.Idle -> Unit
        is CheckState.Checking -> Text(
            text = "Checking with ScamGuard…",
            modifier = Modifier.padding(top = 4.dp)
        )
        is CheckState.Done -> Text(
            text = "${state.riskLevel.uppercase()} RISK — ${state.reason}",
            color = riskColor(state.riskLevel),
            modifier = Modifier.padding(top = 4.dp)
        )
        is CheckState.Failed -> Text(
            text = "Check failed: ${state.message}",
            color = Color(0xFF9E9E9E),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun riskColor(riskLevel: String): Color = when (riskLevel.lowercase()) {
    "high" -> Color(0xFFC62828)
    "medium" -> Color(0xFFEF6C00)
    "low" -> Color(0xFF2E7D32)
    else -> Color.Unspecified
}
