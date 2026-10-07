package org.bastion.core.crypto

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Unambiguous encoding of signed / hashed inputs (PROTOCOL.md §1.1):
 * `u32be(len(ctx)) ‖ ctx ‖ u32be(len(f1)) ‖ f1 ‖ …`.
 */
public class Transcript(context: String) {
    private val out = ByteArrayOutputStream()

    init {
        push(ContextStrings.bytes(context))
    }

    private fun push(field: ByteArray) {
        out.write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(field.size).array())
        out.write(field)
    }

    public fun field(field: ByteArray): Transcript = apply { push(field) }

    public fun u32(value: Int): Transcript = field(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())

    public fun u64(value: Long): Transcript = field(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array())

    public fun finish(): ByteArray = out.toByteArray()
}
