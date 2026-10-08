package org.bastion.core.agent

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import java.net.URI
import java.util.Base64
import org.bastion.core.agent.state.AgentState
import org.bastion.core.agent.state.Pairing
import org.bastion.core.agent.state.SeenId
import org.bastion.core.crypto.Bytes
import org.bastion.core.crypto.DeviceKeys
import org.bastion.core.crypto.EnvelopeCrypto
import org.bastion.core.crypto.EnvelopeHeader
import org.bastion.core.crypto.ExchangeKeypair
import org.bastion.core.crypto.Identity
import org.bastion.core.crypto.KeySizes
import org.bastion.core.crypto.OpenedEnvelope
import org.bastion.core.crypto.Party
import org.bastion.core.crypto.ReplayWindow
import org.bastion.core.crypto.Session
import org.bastion.core.crypto.SigningKeypair
import org.bastion.core.crypto.Sodium
import org.bastion.core.domain.model.ContactCard
import org.bastion.core.domain.model.getOrNull
import org.bastion.core.protocol.EnvelopeCodec
import org.bastion.core.protocol.ProtocolLimits
import org.bastion.protocol.v1.Command
import org.bastion.protocol.v1.CommandResult
import org.bastion.protocol.v1.EnrollRequest
import org.bastion.protocol.v1.EnrollResponse
import org.bastion.protocol.v1.Event
import org.bastion.protocol.v1.KeyRotation
import org.bastion.protocol.v1.MessageBody
import org.bastion.protocol.v1.PairingConfirm
import org.bastion.protocol.v1.PairingHello
import org.bastion.protocol.v1.PairingInvite
import org.bastion.protocol.v1.Role

/** A command decoded from an authenticated, fresh, non-replayed message. */
public sealed interface AgentCommand {
    public val messageId: ByteArray

    public class Ring(
        override val messageId: ByteArray,
        public val durationSeconds: Int,
        public val flashlight: Boolean,
        public val vibrate: Boolean,
    ) : AgentCommand

    public class StopRing(override val messageId: ByteArray) : AgentCommand

    public class Locate(override val messageId: ByteArray, public val highAccuracy: Boolean) : AgentCommand

    public class Tracking(override val messageId: ByteArray, public val mode: Int) : AgentCommand

    public class LostMode(
        override val messageId: ByteArray,
        public val enabled: Boolean,
        public val contact: ContactCard?,
    ) : AgentCommand

    /** Sensitive: only produced when the privileged counter-signature verified. */
    public class Lock(override val messageId: ByteArray, public val contact: ContactCard?) : AgentCommand

    /** Sensitive. */
    public class Wipe(override val messageId: ByteArray, public val includeExternalStorage: Boolean) : AgentCommand

    /** Sensitive. */
    public class Unpair(override val messageId: ByteArray) : AgentCommand

    public class Status(override val messageId: ByteArray) : AgentCommand

    public class CapturePhoto(override val messageId: ByteArray, public val camera: Int) : AgentCommand

    /** Start or stop a near-live camera stream. Parameters are clamped to safe ranges. */
    public class Stream(
        override val messageId: ByteArray,
        public val enabled: Boolean,
        public val camera: Int,
        public val fps: Int,
        public val edgePx: Int,
        public val durationSeconds: Int,
    ) : AgentCommand

    /** Start or stop a near-live microphone stream. */
    public class Audio(
        override val messageId: ByteArray,
        public val enabled: Boolean,
        public val durationSeconds: Int,
    ) : AgentCommand

    /** Replaces the watched geofences (empty list disables geofencing). */
    public class SetGeofences(
        override val messageId: ByteArray,
        public val zones: List<org.bastion.protocol.v1.Geofence>,
    ) : AgentCommand

    /** Start or stop a near-live mirror of the device's own screen. */
    public class Screen(
        override val messageId: ByteArray,
        public val enabled: Boolean,
        public val fps: Int,
        public val edgePx: Int,
        public val durationSeconds: Int,
        public val keepAwake: Boolean,
    ) : AgentCommand

    /** A single remote-input action to inject via the accessibility service (fire-and-forget). */
    public class Input(override val messageId: ByteArray, public val action: org.bastion.protocol.v1.RemoteInput) :
        AgentCommand
}

/** Outcome of an invitation scan. */
public sealed interface InviteParse {
    public class Valid(public val invite: PairingInvite) : InviteParse

    public data class Invalid(val reason: Reason) : InviteParse

    public enum class Reason { MALFORMED, EXPIRED, UNSUPPORTED_VERSION, BAD_SIGNATURE, INSECURE_ENDPOINT }
}

/** Enrollment material: the request for the relay and the state to persist once it succeeds. */
public class Enrollment(public val request: EnrollRequest, public val state: AgentState, public val sas: Int)

/** Result of processing one envelope. */
public class Received(
    /** New state, to persist BEFORE executing [commands] (anti-replay, PROTOCOL.md §6.9). */
    public val state: AgentState,
    public val commands: List<AgentCommand>,
    /** Envelopes to send back (e.g. rejection results). */
    public val replies: List<ByteArray>,
    /** The controller refused the pairing: the caller must forget it. */
    public val pairingRejected: Boolean = false,
)

/**
 * Phone-side protocol engine (PROTOCOL.md §3–7). Pure and deterministic apart from randomness:
 * every function takes the current [AgentState] and returns the next one.
 */
@Suppress("TooManyFunctions")
public class PhoneAgent(private val sodium: Sodium) {
    public fun isPaired(state: AgentState): Boolean = state.hasPairing() && state.pairing.enrolled

    public fun isActive(state: AgentState): Boolean =
        isPaired(state) && state.pairing.localConfirmed && state.pairing.remoteConfirmed

    /** Identity key of the current pairing (caller closes it). */
    public fun identity(state: AgentState): SigningKeypair =
        SigningKeypair(sodium, state.pairing.identitySeed.toByteArray())

    public fun deviceId(state: AgentState): ByteArray = identity(state).use { it.deviceId }

    /** Parses and validates `bastion://pair/v1#…` (PROTOCOL.md §3.1). */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    public fun parseInvite(uri: String, nowMs: Long): InviteParse {
        val payload = uri.trim().removePrefix(URI_PREFIX)
        if (payload.length == uri.trim().length || payload.length > MAX_INVITE_CHARS) {
            return InviteParse.Invalid(InviteParse.Reason.MALFORMED)
        }
        val invite = decodeInvite(payload) ?: return InviteParse.Invalid(InviteParse.Reason.MALFORMED)
        if (invite.version != 1) return InviteParse.Invalid(InviteParse.Reason.UNSUPPORTED_VERSION)
        if (invite.expiresAtMs <= nowMs || invite.expiresAtMs - nowMs > INVITE_MAX_AHEAD_MS) {
            return InviteParse.Invalid(InviteParse.Reason.EXPIRED)
        }
        val wellFormed = invite.relayTlsSpkiSha256.size() == KeySizes.HASH &&
            invite.controllerPrivilegedPublicKey.size() == KeySizes.PUBLIC_KEY &&
            invite.token.size() == org.bastion.core.crypto.Pairing.TOKEN_BYTES &&
            invite.hasController()
        if (!wellFormed) return InviteParse.Invalid(InviteParse.Reason.MALFORMED)
        if (!isHttpsEndpoint(invite.relayHttpsEndpoint)) {
            return InviteParse.Invalid(InviteParse.Reason.INSECURE_ENDPOINT)
        }
        if (DeviceKeys.fromProto(sodium, invite.controller) == null) {
            return InviteParse.Invalid(InviteParse.Reason.BAD_SIGNATURE)
        }
        return InviteParse.Valid(invite)
    }

    /**
     * Generates fresh keys for this pairing and the enrollment request (proof, signature,
     * sealed `PairingHello`). The returned state is not enrolled until [markEnrolled].
     */
    public fun enroll(invite: PairingInvite, deviceLabel: String, nowMs: Long, previous: AgentState): Enrollment {
        val controller = requireNotNull(DeviceKeys.fromProto(sodium, invite.controller))
        val token = invite.token.toByteArray()
        SigningKeypair.generate(sodium).use { ik ->
            ExchangeKeypair.generate(sodium, 0).use { xk ->
                val keys = xk.signedBy(ik)
                val tokenHash = org.bastion.core.crypto.Pairing.tokenHash(sodium, token)
                val proof = org.bastion.core.crypto.Pairing.enrollProof(
                    sodium,
                    token,
                    keys.identity,
                    keys.exchange,
                    ByteArray(0),
                    controller.identity,
                )
                val transcript = org.bastion.core.crypto.Pairing.enrollTranscript(
                    tokenHash,
                    keys,
                    ByteArray(0),
                    proof,
                    Role.ROLE_PHONE_VALUE,
                )
                val hello = PairingHello.newBuilder()
                    .setDevice(keys.toProto())
                    .setProof(ByteString.copyFrom(proof))
                    .setDeviceLabel(sanitizeLabel(deviceLabel))
                    .setMaxProtocolVersion(ProtocolLimits.PROTOCOL_VERSION)
                    .build()
                val request = EnrollRequest.newBuilder()
                    .setTokenHash(ByteString.copyFrom(tokenHash))
                    .setDevice(keys.toProto())
                    .setProof(ByteString.copyFrom(proof))
                    .setRole(Role.ROLE_PHONE)
                    .setSignature(ByteString.copyFrom(ik.sign(transcript)))
                    .setSealedHello(ByteString.copyFrom(sodium.seal(controller.exchange, hello.toByteArray())))
                    .build()
                val sas = org.bastion.core.crypto.Pairing.sasCode(sodium, token, controller, keys)
                val pairing = Pairing.newBuilder()
                    .setIdentitySeed(ByteString.copyFrom(ik.exportSeed()))
                    .setExchangeSecret(ByteString.copyFrom(xk.exportSecret()))
                    .setExchangeEpoch(xk.epoch)
                    .setRelayUrl(invite.relayHttpsEndpoint.trimEnd('/'))
                    .setRelayPin(invite.relayTlsSpkiSha256)
                    .setControllerIdentity(ByteString.copyFrom(controller.identity))
                    .setControllerExchange(ByteString.copyFrom(controller.exchange))
                    .setControllerEpoch(controller.epoch)
                    .setControllerPrivileged(invite.controllerPrivilegedPublicKey)
                    .setControllerLabel(sanitizeLabel(invite.controllerLabel))
                    .setSas(sas)
                    .setProtocolVersion(ProtocolLimits.PROTOCOL_VERSION)
                    .setRecvWindow(ByteString.copyFrom(ReplayWindow().toBytes()))
                    .setPairedAtMs(nowMs)
                    .build()
                val state = previous.toBuilder().setPairing(pairing).build()
                return Enrollment(request, state, sas)
            }
        }
    }

    /** Accepts the relay response if it names this device and the inviting controller. */
    public fun markEnrolled(state: AgentState, response: EnrollResponse): AgentState? {
        val controllerId = Identity.deviceId(sodium, state.pairing.controllerIdentity.toByteArray())
        val ok = response.deviceId.toByteArray().contentEquals(deviceId(state)) &&
            response.controllerId.toByteArray().contentEquals(controllerId)
        return if (ok) state.toBuilder().setPairing(state.pairing.toBuilder().setEnrolled(true)).build() else null
    }

    /** Local SAS decision: returns the new state and the `PairingConfirm` envelope to send. */
    public fun confirm(state: AgentState, accept: Boolean, nowMs: Long): Pair<AgentState, ByteArray> {
        val confirm = PairingConfirm.newBuilder()
            .setConfirmed(accept)
            .setNegotiatedProtocolVersion(state.pairing.protocolVersion)
            .build()
        val (pairing, envelope) = seal(state.pairing, nowMs, CONFIRM_TTL_S) { it.setPairingConfirm(confirm) }
        val updated = pairing.toBuilder().setLocalConfirmed(accept).build()
        return state.toBuilder().setPairing(updated).build() to envelope
    }

    /** Builds an event envelope (only once the pairing is active). */
    public fun event(state: AgentState, event: Event, nowMs: Long): Pair<AgentState, ByteArray>? {
        if (!isActive(state)) return null
        val (pairing, envelope) = seal(state.pairing, nowMs, EVENT_TTL_S) { it.setEvent(event) }
        return state.toBuilder().setPairing(pairing).build() to envelope
    }

    /** Whether the X25519 key should be rotated (PROTOCOL.md §4; default every 7 days). */
    public fun shouldRotate(state: AgentState, nowMs: Long): Boolean {
        if (!isActive(state)) return false
        val last = state.pairing.lastRotationMs.takeIf { it > 0 } ?: state.pairing.pairedAtMs
        return nowMs - last >= ROTATION_INTERVAL_MS
    }

    /**
     * Rotates the X25519 key: the signed `KeyRotation` is sealed under the current epoch (so the
     * controller can still read it), then the new key becomes current for later messages.
     */
    public fun rotateExchangeKey(state: AgentState, nowMs: Long): Pair<AgentState, ByteArray>? {
        if (!isActive(state)) return null
        val newEpoch = state.pairing.exchangeEpoch + 1
        return ExchangeKeypair.generate(sodium, newEpoch).use { newXk ->
            SigningKeypair(sodium, state.pairing.identitySeed.toByteArray()).use { ik ->
                val keys = newXk.signedBy(ik)
                val rotation = KeyRotation.newBuilder()
                    .setNewEpoch(newEpoch)
                    .setX25519PublicKey(ByteString.copyFrom(keys.exchange))
                    .setX25519Signature(ByteString.copyFrom(keys.exchangeSignature))
                    .build()
                // Seal under the OLD key first.
                val (pairing, envelope) = seal(state.pairing, nowMs, EVENT_TTL_S) { it.setKeyRotation(rotation) }
                val updated = pairing.toBuilder()
                    .setExchangeSecret(ByteString.copyFrom(newXk.exportSecret()))
                    .setExchangeEpoch(newEpoch)
                    .setLastRotationMs(nowMs)
                    .build()
                state.toBuilder().setPairing(updated).build() to envelope
            }
        }
    }

    /** Builds a `CommandResult` envelope. */
    public fun result(
        state: AgentState,
        commandId: ByteArray,
        status: CommandResult.Status,
        reason: String,
        nowMs: Long,
    ): Pair<AgentState, ByteArray>? {
        if (!isPaired(state)) return null
        val result = CommandResult.newBuilder()
            .setCommandMessageId(ByteString.copyFrom(commandId))
            .setStatus(status)
            .setReasonCode(reason)
            .build()
        val (pairing, envelope) = seal(state.pairing, nowMs, EVENT_TTL_S) { it.setCommandResult(result) }
        return state.toBuilder().setPairing(pairing).build() to envelope
    }

    /**
     * Applies the reception rules of PROTOCOL.md §6 in order. Returns `null` for silently
     * dropped messages (steps 1–5, replays).
     */
    @Suppress("ReturnCount")
    public fun receive(state: AgentState, bytes: ByteArray, nowMs: Long): Received? {
        if (!isPaired(state)) return null
        val pairing = state.pairing
        val envelope = (EnvelopeCodec.decode(bytes) as? EnvelopeCodec.Result.Accepted)?.envelope ?: return null
        val controllerId = Identity.deviceId(sodium, pairing.controllerIdentity.toByteArray())
        val ownId = deviceId(state)
        val routedToUs = envelope.recipientId.toByteArray().contentEquals(ownId) &&
            envelope.senderId.toByteArray().contentEquals(controllerId) &&
            envelope.keyEpoch == pairing.controllerEpoch
        if (!routedToUs) return null
        val opened = open(pairing, envelope) ?: return null
        val body = parseBody(opened.body) ?: return null
        if (body.protocolVersion < pairing.protocolVersion || body.protocolVersion > ProtocolLimits.PROTOCOL_VERSION) {
            return null
        }
        if (!isActive(state) && !body.hasPairingConfirm()) return null
        // The counter is consumed even for stale messages, so a replayed stale command is
        // dropped silently instead of triggering a new rejection each time.
        val replay = acceptCounter(pairing, body, nowMs) ?: return null
        val accepted = state.toBuilder().setPairing(replay.setLastContactMs(nowMs)).build()
        if (!isFresh(body, nowMs)) return rejectStale(accepted, body, nowMs)
        return dispatch(accepted, body, opened.privileged, nowMs)
    }

    private fun open(pairing: Pairing, envelope: org.bastion.protocol.v1.Envelope): OpenedEnvelope? =
        ExchangeKeypair(sodium, pairing.exchangeSecret.toByteArray(), pairing.exchangeEpoch).use { xk ->
            val key = Session.directionalKey(sodium, xk, controllerParty(pairing), ownParty(pairing, xk))
                ?: return null
            key.use {
                EnvelopeCrypto.open(
                    sodium,
                    envelope,
                    listOf(it),
                    pairing.controllerIdentity.toByteArray(),
                    pairing.controllerPrivileged.toByteArray(),
                )
            }
        }

    private fun isFresh(body: MessageBody, nowMs: Long): Boolean {
        val ttlMs = effectiveTtlSeconds(body) * MILLIS
        return nowMs - SKEW_MS <= body.timestampMs + ttlMs && body.timestampMs <= nowMs + SKEW_MS
    }

    private fun rejectStale(state: AgentState, body: MessageBody, nowMs: Long): Received? {
        if (!body.hasCommand() || !isActive(state)) return null
        val reason = if (body.timestampMs > nowMs) "clock_skew" else "expired"
        val reply = result(state, body.messageId.toByteArray(), CommandResult.Status.STATUS_REJECTED, reason, nowMs)
            ?: return null
        return Received(reply.first, emptyList(), listOf(reply.second))
    }

    private fun acceptCounter(pairing: Pairing, body: MessageBody, nowMs: Long): Pairing.Builder? {
        val window = ReplayWindow.fromBytes(pairing.recvWindow.toByteArray()) ?: ReplayWindow()
        val seen = pairing.seenList.filter { it.expiresMs > nowMs }
        val id = body.messageId
        if (id.size() != MESSAGE_ID_BYTES || seen.any { it.id == id } || !window.accept(body.counter)) return null
        val expires = body.timestampMs + effectiveTtlSeconds(body) * MILLIS + SKEW_MS
        val kept = (seen + SeenId.newBuilder().setId(id).setExpiresMs(expires).build()).takeLast(MAX_SEEN)
        return pairing.toBuilder()
            .setRecvWindow(ByteString.copyFrom(window.toBytes()))
            .clearSeen()
            .addAllSeen(kept)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun dispatch(state: AgentState, body: MessageBody, privileged: Boolean, nowMs: Long): Received {
        val id = body.messageId.toByteArray()
        return when {
            body.hasPairingConfirm() -> {
                if (!body.pairingConfirm.confirmed) {
                    Received(state, emptyList(), emptyList(), pairingRejected = true)
                } else {
                    val pairing = state.pairing.toBuilder().setRemoteConfirmed(true).build()
                    Received(state.toBuilder().setPairing(pairing).build(), emptyList(), emptyList())
                }
            }

            body.hasCommand() -> {
                val command = decode(id, body.command)
                when {
                    command == null -> reply(
                        state,
                        id,
                        CommandResult.Status.STATUS_UNSUPPORTED,
                        "unknown_command",
                        nowMs,
                    )

                    isSensitive(command) && !privileged ->
                        reply(state, id, CommandResult.Status.STATUS_REJECTED, "privileged_signature_required", nowMs)

                    else -> Received(state, listOf(command), emptyList())
                }
            }

            body.hasKeyRotation() -> Received(applyRotation(state, body), emptyList(), emptyList())

            else -> Received(state, emptyList(), emptyList())
        }
    }

    private fun reply(
        state: AgentState,
        id: ByteArray,
        status: CommandResult.Status,
        reason: String,
        nowMs: Long,
    ): Received {
        val built = result(state, id, status, reason, nowMs) ?: return Received(state, emptyList(), emptyList())
        return Received(built.first, emptyList(), listOf(built.second))
    }

    private fun applyRotation(state: AgentState, body: MessageBody): AgentState {
        val rotation = body.keyRotation
        val keys = DeviceKeys(
            state.pairing.controllerIdentity.toByteArray(),
            rotation.x25519PublicKey.toByteArray(),
            rotation.x25519Signature.toByteArray(),
            rotation.newEpoch,
        )
        if (rotation.newEpoch <= state.pairing.controllerEpoch || !keys.verify(sodium)) return state
        val pairing = state.pairing.toBuilder()
            .setControllerExchange(rotation.x25519PublicKey)
            .setControllerEpoch(rotation.newEpoch)
            .build()
        return state.toBuilder().setPairing(pairing).build()
    }

    private fun seal(
        pairing: Pairing,
        nowMs: Long,
        ttlSeconds: Int,
        payload: (MessageBody.Builder) -> MessageBody.Builder,
    ): Pair<Pairing, ByteArray> {
        val counter = pairing.sendCounter + 1
        val body = payload(
            MessageBody.newBuilder()
                .setProtocolVersion(pairing.protocolVersion)
                .setMessageId(ByteString.copyFrom(sodium.randomBytes(MESSAGE_ID_BYTES)))
                .setCounter(counter)
                .setTimestampMs(nowMs)
                .setTtlSeconds(ttlSeconds),
        ).build()
        val envelope = SigningKeypair(sodium, pairing.identitySeed.toByteArray()).use { ik ->
            ExchangeKeypair(sodium, pairing.exchangeSecret.toByteArray(), pairing.exchangeEpoch).use { xk ->
                val key = requireNotNull(
                    Session.directionalKey(sodium, xk, ownParty(pairing, xk), controllerParty(pairing)),
                ) { "controller exchange key is invalid" }
                val header = EnvelopeHeader(
                    EnvelopeHeader.CURRENT_VERSION,
                    ik.deviceId,
                    Identity.deviceId(sodium, pairing.controllerIdentity.toByteArray()),
                    xk.epoch,
                )
                key.use { EnvelopeCrypto.seal(sodium, it, header, ik, null, body.toByteArray()) }
            }
        }
        return pairing.toBuilder().setSendCounter(counter).build() to envelope
    }

    private fun ownParty(pairing: Pairing, xk: ExchangeKeypair): Party =
        SigningKeypair(sodium, pairing.identitySeed.toByteArray()).use { Party(it.publicKey, xk.publicKey) }

    private fun controllerParty(pairing: Pairing): Party =
        Party(pairing.controllerIdentity.toByteArray(), pairing.controllerExchange.toByteArray())

    public companion object {
        public const val URI_PREFIX: String = "bastion://pair/v1#"
        public const val SKEW_MS: Long = 30_000
        private const val MAX_INVITE_CHARS = 4096
        private const val INVITE_MAX_AHEAD_MS = 330_000L
        private const val MESSAGE_ID_BYTES = 16
        private const val MAX_SEEN = 4096
        private const val MILLIS = 1000L
        private const val DEFAULT_TTL_S = 60L
        private const val SHORT_TTL_S = 15 * 60L
        private const val LONG_TTL_S = 24 * 3600L
        private const val CONFIRM_TTL_S = 24 * 3600
        private const val EVENT_TTL_S = 7 * 24 * 3600
        private const val MAX_LABEL = 64
        private const val MAX_RING_SECONDS = 600
        private const val U32_MASK = 0xffffffffL
        private const val MIN_FPS = 1
        private const val MAX_FPS = 10
        private const val MIN_EDGE_PX = 240
        private const val MAX_EDGE_PX = 1280
        private const val DEFAULT_STREAM_SECONDS = 60
        private const val MAX_STREAM_SECONDS = 300
        private const val MAX_SCREEN_FPS = 15
        private const val MAX_SCREEN_EDGE_PX = 1600
        private const val DEFAULT_SCREEN_SECONDS = 300
        private const val MAX_SCREEN_SECONDS = 900
        private const val ROTATION_INTERVAL_MS = 7L * 24 * 3600 * 1000

        /** TTL caps per payload type (PROTOCOL.md §6.8). */
        internal fun effectiveTtlSeconds(body: MessageBody): Long {
            val requested = if (body.ttlSeconds == 0) DEFAULT_TTL_S else body.ttlSeconds.toLong() and U32_MASK
            val cap = when {
                body.hasPairingConfirm() -> LONG_TTL_S

                body.hasCommand() -> when (body.command.kindCase) {
                    Command.KindCase.SET_TRACKING_MODE,
                    Command.KindCase.LOST_MODE,
                    Command.KindCase.LOCK,
                    Command.KindCase.WIPE,
                    Command.KindCase.UNPAIR,
                    -> LONG_TTL_S

                    else -> SHORT_TTL_S
                }

                else -> DEFAULT_TTL_S
            }
            return minOf(requested, cap)
        }

        internal fun isSensitive(command: AgentCommand): Boolean =
            command is AgentCommand.Lock || command is AgentCommand.Wipe || command is AgentCommand.Unpair

        private fun contact(card: org.bastion.protocol.v1.ContactCard): ContactCard? =
            ContactCard.create(card.message, card.phone, card.email).getOrNull()

        @Suppress("CyclomaticComplexMethod")
        internal fun decode(id: ByteArray, command: Command): AgentCommand? = when (command.kindCase) {
            Command.KindCase.RING -> AgentCommand.Ring(
                id,
                command.ring.durationSeconds.coerceIn(0, MAX_RING_SECONDS),
                command.ring.flashlight,
                command.ring.vibrate,
            )

            Command.KindCase.STOP_RING -> AgentCommand.StopRing(id)

            Command.KindCase.LOCATE_NOW -> AgentCommand.Locate(id, command.locateNow.highAccuracy)

            Command.KindCase.SET_TRACKING_MODE -> AgentCommand.Tracking(id, command.setTrackingMode.modeValue)

            Command.KindCase.LOST_MODE -> AgentCommand.LostMode(
                id,
                command.lostMode.enabled,
                if (command.lostMode.hasContact()) contact(command.lostMode.contact) else null,
            )

            Command.KindCase.LOCK -> AgentCommand.Lock(
                id,
                if (command.lock.hasContact()) contact(command.lock.contact) else null,
            )

            Command.KindCase.WIPE -> AgentCommand.Wipe(id, command.wipe.includeExternalStorage)

            Command.KindCase.UNPAIR -> AgentCommand.Unpair(id)

            Command.KindCase.REQUEST_STATUS -> AgentCommand.Status(id)

            Command.KindCase.CAPTURE_PHOTO -> AgentCommand.CapturePhoto(id, command.capturePhoto.cameraValue)

            Command.KindCase.STREAM_CONTROL -> AgentCommand.Stream(
                id,
                command.streamControl.enabled,
                command.streamControl.cameraValue,
                command.streamControl.maxFps.coerceIn(MIN_FPS, MAX_FPS),
                command.streamControl.maxEdgePx.coerceIn(MIN_EDGE_PX, MAX_EDGE_PX),
                clampDuration(command.streamControl.maxDurationSeconds),
            )

            Command.KindCase.AUDIO_CONTROL -> AgentCommand.Audio(
                id,
                command.audioControl.enabled,
                clampDuration(command.audioControl.maxDurationSeconds),
            )

            Command.KindCase.SET_GEOFENCES -> AgentCommand.SetGeofences(id, command.setGeofences.zonesList)

            Command.KindCase.SCREEN_CONTROL -> AgentCommand.Screen(
                id,
                command.screenControl.enabled,
                command.screenControl.maxFps.coerceIn(MIN_FPS, MAX_SCREEN_FPS),
                command.screenControl.maxEdgePx.coerceIn(MIN_EDGE_PX, MAX_SCREEN_EDGE_PX),
                clampScreenDuration(command.screenControl.maxDurationSeconds),
                command.screenControl.keepAwake,
            )

            Command.KindCase.REMOTE_INPUT -> AgentCommand.Input(id, command.remoteInput)

            else -> null
        }

        private fun clampDuration(seconds: Int): Int =
            if (seconds == 0) DEFAULT_STREAM_SECONDS else seconds.coerceIn(1, MAX_STREAM_SECONDS)

        private fun clampScreenDuration(seconds: Int): Int =
            if (seconds == 0) DEFAULT_SCREEN_SECONDS else seconds.coerceIn(1, MAX_SCREEN_SECONDS)

        public fun sanitizeLabel(label: String): String =
            label.filterNot { it.isISOControl() }.trim().take(MAX_LABEL).ifEmpty { "Bastion" }

        private fun isHttpsEndpoint(endpoint: String): Boolean = runCatching {
            val uri = URI(endpoint)
            uri.scheme == "https" && !uri.host.isNullOrEmpty() && (uri.path.isNullOrEmpty() || uri.path == "/") &&
                uri.query == null && uri.fragment == null && uri.userInfo == null
        }.getOrDefault(false)

        // Malformed input is an expected, silent rejection (null).
        @Suppress("SwallowedException")
        private fun decodeInvite(payload: String): PairingInvite? = try {
            PairingInvite.parseFrom(Base64.getUrlDecoder().decode(payload.trimEnd('=')))
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: InvalidProtocolBufferException) {
            null
        }

        @Suppress("SwallowedException")
        private fun parseBody(bytes: ByteArray): MessageBody? = try {
            MessageBody.parseFrom(bytes)
        } catch (_: InvalidProtocolBufferException) {
            null
        }

        /** Hex form of a device id, for logs-free UI display. */
        public fun hex(bytes: ByteArray): String = Bytes.toHex(bytes)
    }
}
