/*
 * Copyright (C) 2026 The Mumla Authors
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

package se.lublin.humla.session

import se.lublin.humla.audio.AudioSettings
import se.lublin.humla.model.Server

/**
 * Everything a client configures a session with, handed to `IHumlaSession.configure` as a whole.
 * [connection] takes effect on the next connection; everything else applies live.
 */
data class SessionConfig(
    val connection: ConnectionConfig = ConnectionConfig(),
    val audio: AudioSettings = AudioSettings(),
    /**
     * Stored local volumes by `LocalVolumes.keyOf`, read when a connection starts. Not a
     * connection field: the session keeps its own copy current through `setLocalVolume`.
     */
    val localVolumes: Map<String, Float> = emptyMap(),
    val autoReconnect: Boolean = false,
    /** Sent to the server right away while connected. */
    val accessTokens: List<String> = emptyList(),
) {
    /** Whether going from [previous] to this config only takes effect after a reconnect. */
    fun needsReconnectAfter(previous: SessionConfig): Boolean = connection != previous.connection
}

/** What a connection is opened with; a change only takes effect on the next connection. */
data class ConnectionConfig(
    val server: Server? = null,
    /** Sent to the server as the client's release name. */
    val clientName: String = "",
    val certificate: ClientCertificate? = null,
    /** An optional trust store for CA certificates. */
    val trustStorePath: String? = null,
    val trustStorePassword: String? = null,
    val trustStoreFormat: String? = null,
    val forceTcp: Boolean = false,
    /** Proxy through a local Orbot; implies TCP for voice. */
    val useTor: Boolean = false,
    /** User ids muted locally on connection. */
    val localMuteHistory: List<Int> = emptyList(),
    /** User ids ignored locally on connection. */
    val localIgnoreHistory: List<Int> = emptyList(),
)

/** A PKCS#12 client certificate; equal by content. */
class ClientCertificate(val pkcs12: ByteArray, val password: String? = null) {
    override fun equals(other: Any?): Boolean =
        other is ClientCertificate && pkcs12.contentEquals(other.pkcs12) && password == other.password

    override fun hashCode(): Int = 31 * pkcs12.contentHashCode() + password.hashCode()
}
