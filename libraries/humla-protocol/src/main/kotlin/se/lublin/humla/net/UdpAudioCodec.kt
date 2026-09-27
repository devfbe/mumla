/*
 * Copyright (C) 2026 The Mumla authors
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
@file:Suppress("MagicNumber") // Header bits, field numbers and varint prefixes are the wire format.

package se.lublin.humla.net

/**
 * Writes the voice packets this client sends, into a buffer the caller owns and resets: the header
 * first, then the Opus packet, then the trailer. Allocation-free.
 *
 * The protobuf message is written in field order, byte for byte what the generated
 * `MumbleUDP.Audio` serializes; generated classes would allocate a message and a copy of the Opus
 * data for every packet.
 */
internal object UdpAudioEncoder {
    /** The protobuf header byte of an audio message, and of a ping. */
    const val PROTOBUF_AUDIO = 0
    const val PROTOBUF_PING = 1

    private const val LEGACY_TARGET_MASK = 0x1F
    private const val LEGACY_TERMINATOR = 1L shl 13

    /** The type bits of a legacy Opus packet, read once rather than per packet. */
    private val LEGACY_OPUS = HumlaUDPMessageType.UDPVoiceOpus.ordinal shl 5

    private const val TAG_TARGET = (1 shl 3) or WIRE_VARINT
    private const val TAG_FRAME_NUMBER = (4 shl 3) or WIRE_VARINT
    private const val TAG_OPUS_DATA = (5 shl 3) or WIRE_LENGTH_DELIMITED

    /** Field 16 needs a two-byte key: 0x80 0x01. */
    private const val TAG_IS_TERMINATOR = (16 shl 3) or WIRE_VARINT

    /** Everything in front of an Opus packet of [opusLength] bytes. */
    @Suppress("LongParameterList") // One per field of the wire format.
    fun writeHeader(
        protocol: UdpProtocol,
        packet: PacketBuffer,
        target: Int,
        frameNumber: Long,
        opusLength: Int,
        terminator: Boolean,
    ) {
        if (protocol === UdpProtocol.LEGACY) {
            packet.append((LEGACY_OPUS or (target and LEGACY_TARGET_MASK)).toLong())
            packet.writeLong(frameNumber)
            packet.writeLong(opusLength.toLong() or if (terminator) LEGACY_TERMINATOR else 0L)
            return
        }
        packet.append(PROTOBUF_AUDIO.toLong())
        // A oneof member, so a target of 0 is written too.
        writeVarint(packet, TAG_TARGET.toLong())
        writeVarint(packet, target.toLong() and UINT32_MASK)
        if (frameNumber != 0L) {
            writeVarint(packet, TAG_FRAME_NUMBER.toLong())
            writeVarint(packet, frameNumber)
        }
        if (opusLength > 0) {
            writeVarint(packet, TAG_OPUS_DATA.toLong())
            writeVarint(packet, opusLength.toLong())
        }
    }

    /** Everything behind the Opus packet. */
    fun writeTrailer(protocol: UdpProtocol, packet: PacketBuffer, terminator: Boolean) {
        if (protocol === UdpProtocol.PROTOBUF && terminator) {
            writeVarint(packet, TAG_IS_TERMINATOR.toLong())
            writeVarint(packet, 1)
        }
    }

    /** A protobuf varint: seven bits per byte, least significant group first. */
    private fun writeVarint(packet: PacketBuffer, value: Long) {
        var v = value
        while (v and VARINT_PAYLOAD.inv() != 0L) {
            packet.append((v and VARINT_PAYLOAD) or VARINT_CONTINUATION)
            v = v ushr VARINT_SHIFT
        }
        packet.append(v)
    }
}

/**
 * Reads the voice packets and ping replies the server sends, in either [UdpProtocol]. A decoded
 * [VoicePacket] points into the received bytes. Allocation-free; one instance per thread.
 */
internal class UdpPacketDecoder {
    private val reader = WireReader()

    /** Kept while a protobuf message is read; 0 means unset. */
    private var volume = 0f

    /** Decodes the first [length] bytes of a legacy packet; false if it is malformed or holds no audio. */
    fun decodeLegacy(data: ByteArray, length: Int, out: VoicePacket): Boolean {
        out.clear()
        val r = reader
        r.start(data, 0, length)
        val header = r.next()
        val codec = HumlaUDPMessageType.entries.getOrNull(header ushr LEGACY_TYPE_SHIFT)
            ?.takeIf { it != HumlaUDPMessageType.UDPPing }
        out.codec = codec ?: HumlaUDPMessageType.UDPPing
        out.context = header and LEGACY_TARGET_MASK
        out.session = r.readMumbleVarint().toInt()
        out.frameNumber = r.readMumbleVarint()
        if (codec != HumlaUDPMessageType.UDPVoiceOpus) return codec != null && r.ok

        val sizeField = r.readMumbleVarint()
        val size = (sizeField and LEGACY_SIZE_MASK).toInt()
        out.isTerminator = sizeField and LEGACY_TERMINATOR != 0L
        // Positional data may follow the Opus packet; it is not used.
        val valid = r.ok && size > 0 && size <= r.remaining
        if (valid) {
            out.data = data
            out.opusOffset = r.pos
            out.opusLength = size
        }
        return valid
    }

    /**
     * Decodes a protobuf `MumbleUDP.Audio` of [length] bytes at [offset], behind the header byte;
     * false if it is malformed or holds no audio. Unknown fields are skipped.
     */
    fun decodeProtobuf(data: ByteArray, offset: Int, length: Int, out: VoicePacket): Boolean {
        out.clear()
        volume = 0f
        val r = reader
        r.start(data, offset, offset + length)
        while (r.ok && r.remaining > 0) {
            val key = r.readVarint()
            readAudioField(key ushr WIRE_TYPE_BITS, (key and WIRE_TYPE_MASK).toInt(), out)
        }
        if (!r.ok || out.opusLength == 0) return false
        out.data = data
        // Anything unusable is ignored like an unset value.
        out.volumeAdjustment = if (volume > 0f && volume.isFinite()) volume else 1f
        return true
    }

    private fun readAudioField(field: Long, wireType: Int, out: VoicePacket) {
        val r = reader
        val expected = when (field) {
            FIELD_OPUS_DATA -> WIRE_LENGTH_DELIMITED
            FIELD_VOLUME_ADJUSTMENT -> WIRE_FIXED32
            else -> WIRE_VARINT
        }
        if (wireType != expected) {
            r.skip(wireType)
            return
        }
        when (field) {
            FIELD_TARGET -> {
                // The other member of the header oneof, so it unsets the context.
                r.readVarint()
                out.context = VoicePacket.CONTEXT_NORMAL
            }
            FIELD_CONTEXT -> out.context = r.readVarint().toInt()
            FIELD_SENDER_SESSION -> out.session = r.readVarint().toInt()
            FIELD_FRAME_NUMBER -> out.frameNumber = r.readVarint()
            FIELD_OPUS_DATA -> {
                val size = r.readLength()
                out.opusOffset = r.pos
                out.opusLength = size
                r.pos += size
            }
            FIELD_VOLUME_ADJUSTMENT -> volume = Float.fromBits(r.readFixed32())
            FIELD_IS_TERMINATOR -> out.isTerminator = r.readVarint() != 0L
            else -> r.skip(wireType)
        }
    }

    /**
     * The timestamp of a protobuf `MumbleUDP.Ping` of [length] bytes at [offset], behind the
     * header byte, or -1 if it is malformed or empty (mumble `decodePing_protobuf`).
     */
    fun decodePingTimestamp(data: ByteArray, offset: Int, length: Int): Long {
        val r = reader
        r.start(data, offset, offset + length)
        var timestamp = 0L
        val empty = r.remaining <= 0
        while (r.ok && r.remaining > 0) {
            val key = r.readVarint()
            if (key == TAG_PING_TIMESTAMP) timestamp = r.readVarint() else r.skip((key and WIRE_TYPE_MASK).toInt())
        }
        return if (r.ok && !empty) timestamp else -1
    }

    private companion object {
        const val LEGACY_TYPE_SHIFT = 5
        const val LEGACY_TARGET_MASK = 0x1F
        const val LEGACY_SIZE_MASK = 0x1FFFL
        const val LEGACY_TERMINATOR = 1L shl 13

        const val WIRE_TYPE_BITS = 3
        const val WIRE_TYPE_MASK = 0x7L

        const val FIELD_TARGET = 1L
        const val FIELD_CONTEXT = 2L
        const val FIELD_SENDER_SESSION = 3L
        const val FIELD_FRAME_NUMBER = 4L
        const val FIELD_OPUS_DATA = 5L
        const val FIELD_VOLUME_ADJUSTMENT = 7L
        const val FIELD_IS_TERMINATOR = 16L

        const val TAG_PING_TIMESTAMP = (1L shl 3) or WIRE_VARINT.toLong()
    }
}

/** Reads Mumble and protobuf varints from a byte range. [ok] turns false at the first bad read. */
private class WireReader {
    private var data = EMPTY
    var pos = 0
    private var limit = 0
    var ok = true
        private set

    val remaining: Int get() = limit - pos

    fun start(data: ByteArray, offset: Int, limit: Int) {
        this.data = data
        this.pos = offset
        this.limit = minOf(limit, data.size)
        ok = offset <= this.limit
    }

    fun next(): Int {
        if (pos >= limit) {
            ok = false
            return 0
        }
        return data[pos++].toInt() and BYTE_MASK
    }

    /** A Mumble packet data stream varint, as [PacketBuffer.readLong] reads it. */
    fun readMumbleVarint(): Long {
        val v = next().toLong()
        return when {
            v and 0x80L == 0L -> v and 0x7F
            v and 0xC0L == 0x80L -> (v and 0x3F) shl 8 or next().toLong()
            v and 0xF0L == 0xF0L -> when ((v and 0xFC).toInt()) {
                0xF0 -> readBigEndian(4, 0)
                0xF4 -> readBigEndian(8, 0)
                0xF8 -> readMumbleVarint().inv()
                0xFC -> (v and 0x03).inv()
                else -> {
                    ok = false
                    0
                }
            }
            v and 0xF0L == 0xE0L -> readBigEndian(3, v and 0x0F)
            v and 0xE0L == 0xC0L -> readBigEndian(2, v and 0x1F)
            else -> 0
        }
    }

    private fun readBigEndian(bytes: Int, initial: Long): Long {
        var value = initial
        repeat(bytes) { value = (value shl Byte.SIZE_BITS) or next().toLong() }
        return value
    }

    /** A protobuf varint of at most ten bytes. */
    fun readVarint(): Long {
        var value = 0L
        var shift = 0
        while (shift < Long.SIZE_BITS) {
            val b = next()
            value = value or ((b.toLong() and VARINT_PAYLOAD) shl shift)
            if (b and VARINT_CONTINUATION.toInt() == 0) return value
            shift += VARINT_SHIFT
        }
        ok = false
        return 0
    }

    /** A length prefix that fits in what is left. */
    fun readLength(): Int {
        val size = readVarint()
        if (size < 0 || size > remaining) {
            ok = false
            return 0
        }
        return size.toInt()
    }

    /** A little-endian fixed32. */
    fun readFixed32(): Int {
        var value = 0
        for (i in 0 until Int.SIZE_BYTES) value = value or (next() shl (i * Byte.SIZE_BITS))
        return value
    }

    /** Skips a field of [wireType]; groups are not in proto3. */
    fun skip(wireType: Int) {
        val count = when (wireType) {
            WIRE_VARINT -> {
                readVarint()
                0
            }
            WIRE_FIXED64 -> Long.SIZE_BYTES
            WIRE_LENGTH_DELIMITED -> readLength()
            WIRE_FIXED32 -> Int.SIZE_BYTES
            else -> {
                ok = false
                0
            }
        }
        if (count > remaining) ok = false else pos += count
    }

    private companion object {
        val EMPTY = ByteArray(0)
        const val BYTE_MASK = 0xFF
    }
}

private const val WIRE_VARINT = 0
private const val WIRE_FIXED64 = 1
private const val WIRE_LENGTH_DELIMITED = 2
private const val WIRE_FIXED32 = 5
private const val VARINT_PAYLOAD = 0x7FL
private const val VARINT_CONTINUATION = 0x80L
private const val VARINT_SHIFT = 7
private const val UINT32_MASK = 0xFFFF_FFFFL
