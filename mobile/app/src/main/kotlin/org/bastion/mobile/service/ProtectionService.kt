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
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
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
import org.bastion.protocol.v1.GlobalAction
import org.bastion.protocol.v1.LastChanceBeacon
import org.bastion.protocol.v1.LocationReport
import org.bastion.protocol.v1.MailboxItem
import org.bastion.protocol.v1.MediaFrame
import org.bastion.protocol.v1.PhotoReport
import org.bastion.protocol.v1.RemoteInput
import org.bastion.protocol.v1.ScreenFrame
import org.bastion.protocol.v1.SmsReceived
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

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, error -> Log.w(TAG, "uncaught: ${error.javaClass.simpleName}") },
    )

    // The camera is a single shared resource: captures and streams take it one at a time instead of
    // closing each other's session.
    private val cameraLock = Mutex()

    // Wakes the relay loop right away (network change, call ended) instead of waiting out a backoff.
    private val reconnect = Channel<Unit>(Channel.CONFLATED)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var device: DeviceState
    private lateinit var locator: Locator
    private lateinit var alarm: Alarm
    private lateinit var contactsReader: ContactsReader
    private lateinit var calls: CallController
    private lateinit var sms: SmsController
    private var loop: Job? = null
    private var periodic: Job? = null
    private var streamJob: Job? = null
    private var audioJob: Job? = null
    private var screenJob: Job? = null
    private var intercomJob: Job? = null
    private var playback: AudioPlayback? = null
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

    // Live incoming texts, forwarded to the paired controller only (system-only broadcast).
    private val smsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!::sms.isInitialized || !sms.canRead()) return
            val parts = android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
            val first = parts.firstOrNull() ?: return
            val body = parts.joinToString("") { it.messageBody.orEmpty() }
            val address = first.originatingAddress.orEmpty()
            val atMs = first.timestampMillis
            scope.launch {
                // The contact lookup is a content-provider query: never on the receiver's main thread.
                val message = sms.incoming(address, body, atMs)
                repository.sendEvent(
                    Event.newBuilder().setSmsReceived(SmsReceived.newBuilder().setMessage(message)).build(),
                )
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
        contactsReader = ContactsReader(this)
        calls = CallController(this, contactsReader) { state ->
            if (state.state == org.bastion.protocol.v1.CallState.State.STATE_IDLE) wakeRelayLoop()
            scope.launch { repository.sendEvent(Event.newBuilder().setCallState(state).build()) }
        }
        sms = SmsController(this, contactsReader)
        calls.start()
        watchNetwork()
        ContextCompat.registerReceiver(
            this,
            smsReceiver,
            IntentFilter(android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION),
            android.Manifest.permission.BROADCAST_SMS,
            null,
            ContextCompat.RECEIVER_EXPORTED,
        )
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
        unregisterReceiver(smsReceiver)
        networkCallback?.let { runCatching { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(it) } }
        networkCallback = null
        calls.stop()
        alarm.stop()
        locator.stopTracking()
        streamJob?.cancel()
        audioJob?.cancel()
        screenJob?.cancel()
        intercomJob?.cancel()
        playback?.release()
        playback = null
        RemoteInputService.instance?.setKeepAwake(false)
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
                try {
                    process(batch.itemsList)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A command that cannot be processed must not be redelivered forever, nor stop the loop.
                    Log.w(TAG, "processing failed: ${e.javaClass.simpleName}")
                }
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
                // Wait out the backoff, but wake at once when the network changes or a call ends.
                withTimeoutOrNull(backoffMs) { reconnect.receive() }
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "relay loop error: ${e.javaClass.simpleName}")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    /** Wakes the relay loop at once so it does not sit out a backoff. Healthy requests are left alone. */
    private fun wakeRelayLoop() {
        reconnect.trySend(Unit)
    }

    /** The network changed: requests opened on the old one would hang, so drop them and reconnect now. */
    private fun networkChanged() {
        scope.launch { runCatching { repository.client()?.cancelInFlight() } }
        wakeRelayLoop()
    }

    private fun watchNetwork() {
        val manager = getSystemService<ConnectivityManager>() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = networkChanged()

            override fun onLost(network: Network) = networkChanged()
        }
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "network callback not registered: ${it.javaClass.simpleName}") }
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
            received.commands.forEach { command ->
                scope.launch {
                    try {
                        execute(command)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "command failed: ${e.javaClass.simpleName}")
                        repository.sendResult(command.messageId, Status.STATUS_FAILED, "internal_error")
                    }
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun execute(command: AgentCommand) {
        val id = command.messageId
        // Remote input and voice chunks fire several times a second; journaling each would be noise.
        if (command !is AgentCommand.Input && command !is AgentCommand.AudioPlay) {
            repository.log("command.${command.javaClass.simpleName.lowercase()}")
        }
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

            is AgentCommand.Screen -> screen(command)

            is AgentCommand.Input -> input(command)

            is AgentCommand.Intercom -> intercom(command)

            is AgentCommand.AudioPlay -> playback?.play(command.pcm, command.sampleRate)

            is AgentCommand.Contacts -> contacts(command)

            is AgentCommand.SmsSend -> {
                val failure = sms.send(command.to, command.body)
                repository.sendResult(
                    command.messageId,
                    if (failure == null) Status.STATUS_COMPLETED else Status.STATUS_FAILED,
                    failure.orEmpty(),
                )
            }

            is AgentCommand.SmsList -> {
                val page = sms.page(command.peer, command.limit)
                if (page == null) {
                    repository.sendResult(command.messageId, Status.STATUS_FAILED, "sms_permission_denied")
                } else {
                    repository.sendEvent(Event.newBuilder().setSmsPage(page).build())
                    repository.sendResult(command.messageId, Status.STATUS_COMPLETED)
                }
            }

            is AgentCommand.Call -> {
                calls.start()
                calls.handle(command.control)
            }
        }
    }

    /** Sends one page of the address book, or a refusal when the owner has not granted Contacts. */
    private suspend fun contacts(command: AgentCommand.Contacts) {
        val page = contactsReader.page(command.query, command.offset, command.limit)
        if (page == null) {
            repository.sendResult(command.messageId, Status.STATUS_FAILED, "contacts_permission_denied")
            return
        }
        repository.sendEvent(Event.newBuilder().setContactsPage(page).build())
        repository.sendResult(command.messageId, Status.STATUS_COMPLETED)
    }

    /** Two-way voice: streams the mic to the controller and opens the speaker for their voice. */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun intercom(command: AgentCommand.Intercom) {
        intercomJob?.cancel()
        if (!command.enabled) {
            playback?.release()
            playback = null
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
        playback = AudioPlayback()
        intercomJob = scope.launch {
            repository.sendResult(command.messageId, Status.STATUS_ACCEPTED)
            if (!capture.start()) {
                playback?.release()
                playback = null
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
                playback?.release()
                playback = null
                goForeground()
            }
        }
    }

    private fun screen(command: AgentCommand.Screen) {
        screenJob?.cancel()
        if (!command.enabled) {
            RemoteInputService.instance?.setKeepAwake(false)
            scope.launch { repository.sendResult(command.messageId, Status.STATUS_COMPLETED) }
            return
        }
        val service = RemoteInputService.instance
        if (service == null || !service.canCapture()) {
            scope.launch { repository.sendResult(command.messageId, Status.STATUS_FAILED, "accessibility_disabled") }
            return
        }
        screenJob = scope.launch {
            repository.sendResult(command.messageId, Status.STATUS_ACCEPTED)
            if (command.keepAwake) service.setKeepAwake(true)
            // takeScreenshot is rate-limited by the OS to about one per second.
            val frameIntervalMs = (MILLIS_PER_SECOND / command.fps).coerceAtLeast(SCREEN_MIN_INTERVAL_MS)
            val endAt = System.currentTimeMillis() + command.durationSeconds * MILLIS_PER_SECOND
            var sequence = 0L
            try {
                while (isActive && System.currentTimeMillis() < endAt) {
                    val started = System.currentTimeMillis()
                    val frame = service.capture(command.edgePx, SCREEN_JPEG_QUALITY)
                    if (frame != null && !sendScreenFrame(sequence++, frame)) break
                    val elapsed = System.currentTimeMillis() - started
                    delay((frameIntervalMs - elapsed).coerceAtLeast(0))
                }
            } finally {
                service.setKeepAwake(false)
            }
        }
    }

    private suspend fun sendScreenFrame(sequence: Long, frame: CapturedScreen): Boolean {
        val screen = ScreenFrame.newBuilder()
            .setSequence(sequence)
            .setCapturedAtMs(System.currentTimeMillis())
            .setJpeg(com.google.protobuf.ByteString.copyFrom(frame.jpeg))
            .setWidth(frame.width)
            .setHeight(frame.height)
            .setLocked(device.isKeyguardLocked())
            .build()
        return repository.sendEvent(Event.newBuilder().setScreenFrame(screen).build())
    }

    /** Injects a remote-input action. While a secure keyguard shows, only WAKE is honoured. */
    private fun input(command: AgentCommand.Input) {
        val service = RemoteInputService.instance ?: return
        val isWake = command.action.actionCase == RemoteInput.ActionCase.GLOBAL &&
            command.action.global == GlobalAction.GLOBAL_ACTION_WAKE
        if (device.isKeyguardLocked() && !isWake) return
        service.inject(command.action)
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

    /** Waits for the camera for at most [CAMERA_WAIT_MS]. Returns false if another user kept it. */
    private suspend fun acquireCamera(): Boolean = withTimeoutOrNull(CAMERA_WAIT_MS) {
        cameraLock.lock()
        true
    } ?: false

    /** Captures and sends one photo. `commandId` is null for an automatic intrusion photo. */
    private suspend fun capturePhoto(commandId: ByteArray?, cameraValue: Int, trigger: PhotoReport.Trigger) {
        if (!device.hasCamera()) {
            commandId?.let { repository.sendResult(it, Status.STATUS_FAILED, "camera_permission_denied") }
            return
        }
        // One camera user at a time: a second request waits for the first instead of closing its session.
        // Only the wait is time-limited; the capture itself is bounded by its own attempts, so a timeout
        // can never cancel it half-way and leave the camera bound.
        if (!acquireCamera()) {
            commandId?.let { repository.sendResult(it, Status.STATUS_FAILED, "camera_busy") }
            return
        }
        val image = try {
            shoot(cameraValue)
        } finally {
            cameraLock.unlock()
        }
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
        val sent = repository.sendEvent(Event.newBuilder().setPhoto(report).build())
        commandId?.let {
            val status = if (sent) Status.STATUS_COMPLETED else Status.STATUS_FAILED
            repository.sendResult(it, status, if (sent) "" else "relay_unreachable")
        }
    }

    /** Opens the camera, takes one photo (retrying while the camera warms up) and closes it again. */
    private suspend fun shoot(cameraValue: Int): CapturedImage? {
        goForeground(camera = true)
        try {
            repeat(CAPTURE_ATTEMPTS) {
                val camera = CameraCapture(this)
                val image = try {
                    if (camera.start(cameraValue, PHOTO_EDGE_PX)) camera.capture() else null
                } finally {
                    release(camera)
                }
                if (image != null) return image
                delay(CAPTURE_RETRY_MS)
            }
            return null
        } finally {
            goForeground()
        }
    }

    /** Unbinds and frees a camera even when the calling coroutine is being cancelled. */
    private suspend fun release(camera: CameraCapture) = withContext(NonCancellable) {
        camera.stop()
        camera.shutdown()
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
            // The previous stream (if any) is still releasing the camera: wait for it, do not fail.
            if (!acquireCamera()) {
                goForeground()
                repository.sendResult(command.messageId, Status.STATUS_FAILED, "camera_busy")
                return@launch
            }
            try {
                streamFrames(command)
            } finally {
                goForeground()
                cameraLock.unlock()
            }
        }
    }

    private suspend fun streamFrames(command: AgentCommand.Stream) {
        val camera = CameraCapture(this@ProtectionService)
        if (!camera.start(command.camera, command.edgePx)) {
            release(camera)
            repository.sendResult(command.messageId, Status.STATUS_FAILED, "camera_unavailable")
            return
        }
        val frameIntervalMs = (MILLIS_PER_SECOND / command.fps).coerceAtLeast(MIN_FRAME_MS)
        val endAt = System.currentTimeMillis() + command.durationSeconds * MILLIS_PER_SECOND
        var sequence = 0L
        try {
            while (currentCoroutineContext().isActive && System.currentTimeMillis() < endAt) {
                val started = System.currentTimeMillis()
                val frame = camera.capture()
                if (frame != null && !sendFrame(command.camera, sequence++, frame)) break
                val elapsed = System.currentTimeMillis() - started
                delay((frameIntervalMs - elapsed).coerceAtLeast(0))
            }
        } finally {
            release(camera)
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
        private const val CAMERA_WAIT_MS = 20_000L
        private const val CAPTURE_ATTEMPTS = 3
        private const val CAPTURE_RETRY_MS = 600L
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 15_000L
        private const val MAX_UNAUTHORIZED = 3
        private const val STATUS_PERIOD_MS = 30 * 60_000L
        private const val STANDBY_INTERVAL_MS = 30 * 60_000L
        private const val ACTIVE_INTERVAL_MS = 60_000L
        private const val LOST_INTERVAL_MS = 30_000L
        private const val BEACON_TIMEOUT_MS = 4_000L
        private const val PHOTO_EDGE_PX = 1280
        private const val MILLIS_PER_SECOND = 1000L
        private const val MIN_FRAME_MS = 100L

        /** takeScreenshot is OS-rate-limited to ~1 Hz; do not request frames faster. */
        private const val SCREEN_MIN_INTERVAL_MS = 1000L
        private const val SCREEN_JPEG_QUALITY = 70

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, ProtectionService::class.java).setAction(action)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "service not started: ${it.javaClass.simpleName}") }
        }
    }
}
