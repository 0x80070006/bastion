package org.bastion.core.crypto

import java.security.MessageDigest

/** Byte helpers shared by the protocol code. */
public object Bytes {
    private const val HEX = "0123456789abcdef"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0f
    private const val BYTE_MASK = 0xff
    private const val RADIX = 16

    public fun toHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val v = byte.toInt() and BYTE_MASK
            append(HEX[v ushr NIBBLE_BITS])
            append(HEX[v and NIBBLE_MASK])
        }
    }

    /** Parses lowercase or uppercase hex; returns `null` for malformed input. */
    public fun fromHex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val out = ByteArray(text.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(text[2 * i], RADIX)
            val lo = Character.digit(text[2 * i + 1], RADIX)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl NIBBLE_BITS) or lo).toByte()
        }
        return out
    }

    /** Constant-time equality (length is not secret). */
    public fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    /** Unsigned lexicographic comparison, as Rust `[u8]` ordering. */
    public fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val diff = (a[i].toInt() and BYTE_MASK) - (b[i].toInt() and BYTE_MASK)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }
}
