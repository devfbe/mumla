package se.lublin.mumla.chat

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Decides whether an `<img src>` from a chat message may be fetched from a given host.
 *
 * [HttpImageFetcher] asks this **once per redirect hop**, not only for the URL the message carried.
 * A policy consulted only at the front door is undone by a single `302`, because the platform
 * follows a same-scheme redirect to any host it likes.
 */
fun interface HostPolicy {
    fun isAllowed(host: String): Boolean

    companion object {
        /** No opinion. For tests that deliberately talk to a loopback server. */
        val ANY_HOST = HostPolicy { true }
    }
}

/**
 * Refuses hosts that lead back into the device or its local network: loopback, the unspecified
 * address, link-local (which includes the 169.254.169.254 metadata address), site-local and
 * IPv6 unique-local ranges, and multicast.
 *
 * The URL comes from another chat participant, so `<img src="https://192.168.1.1/admin?reset=1">`
 * is a request the phone makes from inside its own network on a stranger's say-so, and the timing
 * of the failure alone tells that stranger which addresses answer. On a device most of this is
 * already out of reach — the manifest allows no cleartext traffic, so plain `http` to a router never
 * leaves the app, and a LAN device rarely has a certificate that passes validation — but neither of
 * those two is this stream's to guarantee, and both are one manifest edit away from gone.
 *
 * **The spelling is not what decides; the answer is.** `127.0.0.1`, `127.1`, `2130706433`, `0` and
 * `[::1]` are all the same interface, and a name an attacker owns can simply have an A record of
 * 192.168.0.1 — which no amount of string matching catches. So every host is resolved and **all**
 * of its addresses have to be public. Resolving costs one lookup that the connection would make
 * anyway; the platform then resolves again when it connects, and nothing here can stop a name that
 * answers differently the second time (DNS rebinding). That residual is documented, not fixed: the
 * fix needs the connection pinned to the address that was checked, which `HttpURLConnection` does
 * not offer.
 *
 * A host that cannot be resolved at all is **allowed** through: the connection then fails on its own
 * and is reported as [ImageError.NETWORK], which is what actually happened. Refusing it would report
 * a DNS outage as [ImageError.UNSUPPORTED] — a terminal error, cached for the life of the process.
 */
class PublicHostsOnly(
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : HostPolicy {

    override fun isAllowed(host: String): Boolean {
        val addresses = try {
            // Bracketed IPv6 literals — the form URL.getHost() hands over — are part of what
            // getAllByName documents itself to accept (RFC 2732), so stripping them here would be
            // a second parser for no gain. Pinned by the `[::1]` cases in HostPolicyTest.
            resolve(host)
        } catch (e: UnknownHostException) {
            return true
        } catch (e: SecurityException) {
            return false
        }
        return addresses.none { it.isLocal() }
    }

    private fun InetAddress.isLocal(): Boolean =
        isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress ||
            isMulticastAddress || isUniqueLocalIpv6()

    /** fc00::/7, which the JDK reports as neither site-local nor link-local. */
    private fun InetAddress.isUniqueLocalIpv6(): Boolean =
        this is Inet6Address && (address[0].toInt() and 0xFE) == 0xFC
}
