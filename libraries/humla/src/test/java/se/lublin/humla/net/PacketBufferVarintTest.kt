package se.lublin.humla.net

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * The Mumble varint, byte for byte as `PacketDataStream` writes it (mumble `src/PacketDataStream.h`,
 * `operator<<(quint64)` and `operator>>(quint64&)`). The UDP ping carries its timestamp in this
 * form, and that timestamp is microseconds since the connection started: it needs the four-byte
 * form after 4.5 minutes, crosses 2^31 after 36 minutes and needs the eight-byte form after 71.
 */
class PacketBufferVarintTest {

    private fun bytes(vararg b: Int) = b.map { it.toByte() }

    private val forms: List<Pair<Long, List<Byte>>> = listOf(
        0L to bytes(0x00),
        0x7FL to bytes(0x7F),
        0x80L to bytes(0x80, 0x80),
        0x3FFFL to bytes(0xBF, 0xFF),
        0x4000L to bytes(0xC0, 0x40, 0x00),
        0x1FFFFFL to bytes(0xDF, 0xFF, 0xFF),
        0x200000L to bytes(0xE0, 0x20, 0x00, 0x00),
        0x0FFFFFFFL to bytes(0xEF, 0xFF, 0xFF, 0xFF),
        0x10000000L to bytes(0xF0, 0x10, 0x00, 0x00, 0x00),
        0x80000000L to bytes(0xF0, 0x80, 0x00, 0x00, 0x00),
        0xFFFFFFFFL to bytes(0xF0, 0xFF, 0xFF, 0xFF, 0xFF),
        0x100000000L to bytes(0xF4, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00),
        0x0123456789ABCDEFL to bytes(0xF4, 0x01, 0x23, 0x45, 0x67, 0x89, 0xAB, 0xCD, 0xEF),
        Long.MAX_VALUE to bytes(0xF4, 0x7F, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF),
        -1L to bytes(0xFC),
        -4L to bytes(0xFF),
        -5L to bytes(0xF8, 0x04),
        -0x100000000L to bytes(0xF8, 0xF0, 0xFF, 0xFF, 0xFF, 0xFF),
        // ~i does not fit 32 bits: PacketDataStream writes the raw 64-bit pattern.
        Long.MIN_VALUE to bytes(0xF4, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
    )

    @Test
    fun `writeLong produces the PacketDataStream bytes`() {
        for ((value, expected) in forms) {
            val pb = PacketBuffer.allocate(16)
            pb.writeLong(value)
            val length = pb.size()
            pb.rewind()
            assertWithMessage("writeLong(0x%s)", java.lang.Long.toHexString(value))
                .that(pb.dataBlock(length).toList()).isEqualTo(expected)
        }
    }

    @Test
    fun `readLong reads the PacketDataStream bytes back`() {
        for ((value, encoded) in forms) {
            val pb = PacketBuffer(encoded.toByteArray(), encoded.size)
            assertWithMessage("readLong of the form of 0x%s", java.lang.Long.toHexString(value))
                .that(pb.readLong()).isEqualTo(value)
            assertWithMessage("bytes left after 0x%s", java.lang.Long.toHexString(value)).that(pb.left()).isEqualTo(0)
        }
    }
}
