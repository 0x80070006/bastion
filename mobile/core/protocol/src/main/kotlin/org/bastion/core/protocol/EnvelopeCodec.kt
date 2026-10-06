package org.bastion.core.protocol

import com.google.protobuf.InvalidProtocolBufferException
import org.bastion.protocol.v1.Envelope

/** Protocol constants shared with `bastion_proto` (Rust). See docs/PROTOCOL.md §9. */
public object ProtocolLimits {
    public const val ENVELOPE_VERSION: Int = 1
    public const val PROTOCOL_VERSION: Int = 1
    public const val MAX_ENVELOPE_BYTES: Int = 512 * 1024
    public const val DEVICE_ID_BYTES: Int = 16
    public const val NONCE_BYTES: Int = 24
}

/** Structural decoding of an [Envelope]. Cryptographic checks happen in `:core:crypto`. */
public object EnvelopeCodec {
    /** Why an envelope was rejected before any cryptographic processing. */
    public enum class Rejection { TOO_LARGE, MALFORMED, UNSUPPORTED_VERSION, BAD_FIELD_LENGTH }

    public sealed interface Result {
        public data class Accepted(val envelope: Envelope) : Result

        public data class Rejected(val reason: Rejection) : Result
    }

    /** Applies PROTOCOL.md §6 steps 1–2 (size before parsing, then version and field lengths). */
    public fun decode(bytes: ByteArray): Result {
        if (bytes.size > ProtocolLimits.MAX_ENVELOPE_BYTES) return Result.Rejected(Rejection.TOO_LARGE)
        val envelope = parseOrNull(bytes) ?: return Result.Rejected(Rejection.MALFORMED)
        return when {
            envelope.version != ProtocolLimits.ENVELOPE_VERSION -> Result.Rejected(Rejection.UNSUPPORTED_VERSION)
            !envelope.hasValidFieldLengths() -> Result.Rejected(Rejection.BAD_FIELD_LENGTH)
            else -> Result.Accepted(envelope)
        }
    }

    // Malformed input is an expected, typed outcome (Rejection.MALFORMED), not an error to propagate.
    @Suppress("SwallowedException")
    private fun parseOrNull(bytes: ByteArray): Envelope? = try {
        Envelope.parseFrom(bytes)
    } catch (_: InvalidProtocolBufferException) {
        null
    }

    private fun Envelope.hasValidFieldLengths(): Boolean = senderId.size() == ProtocolLimits.DEVICE_ID_BYTES &&
        recipientId.size() == ProtocolLimits.DEVICE_ID_BYTES &&
        nonce.size() == ProtocolLimits.NONCE_BYTES
}
