package org.bastion.core.crypto

import java.nio.ByteBuffer

/** Pairing derivations (PROTOCOL.md §3). Mirrors `bastion_crypto::pairing`. */
public object Pairing {
    public const val TOKEN_BYTES: Int = 16
    private const val SAS_MODULUS = 1_000_000L
    private const val SAS_HALF = 3
    private const val U32_MASK = 0xffffffffL

    /** `BLAKE2b-256(key = "bastion-invite-v1", token)`. */
    public fun tokenHash(sodium: Sodium, token: ByteArray): ByteArray =
        sodium.blake2b(KeySizes.HASH, token, ContextStrings.bytes(ContextStrings.INVITE_TOKEN_HASH))

    public fun enrollProof(
        sodium: Sodium,
        token: ByteArray,
        phoneIdentity: ByteArray,
        phoneExchange: ByteArray,
        wireguardPublic: ByteArray,
        controllerIdentity: ByteArray,
    ): ByteArray {
        require(token.size == TOKEN_BYTES) { "token must be 16 bytes" }
        val input = Transcript(ContextStrings.ENROLL_PROOF)
            .field(phoneIdentity)
            .field(phoneExchange)
            .field(wireguardPublic)
            .field(controllerIdentity)
            .finish()
        return sodium.blake2b(KeySizes.HASH, input, token)
    }

    /** Transcript signed by the enrolling device (PROTOCOL.md §3.2). */
    public fun enrollTranscript(
        tokenHash: ByteArray,
        device: DeviceKeys,
        wireguardPublic: ByteArray,
        proof: ByteArray,
        role: Int,
    ): ByteArray = Transcript(ContextStrings.ENROLL_SIGNATURE)
        .field(tokenHash)
        .field(device.identity)
        .field(device.exchange)
        .field(device.exchangeSignature)
        .u32(device.epoch)
        .field(wireguardPublic)
        .field(proof)
        .u32(role)
        .finish()

    /** Six-digit SAS over `A = IK ‖ XK` of both devices, order-independent. */
    public fun sasCode(sodium: Sodium, token: ByteArray, a: DeviceKeys, b: DeviceKeys): Int {
        val first = a.identity + a.exchange
        val second = b.identity + b.exchange
        val (low, high) = if (Bytes.compare(first, second) <= 0) first to second else second to first
        val input = Transcript(ContextStrings.SAS).field(low).field(high).finish()
        val digest = sodium.blake2b(KeySizes.HASH, input, token)
        val prefix = ByteBuffer.wrap(digest, 0, Int.SIZE_BYTES).int.toLong() and U32_MASK
        return (prefix % SAS_MODULUS).toInt()
    }

    /** `"123 456"`. */
    public fun formatSas(code: Int): String {
        val digits = code.toString().padStart(SAS_HALF * 2, '0')
        return digits.substring(0, SAS_HALF) + " " + digits.substring(SAS_HALF)
    }
}
