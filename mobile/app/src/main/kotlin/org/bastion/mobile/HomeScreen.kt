package org.bastion.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import org.bastion.core.designsystem.component.PrimaryButton
import org.bastion.core.designsystem.theme.BastionTheme
import org.bastion.core.designsystem.tokens.SpacingTokens

/** Milestone 1 placeholder home: one state, one (not yet available) main action. */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val colors = BastionTheme.colors
    val type = BastionTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .padding(horizontal = SpacingTokens.Lg, vertical = SpacingTokens.Xl),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text(text = stringResource(R.string.product_name), style = type.label, color = colors.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(R.string.home_status_unpaired),
            style = type.display,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(SpacingTokens.Sm))
        Text(
            text = stringResource(R.string.home_status_unpaired_hint),
            style = type.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(SpacingTokens.Xl))
        PrimaryButton(
            text = stringResource(R.string.home_action_pair),
            onClick = {},
            enabled = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(SpacingTokens.Xs))
        Text(
            text = stringResource(R.string.home_action_pair_unavailable),
            style = type.caption,
            color = colors.textSecondary,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0A0B)
@Composable
private fun HomeScreenPreview() {
    BastionTheme { HomeScreen() }
}
