package org.bastion.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.bastion.core.designsystem.tokens.TypeScaleTokens
import org.bastion.core.designsystem.tokens.TypeToken

// Milestone 1 uses system families as fallback; Inter Tight and JetBrains Mono are bundled
// as res/font in milestone 4 (ADR-0014). Never fetched at runtime.
private val Sans = FontFamily.SansSerif
private val Mono = FontFamily.Monospace

private fun TypeToken.toStyle(family: FontFamily = Sans) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.sp,
)

/** Five-step type scale (docs: section 10) plus a monospace style for technical data. */
@Immutable
data class BastionTypography(
    val display: TextStyle = TypeScaleTokens.Display.toStyle(),
    val title: TextStyle = TypeScaleTokens.Title.toStyle(),
    val body: TextStyle = TypeScaleTokens.Body.toStyle(),
    val label: TextStyle = TypeScaleTokens.Label.toStyle(),
    val caption: TextStyle = TypeScaleTokens.Caption.toStyle(),
    /** Coordinates, key fingerprints, IP addresses. */
    val mono: TextStyle = TypeScaleTokens.Label.toStyle(Mono).copy(fontWeight = FontWeight.Normal),
) {
    internal fun toMaterial() = Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = title,
        headlineMedium = title,
        headlineSmall = title,
        titleLarge = title,
        titleMedium = label,
        titleSmall = label,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = caption,
        labelLarge = label,
        labelMedium = label,
        labelSmall = caption,
    )
}
