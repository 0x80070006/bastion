package org.bastion.core.crypto

/** 256-bit XChaCha20-Poly1305 key. Call [close] to wipe it. */
public class SymmetricKey(private val sodium: Sodium, bytes: ByteArray) : AutoCloseable {
    private val key: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == KeySizes.SECRET) { "key must be 32 bytes" }
    }

    public fun encrypt(nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray =
        sodium.aeadEncrypt(key, nonce, aad, plaintext)

    public fun decrypt(nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? =
        sodium.aeadDecrypt(key, nonce, aad, ciphertext)

    /** Test vectors and encrypted storage only. */
    public fun expose(): ByteArray = key.copyOf()

    override fun close() {
        key.fill(0)
    }

    override fun toString(): String = "SymmetricKey(redacted)"
}

/** Public keys of one side of a session. */
public class Party(public val identity: ByteArray, public val exchange: ByteArray)

/** Directional session keys (PROTOCOL.md §4). Mirrors `bastion_crypto::session`. */
public object Session {
    /**
     * Key for messages from [sender] to [recipient], or `null` if [local] is not a party or the
     * exchange is weak.
     */
    public fun directionalKey(sodium: Sodium, local: ExchangeKeypair, sender: Party, recipient: Party): SymmetricKey? {
        val localPublic = local.publicKey
        val peer = when {
            localPublic.contentEquals(sender.exchange) -> recipient.exchange
            localPublic.contentEquals(recipient.exchange) -> sender.exchange
            else -> null
        }
        val shared = peer?.let(local::diffieHellman) ?: return null
        try {
            val (low, high) = if (Bytes.compare(sender.identity, recipient.identity) <= 0) {
                sender.identity to recipient.identity
            } else {
                recipient.identity to sender.identity
            }
            val salt = sodium.blake2b(KeySizes.HASH, Transcript(ContextStrings.SESSION).field(low).field(high).finish())
            val info = Transcript(ContextStrings.SESSION_KEY)
                .field(salt)
                .field(sender.identity)
                .field(sender.exchange)
                .field(recipient.identity)
                .field(recipient.exchange)
                .finish()
            val raw = sodium.blake2b(KeySizes.HASH, info, shared)
            return try {
                SymmetricKey(sodium, raw)
            } finally {
                raw.fill(0)
            }
        } finally {
            shared.fill(0)
        }
    }
}
