package com.scamguard.spike.ui.theme

import androidx.compose.ui.graphics.Color

// Blue/teal "trust" palette -- replaces the unmodified Android Studio template purple.
// Standard Material3 tonal pairs (each XxxContainer/onXxxContainer stays readable at
// standard M3 contrast; no extra accessibility scaling requested for this pass).

// Light scheme
val PrimaryLight = Color(0xFF0B5FA0)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFD3E4FF)
val OnPrimaryContainerLight = Color(0xFF001C38)
val SecondaryLight = Color(0xFF4B6376)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFD3E5F5)
val OnSecondaryContainerLight = Color(0xFF051F2E)
val TertiaryLight = Color(0xFF146C73)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFA0EEF2)
val OnTertiaryContainerLight = Color(0xFF00201F)
val BackgroundLight = Color(0xFFF7FAFC)
val OnBackgroundLight = Color(0xFF171C1F)
val SurfaceVariantLight = Color(0xFFDDE3E9)
val OnSurfaceVariantLight = Color(0xFF41484D)
val OutlineLight = Color(0xFF71787E)

// Dark scheme
val PrimaryDark = Color(0xFF9CC9FF)
val OnPrimaryDark = Color(0xFF00325A)
val PrimaryContainerDark = Color(0xFF004880)
val OnPrimaryContainerDark = Color(0xFFD3E4FF)
val SecondaryDark = Color(0xFFB3CBDF)
val OnSecondaryDark = Color(0xFF1D3547)
val SecondaryContainerDark = Color(0xFF354B5F)
val OnSecondaryContainerDark = Color(0xFFD3E5F5)
val TertiaryDark = Color(0xFF82D3D9)
val OnTertiaryDark = Color(0xFF00373A)
val TertiaryContainerDark = Color(0xFF004F54)
val OnTertiaryContainerDark = Color(0xFFA0EEF2)
val BackgroundDark = Color(0xFF0F1416)
val OnBackgroundDark = Color(0xFFDEE3E6)
val SurfaceVariantDark = Color(0xFF41484D)
val OnSurfaceVariantDark = Color(0xFFC1C7CD)
val OutlineDark = Color(0xFF8B9297)

// Semantic risk colors that Material3 has no built-in role for (no "warning"/"success"
// pair exists in the default color scheme -- high risk reuses colorScheme.errorContainer
// instead, see RiskColors in Theme.kt). Consumed by CheckStateLabel via LocalRiskColors.
val RiskMediumContainerLight = Color(0xFFFFE0B2)
val RiskOnMediumContainerLight = Color(0xFF8A4B00)
val RiskLowContainerLight = Color(0xFFC4EED0)
val RiskOnLowContainerLight = Color(0xFF0B5D1E)

val RiskMediumContainerDark = Color(0xFF5C3D00)
val RiskOnMediumContainerDark = Color(0xFFFFD599)
val RiskLowContainerDark = Color(0xFF0F4620)
val RiskOnLowContainerDark = Color(0xFF9CDBA6)
