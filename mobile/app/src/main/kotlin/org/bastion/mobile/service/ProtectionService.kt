package org.bastion.mobile.service

import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.bastion.core.agent.AgentCommand
import org.bastion.core.agent.PhoneAgent
import org.bastion.core.domain.model.ContactCard
import org.bastion.mobile.data.AgentRepository
import org.bastion.mobile.data.RelayException
import org.bastion.mobile.ui.LostModeActivity
import org.bastion.protocol.v1.CommandResult.Status
import org.bastion.protocol.v1.Event
import org.bastion.protocol.v1.LastChanceBeacon
import org.bastion.protocol.v1.LocationReport
import org.bastion.protocol.v1.MailboxItem
import org.bastion.protocol.v1.StatusReport
import org.bastion.protocol.v1.TrackingMode

/**
 * Foreground service keeping the encrypted channel to the relay open (long polling), executing
 * authenticated commands and reporting position, status and alerts.
 */
@AndroidEntryPoint
@Suppress("TooManyFunctions")
class ProtectionService : Service() {
    @Inject lateinit var repository: AgentRepository

    @Inject lateinit var agent: PhoneAgent

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var device: DeviceState
    private lateinit var locator: Locator
    private lateinit var alarm: Alarm
    private var loop: Job? = null
    private var periodic: Job? = null
    private var lastTrackedFixMs = 0L
    private var appliedTracking = -1

    private val systemEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val reason = when (intent.action) {
                Intent.ACTION_SHUTDOWN -> LastChanceBeacon.Reason.REASON_SHUTDOWN

                Intent.ACTION_BATTERY_LOW -> LastChanceBeacon.Reason.REASON_BATTERY_LOW

                Intent.ACTION_AIRPLANE_MODE_CHANGED ->
                    if (intent.getBooleanExtra("state", false)) LastChanceBeacon.Reason.REASON_AIRPLANE_MODE else null

                else -> null
            } ?: return
            val pending = goAsync()
            scope.launch {
                withTimeoutOrNull(BEACON_TIMEOUT_MS) { sendBeacon(reason) }
                pending.finish()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        device = DeviceState(this)
        locator = Locator(this, device)
        alarm = Alarm(this)
        ContextCompat.registerReceiver(
            this,
            systemEvents,
            IntentFilter().apply {
                addAction(Intent.ACTION_SHUTDOWN)
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_STOP_RING) {
            alarm.stop()
            getSystemService<NotificationManager>()?.cancel(Notifications.ID_ALARM)
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(systemEvents)
        alarm.stop()
        locator.stopTracking()
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground() {
        val active = agent.isActive(repository.state.value)
        val notification = Notifications.protection(this, active)
        val type = when {
            device.hasLocation() -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

            else -> 0
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_PROTECTION, notification, type)
        } catch (e: SecurityException) {
            // Location type refused (e.g. started from the background without that access).
            Log.w(TAG, "foreground type refused: ${e.javaClass.simpleName}")
            val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(this, Notifications.ID_PROTECTION, notification, fallback)
        }
    }

    private suspend fun run() {
        var backoffMs = INITIAL_BACKOFF_MS
        var unauthorized = 0
        var wasActive = false
        while (scope.isActive) {
            val state = repository.state.value
            if (!agent.isPaired(state)) {
                stopSelf()
                return
            }
            val active = agent.isActive(state)
            if (active && !wasActive) onActivated()
            wasActive = active
            val client = repository.client() ?: return
            try {
                val batch = client.fetch(POLL_WAIT_S)
                repository.reportRelay(true)
                unauthorized = 0
                backoffMs = INITIAL_BACKOFF_MS
                process(batch.itemsList)
                client.ack(batch.itemsList.map { it.id })
            } catch (_: RelayException.Unauthorized) {
                // The relay forgot this phone: the controller unpaired it.
                unauthorized += 1
                if (unauthorized >= MAX_UNAUTHORIZED) {
                    repository.forget(revokeOnRelay = false)
                    stopSelf()
                    return
                }
                delay(backoffMs)
            } catch (e: RelayException) {
                Log.i(TAG, "relay error: ${e.javaClass.simpleName}")
                repository.reportRelay(false)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    private fun onActivated() {
        goForeground()
        applyTracking()
        periodic?.cancel()
        periodic = scope.launch {
            while (isActive) {
                sendStatus()
                delay(STATUS_PERIOD_MS)
            }
        }
        if (repository.state.value.settings.lostMode) showLost()
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun process(items: List<MailboxItem>) {
        for (item in items) {
            if (item.kind != MailboxItem.Kind.KIND_ENVELOPE) continue
            val now = System.currentTimeMillis()
            val received = repository.transact { s ->
                val r = agent.receive(s, item.payload.toByteArray(), now)
                (r?.state ?: s) to r
            } ?: continue
            val controller = repository.controllerId() ?: return
            received.replies.forEach { repository.deliver(controller, it) }
            if (received.pairingRejected) {
                repository.forget(revokeOnRelay = false)
                return
            }
            received.commands.forEach { command -> scope.launch { execute(command) } }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun execute(command: AgentCommand) {
        val id = command.messageId
        repository.log("command.${command.javaClass.simpleName.lowercase()}")
        when (command) {
            is AgentCommand.Ring -> {
                alarm.start(scope, command.durationSeconds, command.flashlight, command.vibrate)
                getSystemService<NotificationManager>()?.notify(Notifications.ID_ALARM, Notifications.alarm(this))
                repository.sendResult(id, Status.STATUS_COMPLETED)
            }

            is AgentCommand.StopRing -> {
                alarm.stop()
                getSystemService<NotificationManager>()?.cancel(Notifications.ID_ALARM)
                repository.sendResult(id, Status.STATUS_COMPLETED)
            }

            is AgentCommand.Locate -> locate(id, command.highAccuracy)

            is AgentCommand.Tracking -> {
                updateSettings { it.setTrackingMode(command.mode) }
                applyTracking()
                repository.sendResult(id, Status.STATUS_COMPLETED)
                sendStatus()
            }

            is AgentCommand.LostMode -> lostMode(id, command.enabled, command.contact)

            is AgentCommand.Lock -> lock(id, command.contact)

            is AgentCommand.Wipe -> wipe(id, command.includeExternalStorage)

            is AgentCommand.Unpair -> {
                repository.sendResult(id, Status.STATUS_COMPLETED)
                repository.forget(revokeOnRelay = true)
                stopSelf()
            }

            is AgentCommand.Status -> {
                sendStatus()
                repository.sendResult(id, Status.STATUS_COMPLETED)
            }

            is AgentCommand.CapturePhoto -> repository.sendResult(id, Status.STATUS_UNSUPPORTED, "not_implemented")
        }
    }

    private suspend fun locate(id: ByteArray, highAccuracy: Boolean) {
        if (!device.hasLocation()) {
            repository.sendResult(id, Status.STATUS_FAILED, "location_permission_denied")
            return
        }
        val fix = locator.current(highAccuracy)
        if (fix == null) {
            repository.sendResult(id, Status.STATUS_FAILED, "location_unavailable")
            return
        }
        sendLocation(fix)
        repository.sendResult(id, Status.STATUS_COMPLETED)
    }

    private suspend fun lostMode(id: ByteArray, enabled: Boolean, contact: ContactCard?) {
        updateSettings {
            it.setLostMode(enabled)
                .setTrackingMode(
                    if (enabled) TrackingMode.TRACKING_MODE_LOST_VALUE else TrackingMode.TRACKING_MODE_STANDBY_VALUE,
                )
                .setContactMessage(contact?.message.orEmpty())
                .setContactPhone(contact?.phone.orEmpty())
                .setContactEmail(contact?.email.orEmpty())
        }
        applyTracking()
        if (enabled) showLost() else getSystemService<NotificationManager>()?.cancel(Notifications.ID_LOST)
        repository.sendResult(id, Status.STATUS_COMPLETED)
        sendStatus()
    }

    private suspend fun lock(id: ByteArray, contact: ContactCard?) {
        if (!device.isAdminActive()) {
            repository.sendResult(id, Status.STATUS_FAILED, "device_admin_inactive")
            return
        }
        if (contact != null) {
            updateSettings {
                it.setContactMessage(contact.message).setContactPhone(contact.phone).setContactEmail(contact.email)
            }
            showLost()
        }
        getSystemService<DevicePolicyManager>()?.lockNow()
        repository.sendResult(id, Status.STATUS_COMPLETED)
    }

    private suspend fun wipe(id: ByteArray, external: Boolean) {
        val dpm = getSystemService<DevicePolicyManager>()
        if (dpm == null || !device.isAdminActive()) {
            repository.sendResult(id, Status.STATUS_FAILED, "device_admin_inactive")
            return
        }
        // Report first: after a successful wipe nothing can be sent any more.
        repository.sendResult(id, Status.STATUS_ACCEPTED)
        try {
            dpm.wipeData(if (external) DevicePolicyManager.WIPE_EXTERNAL_STORAGE else 0)
        } catch (e: SecurityException) {
            // Android 14+ restricts wipeData for device admins that are not device owners.
            Log.w(TAG, "wipe refused by the OS: ${e.javaClass.simpleName}")
            repository.sendResult(id, Status.STATUS_FAILED, "wipe_not_permitted")
        }
    }

    private fun applyTracking() {
        val mode = repository.state.value.settings.trackingMode
        if (mode == appliedTracking) return
        appliedTracking = mode
        val (interval, high) = when (mode) {
            TrackingMode.TRACKING_MODE_ACTIVE_VALUE -> ACTIVE_INTERVAL_MS to true
            TrackingMode.TRACKING_MODE_LOST_VALUE -> LOST_INTERVAL_MS to true
            else -> STANDBY_INTERVAL_MS to false
        }
        locator.track(interval, high) { fix ->
            val now = System.currentTimeMillis()
            if (now - lastTrackedFixMs >= interval / 2) {
                lastTrackedFixMs = now
                scope.launch { sendLocation(fix) }
            }
        }
    }

    private fun showLost() {
        getSystemService<NotificationManager>()?.notify(Notifications.ID_LOST, Notifications.lost(this))
        runCatching {
            startActivity(Intent(this, LostModeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private suspend fun updateSettings(
        change: (
            org.bastion.core.agent.state.ProtectionSettings.Builder,
        ) -> org.bastion.core.agent.state.ProtectionSettings.Builder,
    ) {
        repository.transact { s -> s.toBuilder().setSettings(change(s.settings.toBuilder())).build() to Unit }
    }

    private suspend fun sendLocation(fix: Location) {
        repository.sendEvent(
            Event.newBuilder().setLocation(LocationReport.newBuilder().addLocations(Locator.toProto(fix))).build(),
        )
    }

    private suspend fun sendStatus() {
        val settings = repository.state.value.settings
        val status = StatusReport.newBuilder()
            .setBattery(device.battery())
            .setNetwork(device.network())
            .setTrackingModeValue(
                if (settings.trackingMode == 0) TrackingMode.TRACKING_MODE_STANDBY_VALUE else settings.trackingMode,
            )
            .setLostMode(settings.lostMode)
            .addAllHealth(device.protoHealth())
            .setAppVersion(device.appVersion)
            .build()
        repository.sendEvent(Event.newBuilder().setStatus(status).build())
    }

    private suspend fun sendBeacon(reason: LastChanceBeacon.Reason) {
        val beacon = LastChanceBeacon.newBuilder().setReason(reason).setBattery(device.battery())
        locator.lastKnown()?.let { beacon.setLocation(Locator.toProto(it)) }
        repository.sendEvent(Event.newBuilder().setLastChance(beacon).build())
    }

    companion object {
        private const val TAG = "ProtectionService"
        const val ACTION_STOP_RING = "org.bastion.mobile.STOP_RING"
        private const val POLL_WAIT_S = 25
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 120_000L
        private const val MAX_UNAUTHORIZED = 3
        private const val STATUS_PERIOD_MS = 30 * 60_000L
        private const val STANDBY_INTERVAL_MS = 30 * 60_000L
        private const val ACTIVE_INTERVAL_MS = 60_000L
        private const val LOST_INTERVAL_MS = 30_000L
        private const val BEACON_TIMEOUT_MS = 4_000L

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, ProtectionService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "service not started: ${it.javaClass.simpleName}") }
        }
    }
}
