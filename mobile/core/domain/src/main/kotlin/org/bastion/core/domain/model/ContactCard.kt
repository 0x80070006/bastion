package org.bastion.core.domain.model

/**
 * Owner contact information displayed on the lock screen in Lost mode.
 *
 * Instances can only be obtained through [ContactCard.create], which enforces the limits of
 * `bastion.v1.ContactCard` (docs/PROTOCOL.md).
 */
public class ContactCard private constructor(
    public val message: String,
    public val phone: String,
    public val email: String,
) {
    /** Reasons a contact card is rejected. */
    public enum class Error { MESSAGE_TOO_LONG, PHONE_TOO_LONG, EMAIL_TOO_LONG, EMPTY, CONTROL_CHARACTERS }

    override fun equals(other: Any?): Boolean =
        other is ContactCard && message == other.message && phone == other.phone && email == other.email

    override fun hashCode(): Int = listOf(message, phone, email).hashCode()

    // Deliberately omits field values: contact details must never reach logs.
    override fun toString(): String = "ContactCard(redacted)"

    public companion object {
        public const val MAX_MESSAGE_CHARS: Int = 280
        public const val MAX_PHONE_CHARS: Int = 64
        public const val MAX_EMAIL_CHARS: Int = 128

        /** Validates and normalizes (trims) the fields. */
        public fun create(message: String, phone: String, email: String): Outcome<ContactCard, Error> {
            val m = message.trim()
            val p = phone.trim()
            val e = email.trim()
            val error = when {
                m.isEmpty() && p.isEmpty() && e.isEmpty() -> Error.EMPTY
                m.codePointCount(0, m.length) > MAX_MESSAGE_CHARS -> Error.MESSAGE_TOO_LONG
                p.length > MAX_PHONE_CHARS -> Error.PHONE_TOO_LONG
                e.length > MAX_EMAIL_CHARS -> Error.EMAIL_TOO_LONG
                listOf(m, p, e).any { it.hasForbiddenControlChars() } -> Error.CONTROL_CHARACTERS
                else -> null
            }
            return if (error == null) Outcome.Success(ContactCard(m, p, e)) else Outcome.Failure(error)
        }

        private fun String.hasForbiddenControlChars(): Boolean = any { it.isISOControl() && it != '\n' }
    }
}
