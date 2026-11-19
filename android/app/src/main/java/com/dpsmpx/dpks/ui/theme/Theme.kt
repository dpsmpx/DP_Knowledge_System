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

private val TealSeed = Color(0xFF0D3B4C)
private val TealLight = Color(0xFF4A9BB0)
private val AmberWarning = Color(0xFFC47B16)
private val ConflictRed = Color(0xFFB3261E)

private val LightColors = lightColorScheme(
    primary = TealSeed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E8EF),
    onPrimaryContainer = Color(0xFF001F28),
    secondary = Color(0xFF4A636C),
    secondaryContainer = Color(0xFFCDE7F0),
    tertiary = Color(0xFF5B5B7A),
    tertiaryContainer = Color(0xFFE2DFFF),
    error = ConflictRed,
    errorContainer = Color(0xFFF9DEDC),
    background = Color(0xFFF7FAFB),
    surface = Color(0xFFF7FAFB),
    surfaceVariant = Color(0xFFDBE4E8),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF003544),
    primaryContainer = Color(0xFF1A4F5F),
    onPrimaryContainer = Color(0xFFC5E7F3),
    secondary = Color(0xFFB1CBD4),
    secondaryContainer = Color(0xFF334A52),
    tertiary = Color(0xFFC4C2E0),
    tertiaryContainer = Color(0xFF43455F),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
    background = Color(0xFF0E1518),
    surface = Color(0xFF0E1518),
    surfaceVariant = Color(0xFF3F484C),
)

/** Epistemic roles — use with badges / chips, not as whole-screen themes. */
object EpistemicColors {
    val Fact = Color(0xFF1B6B4A)
    val Claim = Color(0xFF3D5A6C)
    val Hypothesis = Color(0xFF5B5B7A)
    val Unknown = Color(0xFF6B7280)
    val Contested = ConflictRed
    val Stale = AmberWarning
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
