package com.example.heyandroid.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.heyandroid.ui.components.AppColors

/**
 * One dark scheme, applied everywhere.
 *
 * Dynamic colour is deliberately off: it pulled wallpaper tones into the UI and
 * produced unpredictable contrast (light text on light containers, unreadable
 * secondary text). The palette below is fixed and audited against the surfaces.
 */
private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = Color(0xFF0B1230),
    primaryContainer = AppColors.AccentSoft,
    onPrimaryContainer = AppColors.AccentText,

    secondary = AppColors.Mint,
    onSecondary = Color(0xFF04291F),
    secondaryContainer = Color(0xFF16352C),
    onSecondaryContainer = Color(0xFFC7F5E7),

    tertiary = AppColors.Amber,
    onTertiary = Color(0xFF2A1D05),
    tertiaryContainer = Color(0xFF3A2C12),
    onTertiaryContainer = Color(0xFFFFE6BF),

    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,

    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceRaised,
    onSurfaceVariant = AppColors.TextSecondary,

    surfaceContainerLowest = AppColors.SurfaceSunken,
    surfaceContainerLow = AppColors.Surface,
    surfaceContainer = AppColors.SurfaceRaised,
    surfaceContainerHigh = AppColors.Chip,
    surfaceContainerHighest = AppColors.Chip,

    outline = AppColors.BorderStrong,
    outlineVariant = AppColors.Border,

    error = AppColors.Danger,
    onError = Color(0xFF2A070B),
    errorContainer = Color(0xFF3A1D22),
    onErrorContainer = Color(0xFFFFDADE),

    inverseSurface = AppColors.TextPrimary,
    inverseOnSurface = AppColors.Background,
    scrim = Color.Black
)

@Composable
fun HeyAndroidTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
