package org.bastion.core.protocol

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import org.bastion.core.protocol.EnvelopeCodec.Rejection
import org.bastion.core.protocol.EnvelopeCodec.Result
import org.bastion.protocol.v1.envelope
import org.junit.jupiter.api.Test

internal class EnvelopeCodecTest {
    private fun validEnvelope(version: Int = 1, nonceSize: Int = 24) = envelope {
        this.version = version
        senderId = ByteString.copyFrom(ByteArray(16) { 1 })
        recipientId = ByteString.copyFrom(ByteArray(16) { 2 })
        keyEpoch = 0
        nonce = ByteString.copyFrom(ByteArray(nonceSize))
        ciphertext = ByteString.copyFrom(ByteArray(48))
    }

    @Test
    fun `accepts a well-formed envelope`() {
        val result = EnvelopeCodec.decode(validEnvelope().toByteArray())
        assertThat(result).isInstanceOf(Result.Accepted::class.java)
    }

    @Test
    fun `rejects oversized input before parsing`() {
        val result = EnvelopeCodec.decode(ByteArray(ProtocolLimits.MAX_ENVELOPE_BYTES + 1))
        assertThat(result).isEqualTo(Result.Rejected(Rejection.TOO_LARGE))
    }

    @Test
    fun `rejects truncated input`() {
        val bytes = validEnvelope().toByteArray()
        assertThat(EnvelopeCodec.decode(bytes.copyOf(bytes.size - 5))).isEqualTo(Result.Rejected(Rejection.MALFORMED))
    }

    @Test
    fun `rejects unknown envelope versions (anti-downgrade and forward)`() {
        assertThat(EnvelopeCodec.decode(validEnvelope(version = 0).toByteArray()))
            .isEqualTo(Result.Rejected(Rejection.UNSUPPORTED_VERSION))
        assertThat(EnvelopeCodec.decode(validEnvelope(version = 2).toByteArray()))
            .isEqualTo(Result.Rejected(Rejection.UNSUPPORTED_VERSION))
    }

    @Test
    fun `rejects wrong field lengths`() {
        assertThat(EnvelopeCodec.decode(validEnvelope(nonceSize = 12).toByteArray()))
            .isEqualTo(Result.Rejected(Rejection.BAD_FIELD_LENGTH))
    }
}
