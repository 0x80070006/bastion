// GENERATED from design/tokens.json by tools/gen-tokens.mjs. Do not edit.
@file:Suppress("MagicNumber")

package org.bastion.core.designsystem.tokens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object ColorTokens {
    val Background = Color(0xFF0B0E11)
    val Surface = Color(0xFF10151A)
    val SurfaceRaised = Color(0xFF161C22)
    val Border = Color(0xFF1F272E)
    val BorderActive = Color(0xFF2D3842)
    val TextPrimary = Color(0xFFE6EAED)
    val TextSecondary = Color(0xFF9AA6B0)
    val TextTertiary = Color(0xFF7B8792)
    val Accent = Color(0xFF3DDC97)
    val OnAccent = Color(0xFF04130C)
    val Danger = Color(0xFFF0524F)
    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFE5A00D)
    val Inactive = Color(0xFF6B7782)
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
    val Sm = 2.dp
    val Md = 3.dp
    val Lg = 4.dp
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
    const val SANS = "IBM Plex Sans"
    const val MONO = "JetBrains Mono"
}
