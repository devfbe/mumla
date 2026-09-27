/*
 * Copyright (C) 2014 Andrew Comminos
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

import se.lublin.humla.util.HumlaLog
import java.io.FileInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Creates the TLS sockets of one connection. The server is trusted if the system trusts its chain
 * and the leaf names [peerHost], or if its leaf matches a certificate the user pinned for
 * [peerHost] in the trust store; see [ServerTrustManager].
 *
 * @param peerHost The host name the user entered: certificates are verified against it, it is sent
 *        as SNI and it selects the pins, even when an SRV record points the connection at another host.
 */
internal class HumlaSSLSocketFactory internal constructor(
    keystore: KeyStore?,
    keystorePassword: String?,
    trustStore: KeyStore?,
    systemTrust: X509TrustManager,
    private val peerHost: String,
) {
    private val context: SSLContext = SSLContext.getInstance("TLS")
    private val trustManager: ServerTrustManager

    init {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keystore, keystorePassword?.toCharArray() ?: CharArray(0))

        val pins = if (trustStore != null) CertificatePins.forHost(trustStore, peerHost) else emptySet()
        HumlaLog.i(TAG, if (pins.isEmpty()) "No pinned certificate for this host" else "Using pinned certificate(s)")
        trustManager = ServerTrustManager(systemTrust, peerHost, pins)
        context.init(kmf.keyManagers, arrayOf(trustManager), null)
    }

    /** Trusts the system store and the pins in the trust store at [trustStorePath], if any. */
    constructor(
        keystore: KeyStore?,
        keystorePassword: String?,
        trustStorePath: String?,
        trustStorePassword: String?,
        trustStoreFormat: String?,
        peerHost: String,
    ) : this(
        keystore,
        keystorePassword,
        trustStorePath?.let {
            loadTrustStore(it, requireNotNull(trustStorePassword), requireNotNull(trustStoreFormat))
        },
        systemTrustManager(),
        peerHost,
    )

    /** The server's certificate chain, or null before the handshake. */
    val serverChain: Array<X509Certificate>?
        get() = trustManager.serverChain

    /** Why the server certificate was rejected, or [TrustFailure.NONE]. */
    val trustFailure: TrustFailure
        get() = trustManager.failure

    /** A socket that reaches [host] through the SOCKS5 proxy, which resolves the host. */
    fun createTorSocket(host: String, port: Int, proxyHost: String, proxyPort: Int): SSLSocket {
        val socket = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxyHost, proxyPort)))
        socket.connect(InetSocketAddress.createUnresolved(host, port))
        return configure(context.socketFactory.createSocket(socket, host, port, true) as SSLSocket)
    }

    fun createSocket(host: String, port: Int): SSLSocket =
        configure(context.socketFactory.createSocket(InetAddress.getByName(host), port) as SSLSocket)

    private fun configure(socket: SSLSocket): SSLSocket {
        if (peerHost.isNotEmpty() && !HostnameMatcher.isIpLiteral(peerHost)) {
            try {
                socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(peerHost)) }
            } catch (e: IllegalArgumentException) {
                HumlaLog.w(TAG, "Not sending SNI for a host name SNI cannot carry", e)
            }
        }
        return socket
    }

    companion object {
        private val TAG = HumlaSSLSocketFactory::class.java.name

        private fun systemTrustManager(): X509TrustManager {
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            return tmf.trustManagers[0] as X509TrustManager
        }

        /** Loads the trust store file at [path], closing it on every path: this runs once per connection attempt. */
        internal fun loadTrustStore(path: String, password: String, format: String): KeyStore {
            val trustStore = KeyStore.getInstance(format)
            FileInputStream(path).use { trustStore.load(it, password.toCharArray()) }
            return trustStore
        }
    }
}
