package org.bastion.core.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.bastion.core.designsystem.theme.BastionTheme
import org.bastion.core.designsystem.tokens.BorderTokens
import org.bastion.core.designsystem.tokens.SizeTokens
import org.bastion.core.designsystem.tokens.SpacingTokens

/** Secondary action: outlined, neutral. Set [danger] for destructive actions. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val colors = BastionTheme.colors
    val content = if (danger) colors.danger else colors.textPrimary
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = SizeTokens.TouchTargetMin),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = content,
            disabledContentColor = colors.textDisabled,
        ),
        border = BorderStroke(BorderTokens.Width, if (danger) colors.danger else colors.borderActive),
        contentPadding = PaddingValues(horizontal = SpacingTokens.Lg),
    ) {
        Text(text = text, style = BastionTheme.typography.label)
    }
}
