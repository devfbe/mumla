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
package se.lublin.humla.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.net.UdpProtocol
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
    fun `the v2 version of a Version message prefers v2, converts v1 and is 0 when unknown`() {
        val both = Mumble.Version.newBuilder().setVersionV1(0x010400).setVersionV2(MumbleVersion.v2(1, 5, 634)).build()
        assertThat(MumbleVersion.v2Of(both)).isEqualTo(MumbleVersion.v2(1, 5, 634))

        val legacyOnly = Mumble.Version.newBuilder().setVersionV1(0x010305).build()
        assertThat(MumbleVersion.v2Of(legacyOnly)).isEqualTo(MumbleVersion.v2(1, 3, 5))

        assertThat(MumbleVersion.v2Of(Mumble.Version.getDefaultInstance())).isEqualTo(0L)
    }

    @Test
    fun `the advertised version speaks the protobuf UDP format`() {
        assertThat(MumbleVersion.CLIENT_V2).isAtLeast(UdpProtocol.PROTOBUF_INTRODUCTION)
        assertThat(MumbleVersion.CLIENT_LEGACY).isEqualTo(MumbleVersion.toLegacy(MumbleVersion.CLIENT_V2))
    }

    @Test
    fun `the client Version message carries both formats`() {
        val msg = MumbleVersion.clientVersion("Mumla", "Android", "14")
        assertThat(msg.versionV1).isEqualTo(0x010500)
        assertThat(msg.versionV2).isEqualTo(MumbleVersion.v2(1, 5, 0))
        assertThat(msg.release).isEqualTo("Mumla")
        assertThat(msg.os).isEqualTo("Android")
        assertThat(msg.osVersion).isEqualTo("14")
    }
}
