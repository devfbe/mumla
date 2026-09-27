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

package se.lublin.humla.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.minidns.hla.ResolverApi
import org.minidns.util.SrvUtil
import se.lublin.humla.model.Server
import se.lublin.humla.util.Constants
import se.lublin.humla.util.HumlaLog
import java.io.IOException

/** Where a connection to a server is opened. */
data class Endpoint(val host: String, val port: Int)

/**
 * Turns a [Server] into the [Endpoint] to connect to: its own port if it has one, else the
 * `_mumble._tcp` SRV record of its host, else the default port.
 *
 * @param srvLookup Answers `_mumble._tcp.<host>`, or null when there is no record. Blocking.
 */
class ServerResolver internal constructor(
    private val srvLookup: (String) -> Endpoint?,
    private val io: CoroutineDispatcher,
) {
    constructor() : this(::lookupSrv, Dispatchers.IO)

    /**
     * Over Tor ([useTor]) no lookup is made: the proxy resolves the host itself, and an SRV query
     * would leak it to the local resolver.
     */
    suspend fun resolve(server: Server, useTor: Boolean = false): Endpoint {
        val host = server.host
        return when {
            server.port != 0 -> Endpoint(host, server.port)
            useTor || isNumericAddress(host) || host.endsWith(".onion") -> Endpoint(host, Constants.DEFAULT_PORT)
            else -> withContext(io) { srvLookup(host) } ?: Endpoint(host, Constants.DEFAULT_PORT)
        }
    }

    internal companion object {
        private const val TAG = "ServerResolver"
        private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

        /** An IPv4 or IPv6 literal, which has no SRV record; no host name contains a colon. */
        fun isNumericAddress(host: String): Boolean = ':' in host || IPV4.matches(host)

        private fun lookupSrv(host: String): Endpoint? {
            val name = "_mumble._tcp.$host"
            return try {
                val result = ResolverApi.INSTANCE.resolveSrv(name)
                val answers = if (result.wasSuccessful()) result.answersOrEmptySet else null
                when {
                    answers == null -> null.also { HumlaLog.d(TAG, "resolveSrv $name: ${result.responseCode}") }
                    answers.isEmpty() -> null.also { HumlaLog.d(TAG, "resolveSrv $name: empty answer") }
                    else -> {
                        // TODO SRV just picking the first record.
                        val srv = SrvUtil.sortSrvRecords(answers)[0]
                        HumlaLog.d(TAG, "resolved $name SRV: $srv")
                        Endpoint(srv.target.toString(), srv.port)
                    }
                }
            } catch (e: IOException) {
                HumlaLog.d(TAG, "exception in srvResolve: $e")
                null
            } catch (e: IllegalArgumentException) {
                // java.net.IDN.toASCII inside resolveSrv() throws IAE (MiniDNS issue 104).
                HumlaLog.d(TAG, "exception in srvResolve: $e")
                null
            }
        }
    }
}
