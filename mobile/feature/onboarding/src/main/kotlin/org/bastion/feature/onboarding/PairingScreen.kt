@file:Suppress("MatchingDeclarationName")

package org.bastion.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import org.bastion.core.designsystem.component.PrimaryButton
import org.bastion.core.designsystem.component.SecondaryButton
import org.bastion.core.designsystem.theme.BastionTheme
import org.bastion.core.designsystem.tokens.RadiusTokens
import org.bastion.core.designsystem.tokens.SpacingTokens

/** Pairing progress shown by [PairingScreen]. */
sealed interface PairingUiState {
    data object Idle : PairingUiState

    data object Working : PairingUiState

    /** [message] is a resolved, user-facing error. */
    data class Failed(val message: String) : PairingUiState
}

/**
 * Scan the controller QR code, or paste the pairing link. Stateless: the caller owns the
 * pairing logic and the camera permission.
 */
@Composable
fun PairingScreen(
    state: PairingUiState,
    cameraGranted: Boolean,
    onRequestCamera: () -> Unit,
    onLink: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BastionTheme.colors
    val type = BastionTheme.typography
    var pasted by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SpacingTokens.Lg, vertical = SpacingTokens.Lg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Md),
    ) {
        Text(
            stringResource(R.string.pairing_title),
            style = type.title,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.pairing_hint), style = type.body, color = colors.textSecondary)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(RadiusTokens.Lg))
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state is PairingUiState.Working -> CircularProgressIndicator(color = colors.accent)
                cameraGranted -> QrScanner(onResult = onLink, modifier = Modifier.fillMaxSize())
                else -> PrimaryButton(stringResource(R.string.pairing_allow_camera), onRequestCamera)
            }
        }
        if (state is PairingUiState.Failed) {
            Text(state.message, style = type.body, color = colors.danger)
        }
        Text(stringResource(R.string.pairing_paste_hint), style = type.caption, color = colors.textSecondary)
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it.take(MAX_LINK) },
            label = { Text(stringResource(R.string.pairing_paste_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Sm)) {
            SecondaryButton(stringResource(R.string.pairing_back), onBack)
            PrimaryButton(
                text = stringResource(R.string.pairing_use_link),
                onClick = { onLink(pasted.trim()) },
                enabled = pasted.isNotBlank() && state !is PairingUiState.Working,
            )
        }
        Spacer(Modifier.height(SpacingTokens.Lg))
    }
}

private const val MAX_LINK = 4096

/**
 * Short authentication string check (PROTOCOL.md §3.3). The user confirms only if the computer
 * shows the same six digits.
 */
@Composable
fun SasScreen(
    sas: String,
    controllerLabel: String,
    awaitingRemote: Boolean,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BastionTheme.colors
    val type = BastionTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .padding(SpacingTokens.Lg),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Md, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(R.string.sas_title),
            style = type.title,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.sas_body, controllerLabel),
            style = type.body,
            color = colors.textSecondary,
        )
        Text(sas, style = type.display, color = colors.accent, modifier = Modifier.align(Alignment.CenterHorizontally))
        if (awaitingRemote) {
            Text(stringResource(R.string.sas_awaiting), style = type.body, color = colors.warning)
            SecondaryButton(stringResource(R.string.sas_cancel), onReject, danger = true)
        } else {
            PrimaryButton(stringResource(R.string.sas_match), onConfirm, modifier = Modifier.fillMaxWidth())
            SecondaryButton(
                stringResource(R.string.sas_mismatch),
                onReject,
                modifier = Modifier.fillMaxWidth(),
                danger = true,
            )
        }
    }
}
