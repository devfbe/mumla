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
package se.lublin.humla.model

import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import org.junit.Test
import se.lublin.humla.net.TestCertificates
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.MumbleVersion

class UserStatsTest {
    @Test
    fun everyFieldTheServerSentIsKept() {
        val cert = TestCertificates.leaf(cn = "alice").certificate
        val msg = Mumble.UserStats.newBuilder()
            .setSession(5)
            .setVersion(
                Mumble.Version.newBuilder()
                    .setVersionV2(MumbleVersion.v2(1, 5, 634))
                    .setRelease("Mumble 1.5.634")
                    .setOs("Linux")
                    .setOsVersion("Ubuntu 24.04")
            )
            .setOnlinesecs(3723)
            .setIdlesecs(61)
            .setBandwidth(5000)
            .setTcpPackets(12).setTcpPingAvg(20f).setTcpPingVar(9f)
            .setUdpPackets(10).setUdpPingAvg(15f).setUdpPingVar(4f)
            .setFromClient(Mumble.UserStats.Stats.newBuilder().setGood(100).setLate(1).setLost(2).setResync(3))
            .setFromServer(Mumble.UserStats.Stats.newBuilder().setGood(200))
            .addCertificates(ByteString.copyFrom(cert.encoded))
            .setStrongCertificate(true)
            .setAddress(ByteString.copyFrom(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 192.toByte(), 0, 2, 1)))
            .setOpus(true)
            .build()

        val stats = UserStats.from(msg)

        assertThat(stats.session).isEqualTo(5)
        assertThat(stats.version).isEqualTo("1.5.634")
        assertThat(stats.release).isEqualTo("Mumble 1.5.634")
        assertThat(stats.os).isEqualTo("Linux")
        assertThat(stats.osVersion).isEqualTo("Ubuntu 24.04")
        assertThat(stats.onlineSeconds).isEqualTo(3723)
        assertThat(stats.idleSeconds).isEqualTo(61)
        assertThat(stats.bandwidth).isEqualTo(5000)
        assertThat(stats.tcpPing).isEqualTo(UserStats.Ping(12, 20f, 9f))
        assertThat(stats.udpPing).isEqualTo(UserStats.Ping(10, 15f, 4f))
        assertThat(stats.fromClient).isEqualTo(UserStats.Packets(100, 1, 2, 3))
        assertThat(stats.fromServer).isEqualTo(UserStats.Packets(200, 0, 0, 0))
        assertThat(stats.certificates).containsExactly(cert)
        assertThat(stats.strongCertificate).isTrue()
        assertThat(stats.address).isEqualTo("192.0.2.1")
        assertThat(stats.opus).isTrue()
    }

    @Test
    fun whatTheServerLeftOutIsUnknown() {
        val stats = UserStats.from(
            Mumble.UserStats.newBuilder().setSession(5).addCertificates(ByteString.copyFromUtf8("garbage")).build()
        )

        assertThat(stats.version).isNull()
        assertThat(stats.release).isNull()
        assertThat(stats.onlineSeconds).isNull()
        assertThat(stats.idleSeconds).isNull()
        assertThat(stats.bandwidth).isNull()
        assertThat(stats.tcpPing).isNull()
        assertThat(stats.udpPing).isNull()
        assertThat(stats.fromClient).isNull()
        assertThat(stats.address).isNull()
        assertThat(stats.certificates).isEmpty()
    }

    @Test
    fun aLegacyVersionIsShownToo() {
        val stats = UserStats.from(
            Mumble.UserStats.newBuilder()
                .setSession(5)
                .setVersion(Mumble.Version.newBuilder().setVersionV1(0x010305))
                .build()
        )
        assertThat(stats.version).isEqualTo("1.3.5")
    }
}
