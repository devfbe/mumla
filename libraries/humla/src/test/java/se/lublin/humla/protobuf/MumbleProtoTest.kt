package se.lublin.humla.protobuf

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import org.junit.Test

/**
 * Pins the wire format produced by the protobuf Gradle plugin (protoc/protobuf-java 4.36.2)
 * against hand-verified proto2 bytes, so a future protobuf bump that silently changes encoding
 * (varint layout, field ordering, enum representation, unknown-field handling, proto2 explicit
 * presence semantics) is caught here rather than against a live Mumble server.
 */
class MumbleProtoTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

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

    @Test
    fun `Authenticate encodes repeated string and repeated int32 fields, including negative values`() {
        val bytes = Mumble.Authenticate.newBuilder()
            .setUsername("u")
            .addTokens("t1")
            .addTokens("t2")
            .addCeltVersions(-2147483637)
            .addCeltVersions(5)
            .setOpus(false)
            .build()
            .toByteArray()

        assertThat(bytes).isEqualTo(
            hex("0a0175 1a027431 1a027432 20 8b808080f8ffffffff01 2005 2800")
        )

        val parsed = Mumble.Authenticate.parseFrom(bytes)
        assertThat(parsed.username).isEqualTo("u")
        assertThat(parsed.tokensList).containsExactly("t1", "t2").inOrder()
        assertThat(parsed.celtVersionsList).containsExactly(-2147483637, 5).inOrder()
        assertThat(parsed.opus).isFalse()
    }

    @Test
    fun `Reject encodes its enum field as a varint`() {
        val bytes = Mumble.Reject.newBuilder()
            .setType(Mumble.Reject.RejectType.WrongServerPW)
            .setReason("no")
            .build()
            .toByteArray()

        assertThat(bytes).isEqualTo(hex("0804 12026e6f"))

        val parsed = Mumble.Reject.parseFrom(bytes)
        assertThat(parsed.type).isEqualTo(Mumble.Reject.RejectType.WrongServerPW)
        assertThat(parsed.reason).isEqualTo("no")
    }

    @Test
    fun `CryptSetup encodes a bytes field verbatim`() {
        val key = byteArrayOf(0x00, 0xff.toByte(), 0x7f)
        val bytes = Mumble.CryptSetup.newBuilder()
            .setKey(ByteString.copyFrom(key))
            .build()
            .toByteArray()

        assertThat(bytes).isEqualTo(hex("0a0300ff7f"))

        val parsed = Mumble.CryptSetup.parseFrom(bytes)
        assertThat(parsed.key.toByteArray()).isEqualTo(key)
    }

    @Test
    fun `UserStats encodes a nested message and a repeated bytes field`() {
        val fromClient = Mumble.UserStats.Stats.newBuilder()
            .setGood(3)
            .setLost(4)
            .build()
        val bytes = Mumble.UserStats.newBuilder()
            .setSession(7)
            .addCertificates(ByteString.copyFrom(byteArrayOf(0x01, 0x02)))
            .setFromClient(fromClient)
            .build()
            .toByteArray()

        assertThat(bytes).isEqualTo(hex("08071a020102220408031804"))

        val parsed = Mumble.UserStats.parseFrom(bytes)
        assertThat(parsed.session).isEqualTo(7)
        assertThat(parsed.certificatesList).containsExactly(ByteString.copyFrom(byteArrayOf(0x01, 0x02)))
        assertThat(parsed.fromClient.good).isEqualTo(3)
        assertThat(parsed.fromClient.lost).isEqualTo(4)
    }

    @Test
    fun `unknown fields survive a parse and re-serialize`() {
        // Mumble servers may send fields a client build does not know about; forward
        // compatibility depends on those bytes being preserved verbatim across a round trip.
        val known = Mumble.Version.newBuilder()
            .setVersion(0x10305)
            .setRelease("Mumla")
            .build()
            .toByteArray()
        // Field 111, wire type 0 (varint), value 42: tag = (111 << 3) | 0 = 888 = varint f8 06.
        val unknownFieldBytes = byteArrayOf(0xf8.toByte(), 0x06, 0x2a)
        val combined = known + unknownFieldBytes

        val parsed = Mumble.Version.parseFrom(combined)
        assertThat(parsed.unknownFields.asMap().keys).contains(111)

        val reserialized = parsed.toByteArray()
        assertThat(reserialized).isEqualTo(combined)
        assertThat(reserialized.copyOfRange(reserialized.size - unknownFieldBytes.size, reserialized.size))
            .isEqualTo(unknownFieldBytes)
    }

    @Test
    fun `CodecVersion honors proto2 defaults and explicit presence`() {
        val unset = Mumble.CodecVersion.newBuilder()

        // Defaults apply to the getter even though the field was never set...
        assertThat(unset.hasPreferAlpha()).isFalse()
        assertThat(unset.preferAlpha).isTrue()
        assertThat(unset.hasOpus()).isFalse()
        assertThat(unset.opus).isFalse()

        // ...but unset fields (required or optional) are not emitted on the wire.
        assertThat(unset.buildPartial().toByteArray()).isEmpty()

        // Explicitly setting a field to its own default value still sets its presence bit:
        // proto2 has explicit presence, unlike proto3's implicit-presence scalars.
        val opusFalse = Mumble.CodecVersion.newBuilder()
            .setOpus(false)
            .buildPartial()
        assertThat(opusFalse.hasOpus()).isTrue()
        // field 4 (opus), wire type 0 (varint): tag = (4 << 3) | 0 = 0x20.
        assertThat(opusFalse.toByteArray()).isEqualTo(hex("2000"))
    }
}
