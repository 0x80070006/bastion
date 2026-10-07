package org.bastion.core.crypto

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import org.bastion.protocol.v1.Envelope
import org.junit.jupiter.api.Test

internal class CryptoBehaviourTest {
    private val sodium = TestSodium.instance

    private class Device(sodium: Sodium) {
        val ik = SigningKeypair.generate(sodium)
        val xk = ExchangeKeypair.generate(sodium, 0)
        val party = Party(ik.publicKey, xk.publicKey)
    }

    @Test
    fun `replay window rejects exact and old replays`() {
        val window = ReplayWindow()
        assertThat(window.accept(0)).isFalse()
        assertThat(window.accept(1)).isTrue()
        assertThat(window.accept(1)).isFalse()
        assertThat(window.accept(5)).isTrue()
        assertThat(window.accept(3)).isTrue()
        assertThat(window.accept(3)).isFalse()
        assertThat(window.accept(5 + ReplayWindow.WINDOW + 10)).isTrue()
        assertThat(window.accept(5)).isFalse()
        val restored = requireNotNull(ReplayWindow.fromBytes(window.toBytes()))
        assertThat(restored.highest).isEqualTo(window.highest)
        assertThat(restored.check(5 + ReplayWindow.WINDOW + 10)).isFalse()
        assertThat(restored.check(5 + ReplayWindow.WINDOW + 9)).isTrue()
    }

    @Test
    fun `tampered envelopes are rejected silently`() {
        val (a, b) = Device(sodium) to Device(sodium)
        val key = requireNotNull(Session.directionalKey(sodium, a.xk, a.party, b.party))
        val header = EnvelopeHeader(1, a.ik.deviceId, b.ik.deviceId, 0)
        val bytes = EnvelopeCrypto.seal(sodium, key, header, a.ik, null, "body".toByteArray())
        val envelope = Envelope.parseFrom(bytes)
        val receiverKey = requireNotNull(Session.directionalKey(sodium, b.xk, a.party, b.party))

        assertThat(EnvelopeCrypto.open(sodium, envelope, listOf(receiverKey), a.ik.publicKey, null)?.body)
            .isEqualTo("body".toByteArray())
        val redirected = envelope.toBuilder().setRecipientId(ByteString.copyFrom(ByteArray(16))).build()
        assertThat(EnvelopeCrypto.open(sodium, redirected, listOf(receiverKey), a.ik.publicKey, null)).isNull()
        val wrongSigner = SigningKeypair.generate(sodium).publicKey
        assertThat(EnvelopeCrypto.open(sodium, envelope, listOf(receiverKey), wrongSigner, null)).isNull()
    }

    @Test
    fun `privileged signature is mandatory to verify when present`() {
        val (a, b) = Device(sodium) to Device(sodium)
        val pk = SigningKeypair.generate(sodium)
        val key = requireNotNull(Session.directionalKey(sodium, a.xk, a.party, b.party))
        val header = EnvelopeHeader(1, a.ik.deviceId, b.ik.deviceId, 0)
        val envelope = Envelope.parseFrom(EnvelopeCrypto.seal(sodium, key, header, a.ik, pk, "wipe".toByteArray()))
        assertThat(EnvelopeCrypto.open(sodium, envelope, listOf(key), a.ik.publicKey, pk.publicKey)?.privileged)
            .isTrue()
        assertThat(EnvelopeCrypto.open(sodium, envelope, listOf(key), a.ik.publicKey, null)).isNull()
        val other = SigningKeypair.generate(sodium).publicKey
        assertThat(EnvelopeCrypto.open(sodium, envelope, listOf(key), a.ik.publicKey, other)).isNull()
    }

    @Test
    fun `weak exchange keys yield no session`() {
        val (a, b) = Device(sodium) to Device(sodium)
        val weak = Party(b.ik.publicKey, ByteArray(32))
        assertThat(Session.directionalKey(sodium, a.xk, a.party, weak)).isNull()
        assertThat(Session.directionalKey(sodium, Device(sodium).xk, a.party, b.party)).isNull()
    }
}
