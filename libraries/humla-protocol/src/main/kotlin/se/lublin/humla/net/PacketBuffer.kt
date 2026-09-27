/*
 * Copyright (C) 2014 Andrew Comminos
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
@file:Suppress("MagicNumber") // The varint prefixes and ranges are the wire format.

package se.lublin.humla.net

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/** Reads and writes Mumble's packet data stream (its varint encoding) over a [ByteBuffer]. */
@Suppress("TooManyFunctions")
internal class PacketBuffer(private val buffer: ByteBuffer) {

    /** Wraps the first [len] bytes of [data]. */
    constructor(data: ByteArray, len: Int) : this(ByteBuffer.wrap(data).apply { limit(len) })

    /** The number of bytes written or read so far. */
    fun size(): Int = buffer.position()

    fun capacity(): Int = buffer.limit()

    /** The number of bytes left to read or write. */
    fun left(): Int = buffer.limit() - buffer.position()

    /** Writes the low byte of [v]. */
    fun append(v: Long) {
        buffer.put(v.toByte())
    }

    fun append(d: ByteArray, len: Int) {
        buffer.put(d, 0, len)
    }

    fun skip(len: Int) {
        buffer.position(buffer.position() + len)
    }

    /** Reads one byte, unsigned. */
    fun next(): Int = buffer.get().toInt() and BYTE_MASK

    /** A copy of the next [size] bytes. */
    fun dataBlock(size: Int): ByteArray {
        val block = ByteArray(size)
        buffer.get(block, 0, size)
        return block
    }

    /** Reads a varint. */
    fun readLong(): Long {
        val v = next().toLong()
        return when {
            v and 0x80L == 0L -> v and 0x7F
            v and 0xC0L == 0x80L -> (v and 0x3F) shl 8 or next().toLong()
            v and 0xF0L == 0xF0L -> when ((v and 0xFC).toInt()) {
                0xF0 -> readBigEndian(4)
                0xF4 -> readBigEndian(8)
                0xF8 -> readLong().inv()
                0xFC -> (v and 0x03).inv()
                else -> throw BufferUnderflowException()
            }
            v and 0xF0L == 0xE0L -> readBigEndian(3, (v and 0x0F))
            v and 0xE0L == 0xC0L -> readBigEndian(2, (v and 0x1F))
            else -> 0
        }
    }

    /** Reads [bytes] bytes big-endian after the high bits already in [initial]. */
    private fun readBigEndian(bytes: Int, initial: Long = 0): Long {
        var i = initial
        repeat(bytes) { i = (i shl Byte.SIZE_BITS) or next().toLong() }
        return i
    }

    fun rewind() {
        buffer.rewind()
    }

    /** Rewinds and makes the first [limit] bytes of the underlying storage readable. */
    fun reset(limit: Int) {
        buffer.clear()
        buffer.limit(limit)
    }

    /** Writes [value] as a varint. */
    fun writeLong(value: Long) {
        var i = value
        // The sign bit makes the masked value negative, so the test is != 0, not > 0.
        if (i and Long.MIN_VALUE != 0L && i.inv() < 0x100000000L) {
            // Signed number.
            i = i.inv()
            if (i <= 0x3) {
                // Shortcase for -1 to -4
                append(0xFCL or i)
                return
            }
            append(0xF8)
        }

        // PacketDataStream compares as quint64: a negative value that took no branch above is a
        // full 64-bit pattern, not a one-byte one.
        when {
            i < 0 -> appendWithPrefix(0xF4, i, 8)
            // Need top bit clear
            i < 0x80 -> append(i)
            // Need top two bits clear
            i < 0x4000 -> {
                append((i shr 8) or 0x80L)
                appendBigEndian(i, 1)
            }
            // Need top three bits clear
            i < 0x200000 -> {
                append((i shr 16) or 0xC0L)
                appendBigEndian(i, 2)
            }
            // Need top four bits clear
            i < 0x10000000 -> {
                append((i shr 24) or 0xE0L)
                appendBigEndian(i, 3)
            }
            // A full 32-bit integer.
            i < 0x100000000L -> appendWithPrefix(0xF0, i, 4)
            // A 64-bit value.
            else -> appendWithPrefix(0xF4, i, 8)
        }
    }

    private fun appendWithPrefix(prefix: Long, v: Long, bytes: Int) {
        append(prefix)
        appendBigEndian(v, bytes)
    }

    private fun appendBigEndian(v: Long, bytes: Int) {
        for (n in bytes - 1 downTo 0) append(v shr (n * Byte.SIZE_BITS))
    }

    companion object {
        private const val BYTE_MASK = 0xFF

        fun allocate(len: Int): PacketBuffer = PacketBuffer(ByteBuffer.allocate(len))
    }
}
