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
import org.bastion.core.agent.Geofencing
import org.bastion.core.agent.PhoneAgent
import org.bastion.core.domain.model.ContactCard
import org.bastion.mobile.data.AgentRepository
import org.bastion.mobile.data.RelayException
import org.bastion.mobile.ui.LostModeActivity
import org.bastion.protocol.v1.Alert
import org.bastion.protocol.v1.AudioChunk
import org.bastion.protocol.v1.CapturePhoto
import org.bastion.protocol.v1.CommandResult.Status
import org.bastion.protocol.v1.Event
import org.bastion.protocol.v1.LastChanceBeacon
import org.bastion.protocol.v1.LocationReport
import org.bastion.protocol.v1.MailboxItem
import org.bastion.protocol.v1.MediaFrame
import org.bastion.protocol.v1.PhotoReport
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
    private var streamJob: Job? = null
    private var audioJob: Job? = null
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
        when (intent?.action) {
            ACTION_STOP_RING -> {
                alarm.stop()
                getSystemService<NotificationManager>()?.cancel(Notifications.ID_ALARM)
            }

            ACTION_INTRUSION_PHOTO -> if (agent.isActive(repository.state.value)) {
                scope.launch {
                    capturePhoto(
                        null,
                        CapturePhoto.Camera.CAMERA_FRONT_VALUE,
                        PhotoReport.Trigger.TRIGGER_FAILED_UNLOCK,
                    )
                }
            }
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(systemEvents)
        alarm.stop()
        locator.stopTracking()
        streamJob?.cancel()
        audioJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(camera: Boolean = false, mic: Boolean = false) {
        val active = agent.isActive(repository.state.value)
        val notification = Notifications.protection(this, active)
        var type = when {
            device.hasLocation() -> ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE

            else -> 0
        }
        // The camera/microphone types are required to use them from the background on Android 11+.
        if (camera && device.hasCamera() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (mic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_PROTECTION, notification, type)
        } catch (e: SecurityException) {
            // A declared type was refused (e.g. started from the background without that access).
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
                checkSim()
                if (repository.shouldRotate()) repository.rotateKeys()
                sendStatus()
                delay(STATUS_PERIOD_MS)
            }
        }
        if (repository.state.value.settings.lostMode) showLost()
    }

    /** Detects a SIM swap or removal (a thief changing the SIM) and alerts the controller. */
    private suspend fun checkSim() {
        val current = device.simOperator()
        val stored = repository.state.value.settings.simOperator
        if (stored == current) return
        updateSettings { it.setSimOperator(current) }
        if (stored.isEmpty()) return // First observation: just record it, no alert.
        val (type, detail) = if (current.isEmpty()) {
            Alert.Type.TYPE_SIM_REMOVED to emptyMap()
        } else {
            Alert.Type.TYPE_SIM_CHANGED to mapOf("operator" to device.simOperatorName().ifBlank { current })
        }
        val alert = Alert.newBuilder().setType(type).putAllDetail(detail)
        locator.lastKnown()?.let { alert.setLocation(Locator.toProto(it)) }
        repository.sendEvent(Event.newBuilder().setAlert(alert).build())
        repository.log("alert.${type.name}")
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

            is AgentCommand.CapturePhoto ->
                capturePhoto(id, command.camera, PhotoReport.Trigger.TRIGGER_ON_DEMAND)

            is AgentCommand.Stream -> stream(command)

            is AgentCommand.Audio -> audio(command)

            is AgentCommand.SetGeofences -> setGeofences(command)
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun audio(command: AgentCommand.Audio) {
        audioJob?.cancel()
        if (!command.enabled) {
            goForeground()
            scope.launch { repository.sendResult(command.messageId, Status.STATUS_COMPLETED) }
            return
        }
        val capture = AudioCapture(this)
        if (!capture.hasPermission()) {
            scope.launch { repository.sendResult(command.messageId, Status.STATUS_FAILED, "mic_permission_denied") }
            return
        }
        goForeground(mic = true)
        audioJob = scope.launch {
            repository.sendResult(command.messageId, Status.STATUS_ACCEPTED)
            if (!capture.start()) {
                goForeground()
                repository.sendResult(command.messageId, Status.STATUS_FAILED, "mic_unavailable")
                return@launch
            }
            val endAt = System.currentTimeMillis() + command.durationSeconds * MILLIS_PER_SECOND
            var sequence = 0L
            try {
                while (isActive && System.currentTimeMillis() < endAt) {
                    val chunk = capture.read() ?: break
                    if (!sendAudio(sequence++, chunk)) break
                }
            } finally {
                capture.stop()
                goForeground()
            }
        }
    }

    private suspend fun sendAudio(sequence: Long, chunk: AudioChunkData): Boolean {
        val audio = AudioChunk.newBuilder()
            .setSequence(sequence)
            .setCapturedAtMs(System.currentTimeMillis())
            .setPcm(com.google.protobuf.ByteString.copyFrom(chunk.pcm))
            .setSampleRate(chunk.sampleRate)
            .build()
        return repository.sendEvent(Event.newBuilder().setAudioChunk(audio).build())
    }

    /** Captures and sends one photo. `commandId` is null for an automatic intrusion photo. */
    private suspend fun capturePhoto(commandId: ByteArray?, cameraValue: Int, trigger: PhotoReport.Trigger) {
        if (!device.hasCamera()) {
            commandId?.let { repository.sendResult(it, Status.STATUS_FAILED, "camera_permission_denied") }
            return
        }
        goForeground(camera = true)
        val camera = CameraCapture(this)
        val image = try {
            if (camera.start(cameraValue, PHOTO_EDGE_PX)) camera.capture() else null
        } finally {
            camera.stop()
            camera.shutdown()
        }
        goForeground()
        if (image == null) {
            commandId?.let { repository.sendResult(it, Status.STATUS_FAILED, "camera_unavailable") }
            return
        }
        val report = PhotoReport.newBuilder()
            .setJpeg(com.google.protobuf.ByteString.copyFrom(image.jpeg))
            .setCapturedAtMs(System.currentTimeMillis())
            .setCameraValue(cameraValue)
            .setTrigger(trigger)
            .apply {
                commandId?.let { setCommandMessageId(com.google.protobuf.ByteString.copyFrom(it)) }
                locator.lastKnown()?.let { setLocation(Locator.toProto(it)) }
            }
            .build()
        repository.sendEvent(Event.newBuilder().setPhoto(report).build())
        commandId?.let { repository.sendResult(it, Status.STATUS_COMPLETED) }
    }

    private fun stream(command: AgentCommand.Stream) {
        streamJob?.cancel()
        if (!command.enabled) {
            goForeground()
            scope.launch { repository.sendResult(command.messageId, Status.STATUS_COMPLETED) }
            return
        }
        if (!device.hasCamera()) {
            scope.launch { repository.sendResult(command.messageId, Status.STATUS_FAILED, "camera_permission_denied") }
            return
        }
        goForeground(camera = true)
        streamJob = scope.launch {
            repository.sendResult(command.messageId, Status.STATUS_ACCEPTED)
            val camera = CameraCapture(this@ProtectionService)
            if (!camera.start(command.camera, command.edgePx)) {
                camera.shutdown()
                goForeground()
                repository.sendResult(command.messageId, Status.STATUS_FAILED, "camera_unavailable")
                return@launch
            }
            val frameIntervalMs = (MILLIS_PER_SECOND / command.fps).coerceAtLeast(MIN_FRAME_MS)
            val endAt = System.currentTimeMillis() + command.durationSeconds * MILLIS_PER_SECOND
            var sequence = 0L
            try {
                while (isActive && System.currentTimeMillis() < endAt) {
                    val started = System.currentTimeMillis()
                    val frame = camera.capture()
                    if (frame != null && !sendFrame(command.camera, sequence++, frame)) break
                    val elapsed = System.currentTimeMillis() - started
                    delay((frameIntervalMs - elapsed).coerceAtLeast(0))
                }
            } finally {
                camera.stop()
                camera.shutdown()
                goForeground()
            }
        }
    }

    private suspend fun sendFrame(cameraValue: Int, sequence: Long, frame: CapturedImage): Boolean {
        val media = MediaFrame.newBuilder()
            .setSequence(sequence)
            .setCapturedAtMs(System.currentTimeMillis())
            .setCameraValue(cameraValue)
            .setJpeg(com.google.protobuf.ByteString.copyFrom(frame.jpeg))
            .setWidth(frame.width)
            .setHeight(frame.height)
            .build()
        return repository.sendEvent(Event.newBuilder().setMediaFrame(media).build())
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
        evaluateGeofences(fix)
    }

    private suspend fun setGeofences(command: AgentCommand.SetGeofences) {
        val local = Geofencing.fromWire(command.zones)
        val fix = locator.lastKnown()
        val initialized = if (fix != null) Geofencing.evaluate(local, fix.latitude, fix.longitude).first else local
        updateSettings { it.clearGeofences().addAllGeofences(initialized) }
        repository.sendResult(command.messageId, Status.STATUS_COMPLETED)
    }

    private suspend fun evaluateGeofences(fix: Location) {
        val zones = repository.state.value.settings.geofencesList
        if (zones.isEmpty()) return
        val (updated, transitions) = Geofencing.evaluate(zones, fix.latitude, fix.longitude)
        if (updated != zones) {
            updateSettings { it.clearGeofences().addAllGeofences(updated) }
        }
        for (transition in transitions) {
            val type = if (transition.entered) {
                Alert.Type.TYPE_GEOFENCE_ENTER
            } else {
                Alert.Type.TYPE_GEOFENCE_EXIT
            }
            val alert = Alert.newBuilder()
                .setType(type)
                .putDetail("id", transition.id)
                .setLocation(Locator.toProto(fix))
            repository.sendEvent(Event.newBuilder().setAlert(alert).build())
            repository.log("alert.${type.name}")
        }
        // Leaving a zone bumps tracking to active (unless already in lost mode).
        if (transitions.any { !it.entered } && !repository.state.value.settings.lostMode) {
            updateSettings { it.setTrackingMode(TrackingMode.TRACKING_MODE_ACTIVE_VALUE) }
            applyTracking()
        }
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
        const val ACTION_INTRUSION_PHOTO = "org.bastion.mobile.INTRUSION_PHOTO"

        /** Failed unlock attempts before an intrusion photo is taken. */
        const val INTRUSION_ATTEMPTS = 3
        private const val POLL_WAIT_S = 25
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 120_000L
        private const val MAX_UNAUTHORIZED = 3
        private const val STATUS_PERIOD_MS = 30 * 60_000L
        private const val STANDBY_INTERVAL_MS = 30 * 60_000L
        private const val ACTIVE_INTERVAL_MS = 60_000L
        private const val LOST_INTERVAL_MS = 30_000L
        private const val BEACON_TIMEOUT_MS = 4_000L
        private const val PHOTO_EDGE_PX = 1280
        private const val MILLIS_PER_SECOND = 1000L
        private const val MIN_FRAME_MS = 100L

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, ProtectionService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "service not started: ${it.javaClass.simpleName}") }
        }
    }
}
