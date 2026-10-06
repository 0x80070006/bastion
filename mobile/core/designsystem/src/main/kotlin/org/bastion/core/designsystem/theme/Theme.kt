package org.bastion.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import org.bastion.core.designsystem.tokens.ColorTokens
import org.bastion.core.designsystem.tokens.RadiusTokens

/** Semantic colors. Every value comes from the generated tokens; nothing is hard-coded here. */
@Immutable
data class BastionColors(
    val background: Color = ColorTokens.Background,
    val surface: Color = ColorTokens.Surface,
    val surfaceRaised: Color = ColorTokens.SurfaceRaised,
    val border: Color = ColorTokens.Border,
    val borderActive: Color = ColorTokens.BorderActive,
    val textPrimary: Color = ColorTokens.TextPrimary,
    val textSecondary: Color = ColorTokens.TextSecondary,
    val textDisabled: Color = ColorTokens.TextTertiary,
    val accent: Color = ColorTokens.Accent,
    val onAccent: Color = ColorTokens.OnAccent,
    val danger: Color = ColorTokens.Danger,
    val success: Color = ColorTokens.Success,
    val warning: Color = ColorTokens.Warning,
)

private val LocalBastionColors = staticCompositionLocalOf { BastionColors() }
private val LocalBastionTypography = staticCompositionLocalOf { BastionTypography() }

/** Accessors for the current theme values. */
object BastionTheme {
    val colors: BastionColors
        @Composable @ReadOnlyComposable
        get() = LocalBastionColors.current

    val typography: BastionTypography
        @Composable @ReadOnlyComposable
        get() = LocalBastionTypography.current
}

/**
 * Dark-only theme. Material 3 is a technical base: its color scheme and shapes are mapped onto
 * Bastion tokens so stock components never introduce foreign colors, elevation tints or radii.
 */
@Composable
fun BastionTheme(content: @Composable () -> Unit) {
    val colors = BastionColors()
    val typography = BastionTypography()
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        secondary = colors.textSecondary,
        onSecondary = colors.background,
        background = colors.background,
        onBackground = colors.textPrimary,
        surface = colors.surface,
        onSurface = colors.textPrimary,
        surfaceVariant = colors.surfaceRaised,
        onSurfaceVariant = colors.textSecondary,
        surfaceTint = Color.Transparent,
        outline = colors.border,
        outlineVariant = colors.border,
        error = colors.danger,
        onError = colors.textPrimary,
    )
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(RadiusTokens.Sm),
        small = RoundedCornerShape(RadiusTokens.Sm),
        medium = RoundedCornerShape(RadiusTokens.Md),
        large = RoundedCornerShape(RadiusTokens.Lg),
        extraLarge = RoundedCornerShape(RadiusTokens.Lg),
    )
    CompositionLocalProvider(
        LocalBastionColors provides colors,
        LocalBastionTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typography.toMaterial(),
            shapes = shapes,
            content = content,
        )
    }
}
