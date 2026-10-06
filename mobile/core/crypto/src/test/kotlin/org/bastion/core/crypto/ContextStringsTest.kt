package org.bastion.core.crypto

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ContextStringsTest {
    @Test
    fun `context strings are distinct, versioned and ASCII`() {
        assertThat(ContextStrings.all).containsNoDuplicates()
        ContextStrings.all.forEach { context ->
            assertThat(context).matches("bastion-[a-z-]+-v1")
            assertThat(ContextStrings.bytes(context)).hasLength(context.length)
        }
    }
}
