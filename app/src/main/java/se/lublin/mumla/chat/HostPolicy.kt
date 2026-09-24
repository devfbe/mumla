package se.lublin.mumla.chat

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Decides whether an `<img src>` from a chat message may be fetched from a given host.
 *
 * [HttpImageFetcher] asks this once per redirect hop: a policy consulted only for the original URL
 * is undone by a single `302`.
 */
fun interface HostPolicy {
    fun isAllowed(host: String): Boolean

    companion object {
        /** No opinion. For tests that deliberately talk to a loopback server. */
        val ANY_HOST = HostPolicy { true }
    }
}

/**
 * Refuses hosts that lead back into the device, its local network or the carrier's: loopback, the
 * unspecified address, link-local (incl. the 169.254.169.254 metadata address), site-local and IPv6
 * unique-local ranges, multicast, 100.64.0.0/10 (carrier-grade NAT: other subscribers of the same
 * carrier), 198.18.0.0/15 and 255.255.255.255. Other non-public ranges (240.0.0.0/4, documentation
 * ranges, ...) are not checked on purpose: they reach nothing and fail on their own.
 *
 * The URL comes from another chat participant, so without this `<img src="https://192.168.1.1/...">`
 * would be a request from inside the user's network on a stranger's say-so, and the failure timing
 * would reveal which addresses answer. Cleartext being off by platform default is not relied on.
 *
 * The resolved addresses decide, not the spelling (`127.1`, `2130706433`, `[::1]`, or an attacker's
 * name with a private A record): every address of the host must be public. DNS rebinding between
 * this lookup and the connection's own is a known residual; `HttpURLConnection` cannot pin the
 * checked address.
 *
 * An unresolvable host is allowed: the connection then fails as [ImageError.NETWORK]. Because
 * resolver answers vary (DNS blockers, captive portals, split horizon), [HttpImageFetcher] reports a
 * refusal as [ImageError.NETWORK], which expires.
 */
class PublicHostsOnly(
    private val resolve: (String) -> Array<InetAddress> = InetAddress::getAllByName,
) : HostPolicy {

    override fun isAllowed(host: String): Boolean {
        val addresses = try {
            // getAllByName accepts the bracketed IPv6 literals URL.getHost() returns (RFC 2732).
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
            isMulticastAddress || isUniqueLocalIpv6() || isReservedIpv4()

    /**
     * 100.64.0.0/10, 198.18.0.0/15 and 255.255.255.255, which the JDK has no predicate for.
     * IPv4-mapped addresses arrive as four bytes, so they are covered too.
     */
    private fun InetAddress.isReservedIpv4(): Boolean {
        val bytes = address
        if (bytes.size != 4) return false
        fun byteAt(i: Int) = bytes[i].toInt() and 0xFF
        return (byteAt(0) == 100 && byteAt(1) in 64..127) ||
            (byteAt(0) == 198 && byteAt(1) in 18..19) ||
            (byteAt(0) == 255 && byteAt(1) == 255 && byteAt(2) == 255 && byteAt(3) == 255)
    }

    /** fc00::/7, which the JDK reports as neither site-local nor link-local. */
    private fun InetAddress.isUniqueLocalIpv6(): Boolean =
        this is Inet6Address && (address[0].toInt() and 0xFE) == 0xFC
}
