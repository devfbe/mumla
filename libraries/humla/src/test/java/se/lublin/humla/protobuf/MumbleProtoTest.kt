package se.lublin.humla.protobuf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MumbleProtoTest {

    @Test
    fun `version message serializes to the expected proto2 bytes and parses back`() {
        val bytes = Mumble.Version.newBuilder()
            .setVersion(0x10305)
            .setRelease("Mumla")
            .build()
            .toByteArray()

        // field 1 (varint) 0x10305 = 66309 -> 0x85 0x86 0x04 ; field 2 (len-delimited) "Mumla"
        assertThat(bytes).isEqualTo(
            byteArrayOf(0x08, 0x85.toByte(), 0x86.toByte(), 0x04, 0x12, 0x05, 0x4d, 0x75, 0x6d, 0x6c, 0x61)
        )
        val parsed = Mumble.Version.parseFrom(bytes)
        assertThat(parsed.version).isEqualTo(0x10305)
        assertThat(parsed.release).isEqualTo("Mumla")
    }
}
