package org.bastion.feature.protection

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bastion.core.designsystem.component.Emblem
import org.bastion.core.designsystem.component.PrimaryButton
import org.bastion.core.designsystem.component.SecondaryButton
import org.bastion.core.designsystem.theme.BastionTheme
import org.bastion.core.designsystem.tokens.BorderTokens
import org.bastion.core.designsystem.tokens.RadiusTokens
import org.bastion.core.designsystem.tokens.SizeTokens
import org.bastion.core.designsystem.tokens.SpacingTokens

/** One protection prerequisite, shown on the Health list with a fix action. */
enum class HealthId { SCREEN_LOCK, NOTIFICATIONS, LOCATION, BACKGROUND_LOCATION, DEVICE_ADMIN, BATTERY, RELAY }

data class HealthItem(val id: HealthId, val ok: Boolean)

/** Optional capabilities the owner can grant for remote features. Absent ones never weaken protection. */
enum class CapabilityId { CAMERA, MICROPHONE, CONTACTS, PHONE, SMS, ACCESSIBILITY }

data class CapabilityItem(val id: CapabilityId, val granted: Boolean)

/** Everything the home screen shows. Contact details never appear here. */
data class ProtectionUiState(
    val controllerLabel: String,
    val relayUrl: String,
    val lastContact: String?,
    val active: Boolean,
    val lostMode: Boolean,
    val health: List<HealthItem>,
    val capabilities: List<CapabilityItem> = emptyList(),
    /** Elements of the circuit that are really up right now. */
    val connections: Set<CircuitId> = emptySet(),
)

private val HERO_HEIGHT = 72.dp
private val LED = 10.dp
private val ROW_LED = 8.dp
private const val DIM_ALPHA = 0.12f

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
        Emblem(size = 72.dp)
        Spacer(Modifier.height(SpacingTokens.Lg))
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

private enum class Level { PROTECTED, INCOMPLETE, OFFLINE }

@Suppress("LongMethod")
@Composable
fun ProtectionScreen(
    state: ProtectionUiState,
    onFix: (HealthId) -> Unit,
    onCapability: (CapabilityId) -> Unit,
    onManage: () -> Unit,
    onCheck: () -> Unit,
    onUnpair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BastionTheme.colors
    val type = BastionTheme.typography
    val required = state.health.filter { it.id != HealthId.RELAY }
    val missing = required.firstOrNull { !it.ok }
    val relayUp = state.health.firstOrNull { it.id == HealthId.RELAY }?.ok != false
    val level = when {
        !state.active || !relayUp -> Level.OFFLINE
        missing != null -> Level.INCOMPLETE
        else -> Level.PROTECTED
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SpacingTokens.Md, vertical = SpacingTokens.Md),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Emblem(size = 30.dp)
            Spacer(Modifier.weight(1f))
            Text(
                state.controllerLabel,
                style = type.mono,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        StatusButton(
            level = level,
            ok = required.count { it.ok },
            total = required.size,
            lastContact = state.lastContact,
            hasFix = missing != null,
            onClick = { if (missing != null) onFix(missing.id) else onCheck() },
        )
        if (state.lostMode) {
            Text(stringResource(R.string.status_lost_mode), style = type.label, color = colors.warning)
        }

        SectionLabel(R.string.circuit_title)
        ConnectionCircuit(state.connections)
        Text(state.relayUrl, style = type.mono, color = colors.textSecondary)

        SectionLabel(R.string.perm_title)
        state.health.filter { it.id != HealthId.RELAY }.forEach { item ->
            PermissionRow(
                title = stringResource(item.id.title()),
                detail = stringResource(if (item.ok) R.string.perm_state_on else item.id.problem()),
                granted = item.ok,
                onToggle = { if (item.ok) onManage() else onFix(item.id) },
            )
        }
        SectionLabel(R.string.perm_optional_title)
        state.capabilities.forEach { item ->
            PermissionRow(
                title = stringResource(item.id.title()),
                detail = stringResource(if (item.granted) R.string.perm_state_on else item.id.hint()),
                granted = item.granted,
                onToggle = { onCapability(item.id) },
            )
        }
        Text(stringResource(R.string.perm_footer), style = type.caption, color = colors.textSecondary)

        Spacer(Modifier.height(SpacingTokens.Sm))
        SecondaryButton(
            stringResource(R.string.action_unpair),
            onUnpair,
            modifier = Modifier.fillMaxWidth(),
            danger = true,
        )
    }
}

@Composable
private fun SectionLabel(@StringRes text: Int) {
    val colors = BastionTheme.colors
    Column(Modifier.fillMaxWidth().semantics { heading() }) {
        Text(
            stringResource(text).uppercase(),
            style = BastionTheme.typography.mono.copy(letterSpacing = 1.6.sp),
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(SpacingTokens.Xxs))
        Box(Modifier.fillMaxWidth().height(BorderTokens.Width).background(colors.border))
    }
}

/** The big "you are protected" control. It re-checks everything, or fixes the first missing item. */
@Composable
private fun StatusButton(
    level: Level,
    ok: Int,
    total: Int,
    lastContact: String?,
    hasFix: Boolean,
    onClick: () -> Unit,
) {
    val colors = BastionTheme.colors
    val type = BastionTheme.typography
    val tone: Color = when (level) {
        Level.PROTECTED -> colors.success
        Level.INCOMPLETE -> colors.warning
        Level.OFFLINE -> colors.danger
    }
    val title = when (level) {
        Level.PROTECTED -> R.string.hero_protected
        Level.INCOMPLETE -> R.string.hero_incomplete
        Level.OFFLINE -> R.string.hero_offline
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = HERO_HEIGHT)
            .clip(RoundedCornerShape(RadiusTokens.Md))
            .background(tone.copy(alpha = DIM_ALPHA))
            .border(BorderTokens.Width, tone, RoundedCornerShape(RadiusTokens.Md))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = SpacingTokens.Md, vertical = SpacingTokens.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Sm),
    ) {
        Box(Modifier.size(LED).clip(CircleShape).background(tone))
        Column(Modifier.weight(1f)) {
            Text(stringResource(title), style = type.title, color = colors.textPrimary)
            Text(
                stringResource(R.string.hero_detail, ok, total) +
                    (lastContact?.let { " · $it" } ?: ""),
                style = type.mono,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            stringResource(if (hasFix) R.string.hero_fix else R.string.hero_check).uppercase(),
            style = type.label,
            color = tone,
        )
    }
}

@Composable
private fun PermissionRow(title: String, detail: String, granted: Boolean, onToggle: () -> Unit) {
    val colors = BastionTheme.colors
    val type = BastionTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = SizeTokens.TouchTargetMin)
            .border(
                BorderTokens.Width,
                if (granted) colors.border else colors.warning,
                RoundedCornerShape(RadiusTokens.Md),
            )
            .padding(horizontal = SpacingTokens.Sm, vertical = SpacingTokens.Xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Sm),
    ) {
        Box(
            Modifier
                .size(ROW_LED)
                .clip(CircleShape)
                .background(if (granted) colors.success else colors.warning),
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = type.label, color = colors.textPrimary)
            Text(detail, style = type.caption, color = if (granted) colors.success else colors.textSecondary)
        }
        Switch(
            checked = granted,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                checkedBorderColor = colors.accent,
                uncheckedThumbColor = colors.textSecondary,
                uncheckedTrackColor = colors.surfaceRaised,
                uncheckedBorderColor = colors.borderActive,
            ),
        )
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

private fun CapabilityId.title(): Int = when (this) {
    CapabilityId.CAMERA -> R.string.cap_camera
    CapabilityId.MICROPHONE -> R.string.cap_microphone
    CapabilityId.CONTACTS -> R.string.cap_contacts
    CapabilityId.PHONE -> R.string.cap_phone
    CapabilityId.SMS -> R.string.cap_sms
    CapabilityId.ACCESSIBILITY -> R.string.cap_accessibility
}

private fun CapabilityId.hint(): Int = when (this) {
    CapabilityId.CAMERA -> R.string.cap_camera_hint
    CapabilityId.MICROPHONE -> R.string.cap_microphone_hint
    CapabilityId.CONTACTS -> R.string.cap_contacts_hint
    CapabilityId.PHONE -> R.string.cap_phone_hint
    CapabilityId.SMS -> R.string.cap_sms_hint
    CapabilityId.ACCESSIBILITY -> R.string.cap_accessibility_hint
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
        Emblem(size = 56.dp, color = colors.warning)
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
