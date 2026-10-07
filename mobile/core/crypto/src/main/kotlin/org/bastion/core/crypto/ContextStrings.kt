package org.bastion.core.crypto

/**
 * Domain-separation strings of protocol v1 (docs/PROTOCOL.md). ASCII, no terminator.
 * Must stay byte-identical to `bastion_crypto::context` (checked by the shared test vectors).
 */
public object ContextStrings {
    public const val X25519_KEY_SIGNATURE: String = "bastion-xk-v1"
    public const val INVITE_TOKEN_HASH: String = "bastion-invite-v1"
    public const val ENROLL_PROOF: String = "bastion-enroll-v1"
    public const val ENROLL_SIGNATURE: String = "bastion-enroll-sig-v1"
    public const val SAS: String = "bastion-sas-v1"
    public const val SESSION: String = "bastion-session-v1"
    public const val SESSION_KEY: String = "bastion-session-key-v1"
    public const val ENVELOPE_AAD: String = "bastion-env-v1"
    public const val MESSAGE_SIGNATURE: String = "bastion-msg-v1"
    public const val REQUEST_SIGNATURE: String = "bastion-req-v1"
    public const val FINGERPRINT: String = "bastion-fp-v1"

    /** All context strings, used to assert they are pairwise distinct. */
    public val all: List<String> = listOf(
        X25519_KEY_SIGNATURE, INVITE_TOKEN_HASH, ENROLL_PROOF, ENROLL_SIGNATURE, SAS, SESSION,
        SESSION_KEY, ENVELOPE_AAD, MESSAGE_SIGNATURE, REQUEST_SIGNATURE, FINGERPRINT,
    )

    /** Encodes a context string as the exact bytes fed to the primitives. */
    public fun bytes(context: String): ByteArray = context.toByteArray(Charsets.US_ASCII)
}
