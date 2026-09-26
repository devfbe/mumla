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

package se.lublin.humla.net

import java.net.InetAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.X509TrustManager

/** Thrown when a host with a pinned certificate presents a different, not system-trusted one. */
class CertificateChangedException(val host: String) :
    CertificateException("The certificate of $host differs from the one trusted before")

/** Why the last server certificate check failed, if it did. */
enum class TrustFailure { NONE, UNTRUSTED, CHANGED }

/**
 * Per-host certificate pins: the SHA-256 of a leaf certificate the user accepted for that host.
 * They are read from the app's trust store, where each accepted certificate is stored under the
 * host name as its alias.
 */
object CertificatePins {
    /** Lower-case hex SHA-256 of the DER encoding. */
    fun fingerprint(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** The trust store alias for [host]: lower case, without a trailing dot. */
    fun aliasFor(host: String): String = HostnameMatcher.normalize(host)

    /** Pins stored for [host] in [trustStore]; aliases are compared case-insensitively. */
    fun forHost(trustStore: KeyStore?, host: String): Set<String> {
        if (trustStore == null) return emptySet()
        val wanted = aliasFor(host)
        return trustStore.aliases().toList()
            .filter { aliasFor(it) == wanted }
            .mapNotNull { trustStore.getCertificate(it) as? X509Certificate }
            .map(::fingerprint)
            .toSet()
    }
}

/** RFC 6125 style matching of a host name or IP literal against a certificate's subjectAltNames. */
object HostnameMatcher {
    private const val SAN_DNS = 2
    private const val SAN_IP = 7
    private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    internal fun normalize(host: String): String = host.trim().trimEnd('.').lowercase(Locale.ROOT)

    /** True for IPv4 and IPv6 literals, which are matched against IP SANs and never sent as SNI. */
    fun isIpLiteral(host: String): Boolean = host.contains(':') || IPV4.matches(host)

    /** Whether [certificate] names [host]. The subject CN is not consulted. */
    fun matches(host: String, certificate: X509Certificate): Boolean {
        val names = try {
            certificate.subjectAlternativeNames ?: return false
        } catch (e: CertificateParsingException) {
            return false
        }
        val target = normalize(host.removePrefix("[").removeSuffix("]"))
        if (target.isEmpty()) return false
        return if (isIpLiteral(target)) {
            val address = parseIp(target) ?: return false
            names.any { it.size >= 2 && it[0] == SAN_IP && parseIp(it[1] as? String) == address }
        } else {
            names.any { it.size >= 2 && it[0] == SAN_DNS && matchesDnsName(target, it[1] as? String) }
        }
    }

    private fun parseIp(literal: String?): InetAddress? {
        if (literal == null || !isIpLiteral(literal)) return null
        return try {
            InetAddress.getByName(literal) // a literal never triggers a lookup
        } catch (e: Exception) {
            null
        }
    }

    private fun matchesDnsName(host: String, pattern: String?): Boolean {
        if (pattern == null) return false
        val name = normalize(pattern)
        if (name.isEmpty()) return false
        if (!name.contains('*')) return host == name
        // Only a whole left-most label may be a wildcard, it spans exactly one label, and it must
        // leave at least two labels (no "*.com").
        if (!name.startsWith("*.") || name.indexOf('*', 1) != -1) return false
        val suffix = name.substring(1) // ".example.com"
        if (suffix.count { it == '.' } < 2) return false
        if (!host.endsWith(suffix)) return false
        val label = host.substring(0, host.length - suffix.length)
        return label.isNotEmpty() && !label.contains('.')
    }
}

/**
 * Accepts a server chain the system trusts for [host], or a leaf whose SHA-256 is one of [pins].
 * A chain that is neither fails; it fails with [CertificateChangedException] when [host] has pins,
 * so a changed certificate can be told apart from a first contact.
 */
class ServerTrustManager(
    private val system: X509TrustManager,
    private val host: String,
    private val pins: Set<String>,
) : X509TrustManager {

    /** The chain from the most recent server check, trusted or not. */
    @Volatile var serverChain: Array<X509Certificate>? = null
        private set

    @Volatile var failure: TrustFailure = TrustFailure.NONE
        private set

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
        system.checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        serverChain = chain
        failure = TrustFailure.NONE
        if (chain.isEmpty()) {
            failure = TrustFailure.UNTRUSTED
            throw CertificateException("Empty server certificate chain")
        }
        val systemError = try {
            system.checkServerTrusted(chain, authType)
            if (HostnameMatcher.matches(host, chain[0])) return
            CertificateException("The certificate does not name $host")
        } catch (e: CertificateException) {
            e
        }
        if (pins.isEmpty()) {
            failure = TrustFailure.UNTRUSTED
            throw systemError
        }
        if (CertificatePins.fingerprint(chain[0]) in pins) return
        failure = TrustFailure.CHANGED
        throw CertificateChangedException(host).apply { initCause(systemError) }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
}
