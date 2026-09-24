package se.lublin.humla.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

class MumbleVersionTest {

    @Test
    fun `components pack into the v2 layout`() {
        assertThat(MumbleVersion.v2(1, 4, 0)).isEqualTo(0x0001_0004_0000_0000L)
        assertThat(MumbleVersion.v2(1, 5, 735)).isEqualTo(0x0001_0005_02DF_0000L)
    }

    @Test
    fun `v2 converts to the legacy layout`() {
        assertThat(MumbleVersion.toLegacy(MumbleVersion.v2(1, 4, 287))).isEqualTo(0x0104FF)
        assertThat(MumbleVersion.toLegacy(MumbleVersion.v2(1, 5, 7))).isEqualTo(0x010507)
    }

    @Test
    fun `a Version message prefers v2 and falls back to v1`() {
        val both = Mumble.Version.newBuilder().setVersionV1(0x010400).setVersionV2(MumbleVersion.v2(1, 5, 634)).build()
        assertThat(MumbleVersion.legacyOf(both)).isEqualTo(0x0105FF)

        val legacyOnly = Mumble.Version.newBuilder().setVersionV1(0x010305).build()
        assertThat(MumbleVersion.legacyOf(legacyOnly)).isEqualTo(0x010305)

        assertThat(MumbleVersion.legacyOf(Mumble.Version.getDefaultInstance())).isEqualTo(0)
    }

    @Test
    fun `the advertised version stays below the protobuf UDP format`() {
        assertThat(MumbleVersion.CLIENT_V2).isLessThan(MumbleVersion.v2(1, 5, 0))
        assertThat(MumbleVersion.CLIENT_LEGACY).isEqualTo(MumbleVersion.toLegacy(MumbleVersion.CLIENT_V2))
    }

    @Test
    fun `the client Version message carries both formats`() {
        val msg = MumbleVersion.clientVersion("Mumla", "Android", "14")
        assertThat(msg.versionV1).isEqualTo(0x010400)
        assertThat(msg.versionV2).isEqualTo(MumbleVersion.v2(1, 4, 0))
        assertThat(msg.release).isEqualTo("Mumla")
        assertThat(msg.os).isEqualTo("Android")
        assertThat(msg.osVersion).isEqualTo("14")
    }
}
