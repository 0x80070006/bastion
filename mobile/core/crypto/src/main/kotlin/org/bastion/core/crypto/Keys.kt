package org.bastion.core.crypto

import com.google.protobuf.ByteString

/** Sizes of protocol v1 key material. */
public object KeySizes {
    public const val PUBLIC_KEY: Int = 32
    public const val SECRET: Int = 32
    public const val SIGNATURE: Int = 64
    public const val DEVICE_ID: Int = 16
    public const val HASH: Int = 32
    public const val NONCE: Int = 24
}

/** Ed25519 key (identity `IK` or privileged `PK`). Call [close] to wipe the seed. */
public class SigningKeypair(private val sodium: Sodium, seed: ByteArray) : AutoCloseable {
    private val seed: ByteArray = seed.copyOf()

    init {
        require(seed.size == KeySizes.SECRET) { "seed must be 32 bytes" }
    }

    public val publicKey: ByteArray = sodium.signPublicKey(this.seed)

    public val deviceId: ByteArray = Identity.deviceId(sodium, publicKey)

    public fun sign(message: ByteArray): ByteArray = sodium.sign(seed, message)

    /** For encrypted storage only. */
    public fun exportSeed(): ByteArray = seed.copyOf()

    override fun close() {
        seed.fill(0)
    }

    override fun toString(): String = "SigningKeypair(${Bytes.toHex(publicKey)})"

    public companion object {
        public fun generate(sodium: Sodium): SigningKeypair {
            val seed = sodium.randomBytes(KeySizes.SECRET)
            return try {
                SigningKeypair(sodium, seed)
            } finally {
                seed.fill(0)
            }
        }
    }
}

/** X25519 key `XK` of a given epoch. Call [close] to wipe the secret. */
public class ExchangeKeypair(private val sodium: Sodium, secret: ByteArray, public val epoch: Int) : AutoCloseable {
    private val secret: ByteArray = secret.copyOf()

    init {
        require(secret.size == KeySizes.SECRET) { "secret must be 32 bytes" }
    }

    public val publicKey: ByteArray = sodium.x25519PublicKey(this.secret)

    /** Shared secret, or `null` for a low-order peer key. */
    public fun diffieHellman(peerPublic: ByteArray): ByteArray? = sodium.x25519(secret, peerPublic)

    public fun openSealed(sealed: ByteArray): ByteArray? = sodium.sealOpen(secret, sealed)

    public fun signedBy(identity: SigningKeypair): DeviceKeys = DeviceKeys(
        identity = identity.publicKey,
        exchange = publicKey,
        exchangeSignature = identity.sign(Identity.exchangeKeyTranscript(publicKey, epoch)),
        epoch = epoch,
    )

    /** For encrypted storage only. */
    public fun exportSecret(): ByteArray = secret.copyOf()

    override fun close() {
        secret.fill(0)
    }

    override fun toString(): String = "ExchangeKeypair(${Bytes.toHex(publicKey)}, epoch=$epoch)"

    public companion object {
        public fun generate(sodium: Sodium, epoch: Int): ExchangeKeypair {
            val secret = sodium.randomBytes(KeySizes.SECRET)
            return try {
                ExchangeKeypair(sodium, secret, epoch)
            } finally {
                secret.fill(0)
            }
        }
    }
}

/** Public keys of a device (`bastion.v1.DeviceKeys`). */
public class DeviceKeys(
    public val identity: ByteArray,
    public val exchange: ByteArray,
    public val exchangeSignature: ByteArray,
    public val epoch: Int,
) {
    public fun verify(sodium: Sodium): Boolean = identity.size == KeySizes.PUBLIC_KEY &&
        exchange.size == KeySizes.PUBLIC_KEY &&
        sodium.verify(identity, Identity.exchangeKeyTranscript(exchange, epoch), exchangeSignature)

    public fun deviceId(sodium: Sodium): ByteArray = Identity.deviceId(sodium, identity)

    public fun fingerprint(sodium: Sodium): String = Identity.fingerprint(sodium, identity, exchange)

    public fun toProto(): org.bastion.protocol.v1.DeviceKeys = org.bastion.protocol.v1.DeviceKeys.newBuilder()
        .setIdentityPublicKey(ByteString.copyFrom(identity))
        .setX25519PublicKey(ByteString.copyFrom(exchange))
        .setX25519Signature(ByteString.copyFrom(exchangeSignature))
        .setKeyEpoch(epoch)
        .build()

    override fun equals(other: Any?): Boolean = other is DeviceKeys &&
        identity.contentEquals(other.identity) &&
        exchange.contentEquals(other.exchange) &&
        exchangeSignature.contentEquals(other.exchangeSignature) &&
        epoch == other.epoch

    override fun hashCode(): Int = identity.contentHashCode() * 31 + epoch

    override fun toString(): String = "DeviceKeys(${Bytes.toHex(identity)}, epoch=$epoch)"

    public companion object {
        /** Parses and verifies; `null` if lengths or the X25519 key signature are invalid. */
        public fun fromProto(sodium: Sodium, proto: org.bastion.protocol.v1.DeviceKeys): DeviceKeys? {
            if (proto.x25519Signature.size() != KeySizes.SIGNATURE) return null
            val keys = DeviceKeys(
                identity = proto.identityPublicKey.toByteArray(),
                exchange = proto.x25519PublicKey.toByteArray(),
                exchangeSignature = proto.x25519Signature.toByteArray(),
                epoch = proto.keyEpoch,
            )
            return keys.takeIf { it.verify(sodium) }
        }
    }
}

/** Identity derivations (PROTOCOL.md §2). */
public object Identity {
    private const val FINGERPRINT_BYTES = 16
    private const val FINGERPRINT_GROUP = 4

    public fun exchangeKeyTranscript(exchange: ByteArray, epoch: Int): ByteArray =
        Transcript(ContextStrings.X25519_KEY_SIGNATURE).field(exchange).u32(epoch).finish()

    /** `device_id = BLAKE2b-128(IK.pub)`. */
    public fun deviceId(sodium: Sodium, identity: ByteArray): ByteArray = sodium.blake2b(KeySizes.DEVICE_ID, identity)

    /** `BLAKE2b-256(T("bastion-fp-v1", IK.pub, XK.pub))`, 8 groups of 4 hex digits. */
    public fun fingerprint(sodium: Sodium, identity: ByteArray, exchange: ByteArray): String {
        val digest = sodium.blake2b(
            KeySizes.HASH,
            Transcript(ContextStrings.FINGERPRINT).field(identity).field(exchange).finish(),
        )
        return Bytes.toHex(digest.copyOf(FINGERPRINT_BYTES)).chunked(FINGERPRINT_GROUP).joinToString(" ")
    }
}
