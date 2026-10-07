package org.bastion.core.crypto

import com.google.protobuf.ByteString
import com.google.protobuf.InvalidProtocolBufferException
import org.bastion.protocol.v1.Envelope
import org.bastion.protocol.v1.SignedMessage

/** Routing header visible to the relay. */
public class EnvelopeHeader(
    public val version: Int,
    public val senderId: ByteArray,
    public val recipientId: ByteArray,
    public val keyEpoch: Int,
) {
    /** `T("bastion-env-v1", u32be(version), sender_id, recipient_id, u32be(key_epoch))`. */
    public fun aad(): ByteArray = Transcript(ContextStrings.ENVELOPE_AAD)
        .u32(version)
        .field(senderId)
        .field(recipientId)
        .u32(keyEpoch)
        .finish()

    public companion object {
        public const val CURRENT_VERSION: Int = 1

        public fun of(envelope: Envelope): EnvelopeHeader = EnvelopeHeader(
            envelope.version,
            envelope.senderId.toByteArray(),
            envelope.recipientId.toByteArray(),
            envelope.keyEpoch,
        )
    }
}

/** A decrypted envelope whose signatures verified. */
public class OpenedEnvelope(
    public val header: EnvelopeHeader,
    public val body: ByteArray,
    public val privileged: Boolean,
)

/** Sign-then-encrypt envelopes (PROTOCOL.md §5–6). Mirrors `bastion_crypto::envelope`. */
public object EnvelopeCrypto {
    public fun signatureInput(aad: ByteArray, body: ByteArray): ByteArray =
        Transcript(ContextStrings.MESSAGE_SIGNATURE).field(aad).field(body).finish()

    public fun seal(
        sodium: Sodium,
        key: SymmetricKey,
        header: EnvelopeHeader,
        sender: SigningKeypair,
        privileged: SigningKeypair?,
        body: ByteArray,
        nonce: ByteArray = sodium.randomBytes(KeySizes.NONCE),
    ): ByteArray {
        val aad = header.aad()
        val input = signatureInput(aad, body)
        val signed = SignedMessage.newBuilder()
            .setBody(ByteString.copyFrom(body))
            .setSignature(ByteString.copyFrom(sender.sign(input)))
            .apply { if (privileged != null) privilegedSignature = ByteString.copyFrom(privileged.sign(input)) }
            .build()
        return Envelope.newBuilder()
            .setVersion(header.version)
            .setSenderId(ByteString.copyFrom(header.senderId))
            .setRecipientId(ByteString.copyFrom(header.recipientId))
            .setKeyEpoch(header.keyEpoch)
            .setNonce(ByteString.copyFrom(nonce))
            .setCiphertext(ByteString.copyFrom(key.encrypt(nonce, aad, signed.toByteArray())))
            .build()
            .toByteArray()
    }

    /**
     * Decrypts with the first candidate key that authenticates and verifies the signatures.
     * Returns `null` on any failure (silent rejection, PROTOCOL.md §6 steps 4–5). A present
     * but invalid privileged signature rejects the message.
     */
    @Suppress("ReturnCount")
    public fun open(
        sodium: Sodium,
        envelope: Envelope,
        candidateKeys: List<SymmetricKey>,
        senderIdentity: ByteArray,
        privilegedKey: ByteArray?,
    ): OpenedEnvelope? {
        val header = EnvelopeHeader.of(envelope)
        val aad = header.aad()
        val nonce = envelope.nonce.toByteArray()
        val ciphertext = envelope.ciphertext.toByteArray()
        val plaintext = candidateKeys.firstNotNullOfOrNull { it.decrypt(nonce, aad, ciphertext) } ?: return null
        val signed = parseSigned(plaintext) ?: return null
        val body = signed.body.toByteArray()
        val input = signatureInput(aad, body)
        if (!sodium.verify(senderIdentity, input, signed.signature.toByteArray())) return null
        val privileged = if (signed.privilegedSignature.isEmpty) {
            false
        } else {
            if (privilegedKey == null) return null
            if (!sodium.verify(privilegedKey, input, signed.privilegedSignature.toByteArray())) return null
            true
        }
        return OpenedEnvelope(header, body, privileged)
    }

    // A malformed plaintext is an expected, silent rejection (null), not an error to propagate.
    @Suppress("SwallowedException")
    private fun parseSigned(bytes: ByteArray): SignedMessage? = try {
        SignedMessage.parseFrom(bytes)
    } catch (_: InvalidProtocolBufferException) {
        null
    }
}
