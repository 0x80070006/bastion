package org.bastion.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.bastion.core.designsystem.theme.BastionTheme
import org.bastion.core.designsystem.tokens.BorderTokens
import org.bastion.core.designsystem.tokens.SizeTokens
import org.bastion.core.designsystem.tokens.SpacingTokens

/** The single main action of a screen. Flat, accent-filled, no elevation. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val colors = BastionTheme.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = SizeTokens.TouchTargetMin),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = colors.onAccent,
            disabledContainerColor = colors.surface,
            disabledContentColor = colors.textDisabled,
        ),
        border = if (enabled) null else BorderStroke(BorderTokens.Width, colors.border),
        elevation = null,
        contentPadding = PaddingValues(horizontal = SpacingTokens.Lg),
    ) {
        Text(text = text, style = BastionTheme.typography.label)
    }
}

@Preview
@Composable
private fun PrimaryButtonPreview() {
    BastionTheme { PrimaryButton(text = "Appairer", onClick = {}) }
}
