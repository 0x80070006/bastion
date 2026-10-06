package org.bastion.core.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ContactCardTest {
    @Test
    fun `valid card is trimmed`() {
        val outcome = ContactCard.create("  Merci de me rappeler  ", " +33 6 00 00 00 00 ", "")
        val card = outcome.getOrNull()
        assertThat(card?.message).isEqualTo("Merci de me rappeler")
        assertThat(card?.phone).isEqualTo("+33 6 00 00 00 00")
    }

    @Test
    fun `empty card is rejected`() {
        assertThat(ContactCard.create(" ", "", "")).isEqualTo(Outcome.Failure(ContactCard.Error.EMPTY))
    }

    @Test
    fun `message limit counts code points not UTF-16 units`() {
        val emojiHeavy = "🔒".repeat(ContactCard.MAX_MESSAGE_CHARS)
        assertThat(ContactCard.create(emojiHeavy, "", "")).isInstanceOf(Outcome.Success::class.java)
        assertThat(ContactCard.create(emojiHeavy + "a", "", ""))
            .isEqualTo(Outcome.Failure(ContactCard.Error.MESSAGE_TOO_LONG))
    }

    @Test
    fun `field limits are enforced`() {
        val longPhone = "1".repeat(ContactCard.MAX_PHONE_CHARS + 1)
        val longEmail = "a".repeat(ContactCard.MAX_EMAIL_CHARS + 1)
        assertThat(ContactCard.create("", longPhone, "")).isEqualTo(Outcome.Failure(ContactCard.Error.PHONE_TOO_LONG))
        assertThat(ContactCard.create("", "", longEmail)).isEqualTo(Outcome.Failure(ContactCard.Error.EMAIL_TOO_LONG))
    }

    @Test
    fun `control characters are rejected but newlines are allowed`() {
        assertThat(
            ContactCard.create("a\u0007b", "", ""),
        ).isEqualTo(Outcome.Failure(ContactCard.Error.CONTROL_CHARACTERS))
        assertThat(ContactCard.create("line 1\nline 2", "", "")).isInstanceOf(Outcome.Success::class.java)
    }

    @Test
    fun `toString never leaks contact details`() {
        val card = ContactCard.create("secret", "0600", "a@b.c").getOrNull()
        assertThat(card.toString()).doesNotContain("secret")
        assertThat(card.toString()).doesNotContain("0600")
    }

    @Test
    fun `map transforms only successes`() {
        assertThat(Outcome.Success(2).map { it * 2 }).isEqualTo(Outcome.Success(4))
        val failure: Outcome<Int, String> = Outcome.Failure("e")
        assertThat(failure.map { it * 2 }).isEqualTo(Outcome.Failure("e"))
        assertThat(failure.getOrNull()).isNull()
    }
}
