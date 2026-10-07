package org.bastion.core.agent

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import java.util.Base64
import org.bastion.core.agent.state.AgentState
import org.bastion.core.crypto.DeviceKeys
import org.bastion.core.crypto.EnvelopeCrypto
import org.bastion.core.crypto.EnvelopeHeader
import org.bastion.core.crypto.ExchangeKeypair
import org.bastion.core.crypto.LazySodiumAdapter
import org.bastion.core.crypto.Pairing
import org.bastion.core.crypto.Party
import org.bastion.core.crypto.Session
import org.bastion.core.crypto.SigningKeypair
import org.bastion.core.crypto.Sodium
import org.bastion.protocol.v1.Command
import org.bastion.protocol.v1.CommandResult
import org.bastion.protocol.v1.EnrollResponse
import org.bastion.protocol.v1.Envelope
import org.bastion.protocol.v1.Lock
import org.bastion.protocol.v1.MessageBody
import org.bastion.protocol.v1.PairingConfirm
import org.bastion.protocol.v1.PairingHello
import org.bastion.protocol.v1.PairingInvite
import org.bastion.protocol.v1.Ring
import org.junit.jupiter.api.Test

internal class PhoneAgentTest {
    private val sodium: Sodium = LazySodiumAdapter(LazySodiumJava(SodiumJava()))
    private val agent = PhoneAgent(sodium)
    private val now = 1_760_000_000_000L

    /** Minimal controller built from the same primitives as the Rust implementation. */
    private inner class Controller {
        val ik = SigningKeypair.generate(sodium)
        val xk = ExchangeKeypair.generate(sodium, 0)
        val pk = SigningKeypair.generate(sodium)
        val keys: DeviceKeys = xk.signedBy(ik)
        val token: ByteArray = sodium.randomBytes(Pairing.TOKEN_BYTES)
        var counter = 0L

        fun invite(endpoint: String = "https://192.168.1.10:8443", expires: Long = now + 120_000): PairingInvite =
            PairingInvite.newBuilder()
                .setVersion(1)
                .setRelayHttpsEndpoint(endpoint)
                .setRelayTlsSpkiSha256(ByteString.copyFrom(ByteArray(32) { 1 }))
                .setController(keys.toProto())
                .setControllerPrivilegedPublicKey(ByteString.copyFrom(pk.publicKey))
                .setToken(ByteString.copyFrom(token))
                .setExpiresAtMs(expires)
                .setControllerLabel("PC")
                .build()

        fun uri(invite: PairingInvite = invite()): String =
            PhoneAgent.URI_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(invite.toByteArray())

        fun send(
            phone: DeviceKeys,
            body: MessageBody.Builder,
            privileged: Boolean = false,
            timestamp: Long = now,
        ): ByteArray {
            counter += 1
            val message = body
                .setProtocolVersion(1)
                .setMessageId(ByteString.copyFrom(sodium.randomBytes(16)))
                .setCounter(counter)
                .setTimestampMs(timestamp)
                .setTtlSeconds(900)
                .build()
            val key = requireNotNull(
                Session.directionalKey(
                    sodium,
                    xk,
                    Party(keys.identity, keys.exchange),
                    Party(phone.identity, phone.exchange),
                ),
            )
            val header = EnvelopeHeader(1, ik.deviceId, phone.deviceId(sodium), 0)
            return EnvelopeCrypto.seal(sodium, key, header, ik, if (privileged) pk else null, message.toByteArray())
        }

        fun open(phone: DeviceKeys, envelope: ByteArray): MessageBody {
            val key = requireNotNull(
                Session.directionalKey(
                    sodium,
                    xk,
                    Party(phone.identity, phone.exchange),
                    Party(keys.identity, keys.exchange),
                ),
            )
            val opened =
                requireNotNull(
                    EnvelopeCrypto.open(sodium, Envelope.parseFrom(envelope), listOf(key), phone.identity, null),
                )
            return MessageBody.parseFrom(opened.body)
        }
    }

    private fun phoneKeys(state: AgentState): DeviceKeys = DeviceKeys(
        agent.identity(state).use { it.publicKey },
        ExchangeKeypair(sodium, state.pairing.exchangeSecret.toByteArray(), 0).use { it.publicKey },
        ByteArray(64),
        0,
    )

    private fun pairedState(controller: Controller): AgentState {
        val invite = (agent.parseInvite(controller.uri(), now) as InviteParse.Valid).invite
        val enrollment = agent.enroll(invite, "Pixel", now, AgentState.getDefaultInstance())
        val response = EnrollResponse.newBuilder()
            .setDeviceId(ByteString.copyFrom(agent.deviceId(enrollment.state)))
            .setControllerId(ByteString.copyFrom(controller.ik.deviceId))
            .build()
        val enrolled = requireNotNull(agent.markEnrolled(enrollment.state, response))
        val (confirmed, _) = agent.confirm(enrolled, true, now)
        val confirm = MessageBody.newBuilder().setPairingConfirm(PairingConfirm.newBuilder().setConfirmed(true))
        val received = requireNotNull(agent.receive(confirmed, controller.send(phoneKeys(confirmed), confirm), now))
        assertThat(agent.isActive(received.state)).isTrue()
        return received.state
    }

    @Test
    fun `invitations are validated`() {
        val c = Controller()
        assertThat(agent.parseInvite(c.uri(), now)).isInstanceOf(InviteParse.Valid::class.java)
        assertThat(agent.parseInvite(c.uri(c.invite(expires = now - 1)), now))
            .isEqualTo(InviteParse.Invalid(InviteParse.Reason.EXPIRED))
        assertThat(agent.parseInvite(c.uri(c.invite(endpoint = "http://evil:80")), now))
            .isEqualTo(InviteParse.Invalid(InviteParse.Reason.INSECURE_ENDPOINT))
        assertThat(agent.parseInvite(c.uri(c.invite(endpoint = "https://x:1/path")), now))
            .isEqualTo(InviteParse.Invalid(InviteParse.Reason.INSECURE_ENDPOINT))
        assertThat(agent.parseInvite("bastion://pair/v1#!!!", now))
            .isEqualTo(InviteParse.Invalid(InviteParse.Reason.MALFORMED))
        assertThat(agent.parseInvite("https://example.org", now))
            .isEqualTo(InviteParse.Invalid(InviteParse.Reason.MALFORMED))
        val forged = c.invite().toBuilder()
            .setController(c.keys.toProto().toBuilder().setKeyEpoch(9))
            .build()
        assertThat(agent.parseInvite(c.uri(forged), now))
            .isEqualTo(InviteParse.Invalid(InviteParse.Reason.BAD_SIGNATURE))
    }

    @Test
    fun `enrollment request is verifiable by relay and controller`() {
        val c = Controller()
        val invite = (agent.parseInvite(c.uri(), now) as InviteParse.Valid).invite
        val enrollment = agent.enroll(invite, "Pixel\u0007 9", now, AgentState.getDefaultInstance())
        val request = enrollment.request
        val phone = requireNotNull(DeviceKeys.fromProto(sodium, request.device))
        val transcript = Pairing.enrollTranscript(
            request.tokenHash.toByteArray(),
            phone,
            ByteArray(0),
            request.proof.toByteArray(),
            request.roleValue,
        )
        assertThat(sodium.verify(phone.identity, transcript, request.signature.toByteArray())).isTrue()
        assertThat(request.tokenHash.toByteArray()).isEqualTo(Pairing.tokenHash(sodium, c.token))

        val hello = PairingHello.parseFrom(c.xk.openSealed(request.sealedHello.toByteArray()))
        assertThat(hello.deviceLabel).isEqualTo("Pixel 9")
        val expectedProof = Pairing.enrollProof(
            sodium,
            c.token,
            phone.identity,
            phone.exchange,
            ByteArray(0),
            c.keys.identity,
        )
        assertThat(hello.proof.toByteArray()).isEqualTo(expectedProof)
        assertThat(enrollment.sas).isEqualTo(Pairing.sasCode(sodium, c.token, c.keys, phone))
        assertThat(agent.isPaired(enrollment.state)).isFalse()
    }

    @Test
    fun `relay response for another device is refused`() {
        val c = Controller()
        val invite = (agent.parseInvite(c.uri(), now) as InviteParse.Valid).invite
        val enrollment = agent.enroll(invite, "Pixel", now, AgentState.getDefaultInstance())
        val wrong = EnrollResponse.newBuilder()
            .setDeviceId(ByteString.copyFrom(ByteArray(16)))
            .setControllerId(ByteString.copyFrom(c.ik.deviceId))
            .build()
        assertThat(agent.markEnrolled(enrollment.state, wrong)).isNull()
    }

    @Test
    fun `commands are accepted once and replays dropped`() {
        val c = Controller()
        val state = pairedState(c)
        val ring = MessageBody.newBuilder().setCommand(
            Command.newBuilder().setRing(Ring.newBuilder().setDurationSeconds(9999)),
        )
        val envelope = c.send(phoneKeys(state), ring)
        val first = requireNotNull(agent.receive(state, envelope, now))
        val command = first.commands.single() as AgentCommand.Ring
        assertThat(command.durationSeconds).isEqualTo(600)
        assertThat(agent.receive(first.state, envelope, now)).isNull()
    }

    @Test
    fun `sensitive commands need the privileged signature`() {
        val c = Controller()
        val state = pairedState(c)
        val lock = MessageBody.newBuilder().setCommand(Command.newBuilder().setLock(Lock.getDefaultInstance()))
        val unsigned = requireNotNull(agent.receive(state, c.send(phoneKeys(state), lock), now))
        assertThat(unsigned.commands).isEmpty()
        val reply = c.open(phoneKeys(state), unsigned.replies.single())
        assertThat(reply.commandResult.status).isEqualTo(CommandResult.Status.STATUS_REJECTED)
        assertThat(reply.commandResult.reasonCode).isEqualTo("privileged_signature_required")

        val signed =
            requireNotNull(agent.receive(unsigned.state, c.send(phoneKeys(state), lock, privileged = true), now))
        assertThat(signed.commands.single()).isInstanceOf(AgentCommand.Lock::class.java)
    }

    @Test
    fun `stale commands are rejected and future ones dropped`() {
        val c = Controller()
        val state = pairedState(c)
        val ring = MessageBody.newBuilder().setCommand(Command.newBuilder().setRing(Ring.getDefaultInstance()))
        val stale =
            requireNotNull(agent.receive(state, c.send(phoneKeys(state), ring, timestamp = now - 3_600_000), now))
        assertThat(stale.commands).isEmpty()
        assertThat(c.open(phoneKeys(state), stale.replies.single()).commandResult.reasonCode).isEqualTo("expired")
        val future =
            requireNotNull(agent.receive(stale.state, c.send(phoneKeys(state), ring, timestamp = now + 60_000), now))
        assertThat(c.open(phoneKeys(state), future.replies.single()).commandResult.reasonCode).isEqualTo("clock_skew")
    }

    @Test
    fun `nothing but the confirmation is accepted before activation`() {
        val c = Controller()
        val invite = (agent.parseInvite(c.uri(), now) as InviteParse.Valid).invite
        val enrollment = agent.enroll(invite, "Pixel", now, AgentState.getDefaultInstance())
        val response = EnrollResponse.newBuilder()
            .setDeviceId(ByteString.copyFrom(agent.deviceId(enrollment.state)))
            .setControllerId(ByteString.copyFrom(c.ik.deviceId))
            .build()
        val state = requireNotNull(agent.markEnrolled(enrollment.state, response))
        val ring = MessageBody.newBuilder().setCommand(Command.newBuilder().setRing(Ring.getDefaultInstance()))
        assertThat(agent.receive(state, c.send(phoneKeys(state), ring), now)).isNull()
        val refuse = MessageBody.newBuilder().setPairingConfirm(PairingConfirm.newBuilder().setConfirmed(false))
        assertThat(agent.receive(state, c.send(phoneKeys(state), refuse), now)?.pairingRejected).isTrue()
    }

    @Test
    fun `events reach the controller`() {
        val c = Controller()
        val state = pairedState(c)
        val event = org.bastion.protocol.v1.Event.newBuilder()
            .setStatus(org.bastion.protocol.v1.StatusReport.newBuilder().setLostMode(true))
            .build()
        val (next, envelope) = requireNotNull(agent.event(state, event, now))
        assertThat(c.open(phoneKeys(state), envelope).event.status.lostMode).isTrue()
        assertThat(next.pairing.sendCounter).isGreaterThan(state.pairing.sendCounter)
    }
}
