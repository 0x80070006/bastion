package org.bastion.core.crypto

import java.util.Base64

/**
 * Relay request authentication (PROTOCOL.md §8). Header value:
 * `hex(device_id).timestamp_ms.hex(nonce).base64url(signature)`.
 */
public object RequestAuth {
    public const val HEADER: String = "Bastion-Auth"
    public const val NONCE_BYTES: Int = 16

    public fun signatureInput(
        sodium: Sodium,
        method: String,
        pathAndQuery: String,
        timestampMs: Long,
        nonce: ByteArray,
        body: ByteArray,
    ): ByteArray = Transcript(ContextStrings.REQUEST_SIGNATURE)
        .field(method.toByteArray(Charsets.UTF_8))
        .field(pathAndQuery.toByteArray(Charsets.UTF_8))
        .u64(timestampMs)
        .field(nonce)
        .field(sodium.blake2b(KeySizes.HASH, body))
        .finish()

    @Suppress("LongParameterList")
    public fun sign(
        sodium: Sodium,
        identity: SigningKeypair,
        method: String,
        pathAndQuery: String,
        timestampMs: Long,
        body: ByteArray,
        nonce: ByteArray = sodium.randomBytes(NONCE_BYTES),
    ): String {
        val signature = identity.sign(signatureInput(sodium, method, pathAndQuery, timestampMs, nonce, body))
        return listOf(
            Bytes.toHex(identity.deviceId),
            timestampMs.toString(),
            Bytes.toHex(nonce),
            Base64.getUrlEncoder().withoutPadding().encodeToString(signature),
        ).joinToString(".")
    }
}
