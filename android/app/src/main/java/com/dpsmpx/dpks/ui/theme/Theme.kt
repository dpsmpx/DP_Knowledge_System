package com.dpsmpx.dpks.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Brand seed — deep teal, readable on both light and dark surfaces. */
private val Seed = Color(0xFF0B4F60)
private val SeedLight = Color(0xFF4EC4D9)
private val Mist = Color(0xFFE8F3F6)
private val Ink = Color(0xFF0A161A)
private val Conflict = Color(0xFFB3261E)
private val Amber = Color(0xFFB86E00)

private val LightColors = lightColorScheme(
    primary = Seed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC5EAF1),
    onPrimaryContainer = Color(0xFF001F26),
    secondary = Color(0xFF4A636B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE7EF),
    onSecondaryContainer = Color(0xFF051F26),
    tertiary = Color(0xFF5C5B7E),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3DFFF),
    onTertiaryContainer = Color(0xFF191833),
    error = Conflict,
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    background = Color(0xFFF5FAFB),
    onBackground = Ink,
    surface = Color(0xFFF5FAFB),
    onSurface = Ink,
    surfaceVariant = Color(0xFFD9E4E8),
    onSurfaceVariant = Color(0xFF3E494D),
    outline = Color(0xFF6E797D),
    outlineVariant = Color(0xFFBDC8CC),
    inverseSurface = Color(0xFF2B3437),
    inverseOnSurface = Color(0xFFECF2F4),
    inversePrimary = SeedLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEFF5F7),
    surfaceContainer = Color(0xFFE9F0F2),
    surfaceContainerHigh = Color(0xFFE3EBED),
    surfaceContainerHighest = Color(0xFFDDE6E9),
)

private val DarkColors = darkColorScheme(
    primary = SeedLight,
    onPrimary = Color(0xFF003641),
    primaryContainer = Color(0xFF1A5A6A),
    onPrimaryContainer = Color(0xFFC5EAF1),
    secondary = Color(0xFFB1CBD3),
    onSecondary = Color(0xFF1C343B),
    secondaryContainer = Color(0xFF334A52),
    onSecondaryContainer = Color(0xFFCDE7EF),
    tertiary = Color(0xFFC5C2EA),
    onTertiary = Color(0xFF2E2D4D),
    tertiaryContainer = Color(0xFF444465),
    onTertiaryContainer = Color(0xFFE3DFFF),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
    background = Color(0xFF0B1214),
    onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF0B1214),
    onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3E494D),
    onSurfaceVariant = Color(0xFFBDC8CC),
    outline = Color(0xFF879297),
    outlineVariant = Color(0xFF3E494D),
    inverseSurface = Color(0xFFDEE3E5),
    inverseOnSurface = Color(0xFF2B3437),
    inversePrimary = Seed,
    surfaceContainerLowest = Color(0xFF060D0F),
    surfaceContainerLow = Color(0xFF131A1C),
    surfaceContainer = Color(0xFF171E21),
    surfaceContainerHigh = Color(0xFF21282B),
    surfaceContainerHighest = Color(0xFF2C3336),
)

/**
 * Fixed semantic colors for epistemic roles.
 * Do not replace whole screens with these — use chips / badges only.
 */
object EpistemicColors {
    val Fact = Color(0xFF1B7A52)
    val FactContainer = Color(0xFFD1F0E2)
    val Claim = Color(0xFF3D5F70)
    val ClaimContainer = Color(0xFFD5E8F0)
    val Hypothesis = Color(0xFF5C5B7E)
    val HypothesisContainer = Color(0xFFE3DFFF)
    val Unknown = Color(0xFF6B7280)
    val Contested = Conflict
    val ContestedContainer = Color(0xFFF9DEDC)
    val Stale = Amber
    val StaleContainer = Color(0xFFFFE8C2)
}

@Composable
fun DpksTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DpksTypography,
        content = content,
    )
}
