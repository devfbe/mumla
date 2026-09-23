package se.lublin.humla.net

/**
 * What a Mumble 1.5 server does with a legacy UDP packet that claims to be a ping, rule for rule:
 * `UDPDecoder<Role::Server>::decode` (the header-less 12-byte probe first, then the header's type
 * bits) and `decodePing_legacy` (mumble `src/MumbleProtocol.cpp`). Written without PacketBuffer on
 * purpose, so that it cannot agree with the code under test by sharing its mistakes.
 *
 * Returns the timestamp the server would echo, or null where the server drops the packet.
 */
object MumbleLegacyPingDecoder {
    private const val LEGACY_PING = 1

    fun decodeAsServer(packet: ByteArray): Long? {
        if (packet.isEmpty()) return null
        // A legacy "extended information" request carries no header and is exactly 12 bytes long.
        if (packet.size == 12 && packet.take(4).all { it.toInt() == 0 }) return null // not a connectivity ping
        val header = packet[0].toInt() and 0xFF
        if ((header shr 5) and 0x7 != LEGACY_PING) return null
        val payload = packet.copyOfRange(1, packet.size)
        if (payload.isEmpty()) return null
        if (payload.size <= 8 + 1) return readVarint(payload)
        // Role::Server: anything longer is only valid as 4 zero bytes and a 64-bit timestamp.
        return null
    }

    /** PacketDataStream `operator>>(quint64&)` for the non-negative forms. */
    private fun readVarint(b: ByteArray): Long? {
        fun at(i: Int): Long? = if (i < b.size) (b[i].toLong() and 0xFF) else null
        val v = at(0) ?: return null
        return when {
            v and 0x80 == 0L -> v and 0x7F
            v and 0xC0 == 0x80L -> ((v and 0x3F) shl 8) or (at(1) ?: return null)
            v and 0xE0 == 0xC0L -> ((v and 0x1F) shl 16) or ((at(1) ?: return null) shl 8) or (at(2) ?: return null)
            v and 0xF0 == 0xE0L ->
                ((v and 0x0F) shl 24) or ((at(1) ?: return null) shl 16) or ((at(2) ?: return null) shl 8) or (at(3) ?: return null)
            v and 0xFC == 0xF0L -> (1..4).fold(0L) { acc, i -> (acc shl 8) or (at(i) ?: return null) }
            v and 0xFC == 0xF4L -> (1..8).fold(0L) { acc, i -> (acc shl 8) or (at(i) ?: return null) }
            else -> null // the negative forms: a timestamp is never negative
        }
    }

    /** What a 1.5 server sends back for a connectivity ping: the header and the varint, nothing else. */
    fun encodeReplyAsServer(timestamp: Long): ByteArray {
        val out = ArrayList<Byte>()
        out += (LEGACY_PING shl 5).toByte()
        fun put(vararg values: Long) = values.forEach { out += (it and 0xFF).toByte() }
        when {
            timestamp < 0x80L -> put(timestamp)
            timestamp < 0x4000L -> put((timestamp shr 8) or 0x80, timestamp)
            timestamp < 0x200000L -> put((timestamp shr 16) or 0xC0, timestamp shr 8, timestamp)
            timestamp < 0x10000000L -> put((timestamp shr 24) or 0xE0, timestamp shr 16, timestamp shr 8, timestamp)
            timestamp < 0x100000000L -> put(0xF0, timestamp shr 24, timestamp shr 16, timestamp shr 8, timestamp)
            else -> put(0xF4, *LongArray(8) { timestamp shr (56 - 8 * it) })
        }
        return out.toByteArray()
    }
}
