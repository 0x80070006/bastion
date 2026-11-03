package org.bastion.mobile.service

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import org.bastion.feature.protection.CapabilityId
import org.bastion.feature.protection.CapabilityItem
import org.bastion.feature.protection.CircuitId
import org.bastion.feature.protection.HealthId
import org.bastion.feature.protection.HealthItem
import org.bastion.mobile.BuildConfig
import org.bastion.protocol.v1.BatteryState
import org.bastion.protocol.v1.NetworkType
import org.bastion.protocol.v1.ProtectionHealth

/** Reads the protection prerequisites and device status (no personal data). */
@Suppress("TooManyFunctions")
class DeviceState(private val context: Context) {
    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    val adminComponent: ComponentName get() = ComponentName(context, BastionDeviceAdmin::class.java)

    fun hasLocation(): Boolean = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
        granted(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasBackgroundLocation(): Boolean = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

    fun hasNotifications(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        granted(Manifest.permission.POST_NOTIFICATIONS)

    fun hasCamera(): Boolean = granted(Manifest.permission.CAMERA) &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /** SIM operator MCC+MNC (e.g. "20801"), or "" when no SIM is present. No permission needed. */
    fun simOperator(): String {
        val tm = context.getSystemService<android.telephony.TelephonyManager>() ?: return ""
        return if (tm.simState == android.telephony.TelephonyManager.SIM_STATE_READY) tm.simOperator.orEmpty() else ""
    }

    fun simOperatorName(): String =
        context.getSystemService<android.telephony.TelephonyManager>()?.simOperatorName.orEmpty()

    fun simPresent(): Boolean {
        val state = context.getSystemService<android.telephony.TelephonyManager>()?.simState
        return state != null &&
            state != android.telephony.TelephonyManager.SIM_STATE_ABSENT &&
            state != android.telephony.TelephonyManager.SIM_STATE_UNKNOWN
    }

    fun isAdminActive(): Boolean =
        context.getSystemService<DevicePolicyManager>()?.isAdminActive(adminComponent) == true

    fun isBatteryExempt(): Boolean =
        context.getSystemService<PowerManager>()?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun isScreenLocked(): Boolean = context.getSystemService<KeyguardManager>()?.isDeviceSecure == true

    /** Whether a secure keyguard is showing right now (used to withhold remote input). */
    fun isKeyguardLocked(): Boolean = context.getSystemService<KeyguardManager>()?.isKeyguardLocked == true

    fun hasMicrophone(): Boolean = granted(Manifest.permission.RECORD_AUDIO)

    fun hasContacts(): Boolean = granted(Manifest.permission.READ_CONTACTS)

    fun hasCalls(): Boolean = granted(Manifest.permission.CALL_PHONE) &&
        granted(Manifest.permission.ANSWER_PHONE_CALLS) &&
        granted(Manifest.permission.READ_PHONE_STATE)

    fun hasSms(): Boolean = granted(Manifest.permission.SEND_SMS) &&
        granted(Manifest.permission.READ_SMS) &&
        granted(Manifest.permission.RECEIVE_SMS)

    /** Whether the owner enabled the accessibility service that drives remote screen and input. */
    fun isRemoteControlEnabled(): Boolean = RemoteInputService.instance != null

    /** Optional capabilities with their real, live state. */
    fun capabilities(): List<CapabilityItem> = listOf(
        CapabilityItem(CapabilityId.CAMERA, hasCamera()),
        CapabilityItem(CapabilityId.MICROPHONE, hasMicrophone()),
        CapabilityItem(CapabilityId.CONTACTS, hasContacts()),
        CapabilityItem(CapabilityId.PHONE, hasCalls()),
        CapabilityItem(CapabilityId.SMS, hasSms()),
        CapabilityItem(CapabilityId.ACCESSIBILITY, isRemoteControlEnabled()),
    )

    private fun activeCapabilities() =
        context.getSystemService<ConnectivityManager>()?.let { cm ->
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        }

    /** Circuit elements that are really up: validated network, VPN, relay, controller and services. */
    fun connections(relayOk: Boolean?, controllerRecent: Boolean): Set<CircuitId> = buildSet {
        add(CircuitId.PHONE)
        val caps = activeCapabilities()
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) add(CircuitId.NETWORK)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add(CircuitId.VPN)
        if (relayOk == true) add(CircuitId.RELAY)
        if (relayOk == true && controllerRecent) add(CircuitId.PC)
        if (hasLocation()) add(CircuitId.LOCATION)
        if (isRemoteControlEnabled()) add(CircuitId.CONTROL)
    }

    fun health(relayOk: Boolean?): List<HealthItem> = listOf(
        HealthItem(HealthId.SCREEN_LOCK, isScreenLocked()),
        HealthItem(HealthId.LOCATION, hasLocation()),
        HealthItem(HealthId.BACKGROUND_LOCATION, hasBackgroundLocation()),
        HealthItem(HealthId.NOTIFICATIONS, hasNotifications()),
        HealthItem(HealthId.DEVICE_ADMIN, isAdminActive()),
        HealthItem(HealthId.BATTERY, isBatteryExempt()),
        HealthItem(HealthId.RELAY, relayOk != false),
    )

    fun protoHealth(): List<ProtectionHealth> = health(null).filter { it.id != HealthId.RELAY }.map { item ->
        ProtectionHealth.newBuilder()
            .setFeature(item.id.name.lowercase())
            .setState(if (item.ok) ProtectionHealth.State.STATE_ACTIVE else ProtectionHealth.State.STATE_DISABLED)
            .setReasonCode(if (item.ok) "" else "not_granted")
            .build()
    }

    fun battery(): BatteryState {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * PERCENT / scale else 0
        return BatteryState.newBuilder()
            .setLevelPercent(percent.coerceIn(0, PERCENT))
            .setCharging(
                status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            )
            .build()
    }

    fun network(): NetworkType {
        val manager = context.getSystemService<ConnectivityManager>() ?: return NetworkType.NETWORK_TYPE_UNSPECIFIED
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return NetworkType.NETWORK_TYPE_NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.NETWORK_TYPE_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.NETWORK_TYPE_CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.NETWORK_TYPE_ETHERNET
            else -> NetworkType.NETWORK_TYPE_OTHER
        }
    }

    val appVersion: String get() = BuildConfig.VERSION_NAME

    private companion object {
        const val PERCENT = 100
    }
}
