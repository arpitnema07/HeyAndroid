package com.example.heyandroid.ui.components

import com.example.heyandroid.ui.components.AppColors

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Flat dark palette for the app surfaces.
 *
 * The previous UI leaned on translucent "glass" fills, which made text contrast
 * depend on whatever was behind the panel. These tokens are all opaque, so every
 * foreground/background pair has a fixed, checkable contrast ratio.
 */
object AppColors {
    val Background = Color(0xFF08090C)
    val BackgroundLift = Color(0xFF0D1016)
    val Surface = Color(0xFF14171D)
    val SurfaceRaised = Color(0xFF1A1E26)
    val SurfaceSunken = Color(0xFF0E1015)
    val Chip = Color(0xFF222732)

    val Border = Color(0xFF262C36)
    val BorderStrong = Color(0xFF38404E)

    val TextPrimary = Color(0xFFEDF0F6)
    val TextSecondary = Color(0xFFA9B1BE)
    val TextTertiary = Color(0xFF8B94A3)

    val Accent = Color(0xFF8FA8FF)
    val AccentSoft = Color(0xFF232C4C)
    val AccentText = Color(0xFFDCE4FF)

    val Mint = Color(0xFF7DE2C3)
    val Amber = Color(0xFFF3BE6B)
    val Sky = Color(0xFF7FC7FF)
    val Green = Color(0xFF9BE38A)
    val Danger = Color(0xFFFF9AA5)
}

/**
 * The window background. A single flat colour with a barely-there lift at the
 * top; no blur, no translucency, nothing that reads as glass.
 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    accent: Color = AppColors.Accent,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.Background)
            .drawWithCache {
                val wash = Brush.verticalGradient(
                    0f to AppColors.BackgroundLift,
                    0.55f to AppColors.Background,
                    1f to AppColors.Background
                )
                val glow = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.07f), Color.Transparent),
                    center = Offset(size.width * 0.5f, -size.height * 0.08f),
                    radius = size.width * 0.95f
                )
                onDrawBehind {
                    drawRect(wash)
                    drawRect(glow)
                }
            },
        content = content
    )
}

/** An opaque card: solid fill, one hairline border, no elevation shadow. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    fill: Color = AppColors.Surface,
    borderColor: Color = AppColors.Border,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, borderColor, shape)
            .padding(contentPadding),
        content = content
    )
}

/** A clickable opaque pill, used for primary actions. */
@Composable
fun AppPill(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    fill: Color = AppColors.SurfaceRaised,
    borderColor: Color = AppColors.Border,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
