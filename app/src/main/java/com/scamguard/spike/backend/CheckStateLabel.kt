package com.scamguard.spike.backend

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.scamguard.spike.ui.theme.LocalRiskColors

/** Shared risk-verdict rendering used by both the SMS and Email message cards. */
@Composable
fun CheckStateLabel(state: CheckState) {
    when (state) {
        is CheckState.Idle -> Unit
        is CheckState.Checking -> RiskChip(
            text = "Checking with ScamGuard…",
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        )
        is CheckState.Done -> {
            val (container, onContainer) = riskChipColors(state.riskLevel)
            RiskChip(
                text = "${state.riskLevel.uppercase()} RISK — ${state.reason}",
                containerColor = container,
                contentColor = onContainer
            )
        }
        is CheckState.Failed -> RiskChip(
            text = "Check failed: ${state.message}",
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Small colored badge instead of plain inline colored text -- the single highest-impact,
 * lowest-effort visual change in the app, since this is the one element every message card
 * (SMS and Email both) always shows.
 */
@Composable
private fun RiskChip(text: String, containerColor: Color, contentColor: Color) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * High/medium/low mapped to MaterialTheme roles wherever one already fits semantically
 * (high = the standard M3 "error" role; M3 has no built-in warning/success pair, so
 * medium/low come from LocalRiskColors instead -- see ui/theme/Theme.kt).
 */
@Composable
private fun riskChipColors(riskLevel: String): Pair<Color, Color> {
    val riskColors = LocalRiskColors.current
    return when (riskLevel.lowercase()) {
        "high" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "medium" -> riskColors.mediumContainer to riskColors.onMediumContainer
        "low" -> riskColors.lowContainer to riskColors.onLowContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
}
