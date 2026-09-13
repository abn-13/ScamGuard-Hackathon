package com.scamguard.spike.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = BackgroundDark,
    onBackground = OnBackgroundDark,
    surface = BackgroundDark,
    onSurface = OnBackgroundDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = BackgroundLight,
    onBackground = OnBackgroundLight,
    surface = BackgroundLight,
    onSurface = OnBackgroundLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight
)

/**
 * Risk-verdict colors Material3's default ColorScheme has no role for (no built-in
 * "warning"/"success" pair) -- everything else CheckStateLabel needs (high risk, checking,
 * failed) reuses existing MaterialTheme.colorScheme roles directly instead of duplicating
 * them here. Light/dark instances provided by ScamGuardSpikeTheme below, alongside
 * MaterialTheme's own colorScheme, so both switch together.
 */
data class RiskColors(
    val mediumContainer: Color,
    val onMediumContainer: Color,
    val lowContainer: Color,
    val onLowContainer: Color
)

private val LightRiskColors = RiskColors(
    mediumContainer = RiskMediumContainerLight,
    onMediumContainer = RiskOnMediumContainerLight,
    lowContainer = RiskLowContainerLight,
    onLowContainer = RiskOnLowContainerLight
)

private val DarkRiskColors = RiskColors(
    mediumContainer = RiskMediumContainerDark,
    onMediumContainer = RiskOnMediumContainerDark,
    lowContainer = RiskLowContainerDark,
    onLowContainer = RiskOnLowContainerDark
)

val LocalRiskColors = staticCompositionLocalOf { LightRiskColors }

@Composable
fun ScamGuardSpikeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Was `true`: on API 31+ that makes every device use a wallpaper-derived Material You
    // scheme, completely overriding the custom palette below -- meaning the deliberate
    // blue/teal brand would never actually render on any modern device/emulator, and two
    // demo devices with different wallpapers would show two different, uncontrolled color
    // schemes. Defaulting to `false` is what makes the palette below actually the one
    // people see; callers can still opt back into Material You explicitly if ever wanted.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val riskColors = if (darkTheme) DarkRiskColors else LightRiskColors

    CompositionLocalProvider(LocalRiskColors provides riskColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
