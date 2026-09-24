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

import android.util.Log
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.MumbleVersion
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * A user's connection statistics from the server's `UserStats`. A value the server did not send,
 * typically because the local user may not see it, is null.
 */
data class UserStats(
    val session: Int,
    /** The client's version, "major.minor.patch". */
    val version: String?,
    /** The client's own name for its release. */
    val release: String?,
    val os: String?,
    val osVersion: String?,
    val onlineSeconds: Int?,
    val idleSeconds: Int?,
    /** Bytes per second. */
    val bandwidth: Int?,
    val tcpPing: Ping?,
    val udpPing: Ping?,
    /** Packets from the client as the server received them. */
    val fromClient: Packets?,
    /** Packets from the server as the client received them. */
    val fromServer: Packets?,
    /** The client certificate chain, leaf first. */
    val certificates: List<X509Certificate>,
    /** Whether the certificate is signed by a CA the server trusts. */
    val strongCertificate: Boolean,
    val address: String?,
    val opus: Boolean,
) {
    /** Ping round trips in milliseconds. */
    data class Ping(val packets: Int, val averageMillis: Float, val varianceMillis: Float)

    data class Packets(val good: Int, val late: Int, val lost: Int, val resync: Int)

    companion object {
        fun from(msg: Mumble.UserStats): UserStats {
            val version = msg.version.takeIf { msg.hasVersion() }
            return UserStats(
                session = msg.session,
                version = version?.let(MumbleVersion::displayOf),
                release = version?.takeIf { it.hasRelease() }?.release,
                os = version?.takeIf { it.hasOs() }?.os,
                osVersion = version?.takeIf { it.hasOsVersion() }?.osVersion,
                onlineSeconds = msg.onlinesecs.takeIf { msg.hasOnlinesecs() },
                idleSeconds = msg.idlesecs.takeIf { msg.hasIdlesecs() },
                bandwidth = msg.bandwidth.takeIf { msg.hasBandwidth() },
                tcpPing = Ping(msg.tcpPackets, msg.tcpPingAvg, msg.tcpPingVar).takeIf { msg.hasTcpPingAvg() },
                udpPing = Ping(msg.udpPackets, msg.udpPingAvg, msg.udpPingVar).takeIf { msg.hasUdpPingAvg() },
                fromClient = msg.fromClient.takeIf { msg.hasFromClient() }?.let(::packets),
                fromServer = msg.fromServer.takeIf { msg.hasFromServer() }?.let(::packets),
                certificates = certificates(msg),
                strongCertificate = msg.strongCertificate,
                address = msg.address.takeIf { msg.hasAddress() }?.let { address(it.toByteArray()) },
                opus = msg.opus,
            )
        }

        private const val TAG = "UserStats"

        private fun packets(stats: Mumble.UserStats.Stats) = Packets(stats.good, stats.late, stats.lost, stats.resync)

        /** The chain, or empty if any certificate does not parse. */
        private fun certificates(msg: Mumble.UserStats): List<X509Certificate> = try {
            val factory = CertificateFactory.getInstance("X.509")
            msg.certificatesList.map { factory.generateCertificate(it.newInput()) as X509Certificate }
        } catch (e: CertificateException) {
            Log.w(TAG, "Unreadable client certificate", e)
            emptyList()
        }

        /** An IPv4 address mapped into IPv6 shows as IPv4, as the server means it. */
        private fun address(bytes: ByteArray): String? = try {
            InetAddress.getByAddress(bytes).hostAddress
        } catch (e: UnknownHostException) {
            Log.w(TAG, "Unreadable client address", e)
            null
        }
    }
}
