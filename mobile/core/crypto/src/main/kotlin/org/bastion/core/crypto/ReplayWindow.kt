package org.bastion.core.crypto

import java.nio.ByteBuffer

/**
 * Sliding anti-replay window over message counters (PROTOCOL.md §6.9). Bit `i` records whether
 * `highest - i` was accepted. Same algorithm and serialization as `bastion_crypto::replay`.
 */
public class ReplayWindow private constructor(highest: Long, private val bitmap: LongArray) {
    public var highest: Long = highest
        private set

    public constructor() : this(0L, LongArray(WORDS))

    private fun bit(offset: Long): Boolean {
        val word = (offset / Long.SIZE_BITS).toInt()
        val bit = (offset % Long.SIZE_BITS).toInt()
        return bitmap[word] and (1L shl bit) != 0L
    }

    private fun set(offset: Long) {
        val word = (offset / Long.SIZE_BITS).toInt()
        val bit = (offset % Long.SIZE_BITS).toInt()
        bitmap[word] = bitmap[word] or (1L shl bit)
    }

    /** Whether [counter] would be accepted. Counters ≤ 0 are never valid. */
    public fun check(counter: Long): Boolean = when {
        counter <= 0L -> false

        counter > highest -> true

        else -> {
            val offset = highest - counter
            offset < WINDOW && !bit(offset)
        }
    }

    /** Records [counter]; returns `false` (and changes nothing) for a replay or an old counter. */
    public fun accept(counter: Long): Boolean {
        if (!check(counter)) return false
        if (counter > highest) {
            advanceTo(counter)
        } else {
            set(highest - counter)
        }
        return true
    }

    private fun advanceTo(counter: Long) {
        val shift = counter - highest
        val previous = ReplayWindow(highest, bitmap.copyOf())
        bitmap.fill(0L)
        val kept = if (shift < WINDOW) WINDOW - shift else 0L
        for (offset in 0 until kept) {
            if (previous.bit(offset)) set(offset + shift)
        }
        highest = counter
        set(0)
    }

    public fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(SERIALIZED_BYTES).putLong(highest)
        bitmap.forEach { buffer.putLong(it) }
        return buffer.array()
    }

    public fun copy(): ReplayWindow = ReplayWindow(highest, bitmap.copyOf())

    public companion object {
        public const val WINDOW: Long = 1024
        private const val WORDS = 16
        public const val SERIALIZED_BYTES: Int = Long.SIZE_BYTES * (WORDS + 1)

        public fun fromBytes(bytes: ByteArray): ReplayWindow? {
            if (bytes.size != SERIALIZED_BYTES) return null
            val buffer = ByteBuffer.wrap(bytes)
            val highest = buffer.long
            return ReplayWindow(highest, LongArray(WORDS) { buffer.long })
        }
    }
}
