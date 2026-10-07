package org.bastion.feature.protection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import org.bastion.core.designsystem.component.PrimaryButton
import org.bastion.core.designsystem.component.SecondaryButton
import org.bastion.core.designsystem.theme.BastionTheme
import org.bastion.core.designsystem.tokens.BorderTokens
import org.bastion.core.designsystem.tokens.RadiusTokens
import org.bastion.core.designsystem.tokens.SpacingTokens

/** One protection prerequisite, shown on the Health list with a fix action. */
enum class HealthId { SCREEN_LOCK, NOTIFICATIONS, LOCATION, BACKGROUND_LOCATION, DEVICE_ADMIN, BATTERY, RELAY }

data class HealthItem(val id: HealthId, val ok: Boolean)

/** Everything the home screen shows. Contact details never appear here. */
data class ProtectionUiState(
    val controllerLabel: String,
    val relayUrl: String,
    val lastContact: String?,
    val active: Boolean,
    val lostMode: Boolean,
    val health: List<HealthItem>,
)

@Composable
fun UnpairedScreen(onPair: () -> Unit, modifier: Modifier = Modifier) {
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
        Text(
            stringResource(R.string.unpaired_title),
            style = type.display,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(SpacingTokens.Sm))
        Text(stringResource(R.string.unpaired_hint), style = type.body, color = colors.textSecondary)
        Spacer(Modifier.height(SpacingTokens.Xl))
        PrimaryButton(stringResource(R.string.unpaired_action), onPair, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun ProtectionScreen(
    state: ProtectionUiState,
    onFix: (HealthId) -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BastionTheme.colors
    val type = BastionTheme.typography
    val healthy = state.health.all { it.ok }
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
            text = stringResource(
                when {
                    !state.active -> R.string.status_waiting
                    healthy -> R.string.status_protected
                    else -> R.string.status_degraded
                },
            ),
            style = type.display,
            color = if (healthy && state.active) colors.textPrimary else colors.warning,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(R.string.status_controller, state.controllerLabel),
            style = type.body,
            color = colors.textSecondary,
        )
        Text(state.relayUrl, style = type.mono, color = colors.textSecondary)
        Text(
            state.lastContact?.let { stringResource(R.string.status_last_contact, it) }
                ?: stringResource(R.string.status_never_contacted),
            style = type.caption,
            color = colors.textSecondary,
        )
        if (state.lostMode) {
            Text(stringResource(R.string.status_lost_mode), style = type.label, color = colors.warning)
        }

        Text(
            stringResource(R.string.health_title),
            style = type.title,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        state.health.forEach { item -> HealthRow(item, onFix) }

        Spacer(Modifier.height(SpacingTokens.Md))
        SecondaryButton(
            stringResource(R.string.action_unpair),
            onUnpair,
            modifier = Modifier.fillMaxWidth(),
            danger = true,
        )
    }
}

@Composable
private fun HealthRow(item: HealthItem, onFix: (HealthId) -> Unit) {
    val colors = BastionTheme.colors
    val type = BastionTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                BorderTokens.Width,
                if (item.ok) colors.border else colors.warning,
                RoundedCornerShape(RadiusTokens.Md),
            )
            .padding(horizontal = SpacingTokens.Md, vertical = SpacingTokens.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(item.id.title()), style = type.label, color = colors.textPrimary)
            Text(
                stringResource(if (item.ok) R.string.health_ok else item.id.problem()),
                style = type.caption,
                color = if (item.ok) colors.success else colors.warning,
            )
        }
        if (!item.ok && item.id != HealthId.RELAY) {
            TextButton(onClick = { onFix(item.id) }) {
                Text(stringResource(R.string.health_fix), color = colors.accent, style = type.label)
            }
        }
    }
}

private fun HealthId.title(): Int = when (this) {
    HealthId.SCREEN_LOCK -> R.string.health_screen_lock
    HealthId.NOTIFICATIONS -> R.string.health_notifications
    HealthId.LOCATION -> R.string.health_location
    HealthId.BACKGROUND_LOCATION -> R.string.health_background_location
    HealthId.DEVICE_ADMIN -> R.string.health_device_admin
    HealthId.BATTERY -> R.string.health_battery
    HealthId.RELAY -> R.string.health_relay
}

private fun HealthId.problem(): Int = when (this) {
    HealthId.SCREEN_LOCK -> R.string.health_screen_lock_problem
    HealthId.NOTIFICATIONS -> R.string.health_notifications_problem
    HealthId.LOCATION -> R.string.health_location_problem
    HealthId.BACKGROUND_LOCATION -> R.string.health_background_location_problem
    HealthId.DEVICE_ADMIN -> R.string.health_device_admin_problem
    HealthId.BATTERY -> R.string.health_battery_problem
    HealthId.RELAY -> R.string.health_relay_problem
}

/** Full-screen lost-mode message, shown above the lock screen. */
@Composable
fun LostModeScreen(message: String, phone: String, email: String, onOwner: () -> Unit, modifier: Modifier = Modifier) {
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
            stringResource(R.string.lost_title),
            style = type.display,
            color = colors.textPrimary,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            message.ifBlank { stringResource(R.string.lost_default_message) },
            style = type.body,
            color = colors.textPrimary,
        )
        if (phone.isNotBlank()) Text(phone, style = type.title, color = colors.accent)
        if (email.isNotBlank()) Text(email, style = type.body, color = colors.accent)
        Spacer(Modifier.height(SpacingTokens.Xl))
        TextButton(onClick = onOwner) {
            Text(stringResource(R.string.lost_owner), color = colors.textSecondary, style = type.label)
        }
    }
}
