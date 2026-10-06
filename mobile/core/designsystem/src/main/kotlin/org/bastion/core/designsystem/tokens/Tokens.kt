// GENERATED from design/tokens.json by tools/gen-tokens.mjs. Do not edit.
@file:Suppress("MagicNumber")

package org.bastion.core.designsystem.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object ColorTokens {
    val Background = Color(0xFF0A0A0B)
    val Surface = Color(0xFF111113)
    val SurfaceRaised = Color(0xFF17171A)
    val Border = Color(0xFF24242A)
    val BorderActive = Color(0xFF34343C)
    val TextPrimary = Color(0xFFEDEDEF)
    val TextSecondary = Color(0xFF9A9AA3)
    val TextTertiary = Color(0xFF62626B)
    val Accent = Color(0xFFC9B27C)
    val OnAccent = Color(0xFF0A0A0B)
    val Danger = Color(0xFFC5574F)
    val Success = Color(0xFF5FA37A)
    val Warning = Color(0xFFC79A4A)
}

object SpacingTokens {
    val Xxs = 4.dp
    val Xs = 8.dp
    val Sm = 12.dp
    val Md = 16.dp
    val Lg = 24.dp
    val Xl = 32.dp
    val Xxl = 48.dp
}

object RadiusTokens {
    val Sm = 6.dp
    val Md = 10.dp
    val Lg = 12.dp
}

object BorderTokens {
    val Width = 1.dp
}

data class TypeToken(val size: Float, val lineHeight: Float, val weight: Int, val tracking: Float)

object TypeScaleTokens {
    val Display = TypeToken(size = 32f, lineHeight = 40f, weight = 600, tracking = -0.4f)
    val Title = TypeToken(size = 20f, lineHeight = 28f, weight = 600, tracking = -0.2f)
    val Body = TypeToken(size = 15f, lineHeight = 22f, weight = 400, tracking = 0f)
    val Label = TypeToken(size = 13f, lineHeight = 18f, weight = 500, tracking = 0.1f)
    val Caption = TypeToken(size = 12f, lineHeight = 16f, weight = 400, tracking = 0.2f)
}

object MotionTokens {
    const val FastMillis = 120
    const val StandardMillis = 160
    const val SlowMillis = 200
    val Easing = floatArrayOf(0f, 0f, 0.2f, 1f)
}

object SizeTokens {
    val TouchTargetMin = 48.dp
}

object FontTokens {
    const val SANS = "Inter Tight"
    const val MONO = "JetBrains Mono"
}
