package org.bastion.mobile.service

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import androidx.core.content.getSystemService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bastion.mobile.R
import org.bastion.mobile.di.ReceiverEntryPoint
import org.bastion.protocol.v1.Alert
import org.bastion.protocol.v1.Event

private const val ALERT_TIMEOUT_MS = 8_000L

/** Sends an alert from a short-lived receiver, keeping it alive until delivered or timed out. */
private fun BroadcastReceiver.sendAlert(context: Context, alert: Alert.Builder) {
    val entry = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
    val pending = goAsync()
    entry.scope().launch {
        try {
            withTimeoutOrNull(ALERT_TIMEOUT_MS) {
                Locator(context, DeviceState(context)).lastKnown()?.let { alert.setLocation(Locator.toProto(it)) }
                entry.repository().sendEvent(Event.newBuilder().setAlert(alert).build())
            }
        } finally {
            pending.finish()
        }
    }
}

/**
 * Device administrator: `lockNow`, `wipeData` and failed-unlock detection (PERMISSIONS.md).
 * Protected by `BIND_DEVICE_ADMIN`, so no other app can drive it.
 */
class BastionDeviceAdmin : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        val attempts = context.getSystemService<DevicePolicyManager>()?.currentFailedPasswordAttempts ?: 0
        sendAlert(
            context,
            Alert.newBuilder()
                .setType(Alert.Type.TYPE_FAILED_UNLOCK)
                .putDetail("attempts", attempts.toString()),
        )
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        sendAlert(context, Alert.newBuilder().setType(Alert.Type.TYPE_ADMIN_DISABLE_REQUESTED))
        return context.getString(R.string.admin_disable_warning)
    }

    override fun onDisabled(context: Context, intent: Intent) {
        sendAlert(
            context,
            Alert.newBuilder().setType(Alert.Type.TYPE_PROTECTION_WEAKENED).putDetail("feature", "device_admin"),
        )
    }
}

/** Restarts the protection after a reboot (once the user unlocked the device). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val entry = EntryPointAccessors.fromApplication(context.applicationContext, ReceiverEntryPoint::class.java)
        if (entry.repository().state.value.hasPairing()) ProtectionService.start(context)
    }
}
