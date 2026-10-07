package org.bastion.core.crypto

import com.goterl.lazysodium.LazySodium

/**
 * The libsodium primitives used by protocol v1 (ADR-0005). Failures are reported as `null` /
 * `false`, never as exceptions.
 */
public interface Sodium {
    public fun randomBytes(size: Int): ByteArray

    /** Ed25519 public key for a 32-byte seed (`crypto_sign_seed_keypair`). */
    public fun signPublicKey(seed: ByteArray): ByteArray

    /** Detached Ed25519 signature with the 32-byte seed. */
    public fun sign(seed: ByteArray, message: ByteArray): ByteArray

    public fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean

    /** X25519 public key (`crypto_scalarmult_base`). */
    public fun x25519PublicKey(secret: ByteArray): ByteArray

    /** X25519 shared secret, or `null` for an all-zero (low-order) result. */
    public fun x25519(secret: ByteArray, peerPublic: ByteArray): ByteArray?

    /** BLAKE2b (`crypto_generichash`), keyed when [key] is not null. */
    public fun blake2b(outputSize: Int, input: ByteArray, key: ByteArray? = null): ByteArray

    public fun aeadEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray

    public fun aeadDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray?

    /** `crypto_box_seal`. */
    public fun seal(recipientPublic: ByteArray, message: ByteArray): ByteArray

    /** `crypto_box_seal_open`, or `null` on failure. */
    public fun sealOpen(recipientSecret: ByteArray, sealed: ByteArray): ByteArray?
}

/**
 * [Sodium] over lazysodium. `com.goterl.lazysodium.LazySodium` has identical signatures in
 * `lazysodium-java` (JVM tests) and `lazysodium-android` (app), so one adapter serves both.
 */
public class LazySodiumAdapter(private val ls: LazySodium) : Sodium {
    override fun randomBytes(size: Int): ByteArray = ls.randomBytesBuf(size)

    override fun signPublicKey(seed: ByteArray): ByteArray {
        require(seed.size == KEY_BYTES) { "seed must be 32 bytes" }
        val publicKey = ByteArray(KEY_BYTES)
        val secretKey = ByteArray(SIGN_SECRET_BYTES)
        check(ls.cryptoSignSeedKeypair(publicKey, secretKey, seed)) { "crypto_sign_seed_keypair failed" }
        secretKey.fill(0)
        return publicKey
    }

    override fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == KEY_BYTES) { "seed must be 32 bytes" }
        val publicKey = ByteArray(KEY_BYTES)
        val secretKey = ByteArray(SIGN_SECRET_BYTES)
        val signature = ByteArray(SIGNATURE_BYTES)
        try {
            check(ls.cryptoSignSeedKeypair(publicKey, secretKey, seed)) { "crypto_sign_seed_keypair failed" }
            check(ls.cryptoSignDetached(signature, message, message.size.toLong(), secretKey)) {
                "crypto_sign_detached failed"
            }
        } finally {
            secretKey.fill(0)
        }
        return signature
    }

    override fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        publicKey.size == KEY_BYTES &&
            signature.size == SIGNATURE_BYTES &&
            ls.cryptoSignVerifyDetached(signature, message, message.size, publicKey)

    override fun x25519PublicKey(secret: ByteArray): ByteArray {
        require(secret.size == KEY_BYTES) { "secret must be 32 bytes" }
        val out = ByteArray(KEY_BYTES)
        check(ls.cryptoScalarMultBase(out, secret)) { "crypto_scalarmult_base failed" }
        return out
    }

    override fun x25519(secret: ByteArray, peerPublic: ByteArray): ByteArray? {
        if (secret.size != KEY_BYTES || peerPublic.size != KEY_BYTES) return null
        val out = ByteArray(KEY_BYTES)
        return if (ls.cryptoScalarMult(out, secret, peerPublic)) out else null
    }

    override fun blake2b(outputSize: Int, input: ByteArray, key: ByteArray?): ByteArray {
        val out = ByteArray(outputSize)
        val ok = if (key == null) {
            ls.cryptoGenericHash(out, outputSize, input, input.size.toLong())
        } else {
            ls.cryptoGenericHash(out, outputSize, input, input.size.toLong(), key, key.size)
        }
        check(ok) { "crypto_generichash failed" }
        return out
    }

    override fun aeadEncrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val out = ByteArray(plaintext.size + TAG_BYTES)
        val outLen = LongArray(1)
        check(
            ls.cryptoAeadXChaCha20Poly1305IetfEncrypt(
                out, outLen, plaintext, plaintext.size.toLong(), aad, aad.size.toLong(), null, nonce, key,
            ),
        ) { "aead encrypt failed" }
        return out
    }

    override fun aeadDecrypt(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? {
        if (ciphertext.size < TAG_BYTES || nonce.size != NONCE_BYTES || key.size != KEY_BYTES) return null
        val out = ByteArray(ciphertext.size - TAG_BYTES)
        val outLen = LongArray(1)
        val ok = ls.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            out, outLen, null, ciphertext, ciphertext.size.toLong(), aad, aad.size.toLong(), nonce, key,
        )
        return if (ok) out else null
    }

    override fun seal(recipientPublic: ByteArray, message: ByteArray): ByteArray {
        val out = ByteArray(message.size + SEAL_OVERHEAD)
        check(ls.cryptoBoxSeal(out, message, message.size.toLong(), recipientPublic)) { "crypto_box_seal failed" }
        return out
    }

    override fun sealOpen(recipientSecret: ByteArray, sealed: ByteArray): ByteArray? {
        if (sealed.size < SEAL_OVERHEAD || recipientSecret.size != KEY_BYTES) return null
        val out = ByteArray(sealed.size - SEAL_OVERHEAD)
        val recipientPublic = x25519PublicKey(recipientSecret)
        val ok = ls.cryptoBoxSealOpen(out, sealed, sealed.size.toLong(), recipientPublic, recipientSecret)
        return if (ok) out else null
    }

    private companion object {
        const val KEY_BYTES = 32
        const val SIGN_SECRET_BYTES = 64
        const val SIGNATURE_BYTES = 64
        const val TAG_BYTES = 16
        const val NONCE_BYTES = 24
        const val SEAL_OVERHEAD = 48
    }
}
