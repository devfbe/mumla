package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.net.InetAddress

class AddressPolicyTest {

    private fun assertRefused(vararg literals: String) = literals.forEach {
        assertWithMessage(it).that(AddressPolicy.PUBLIC_ONLY.isAllowed(InetAddress.getByName(it))).isFalse()
    }

    private fun assertAllowed(vararg literals: String) = literals.forEach {
        assertWithMessage(it).that(AddressPolicy.PUBLIC_ONLY.isAllowed(InetAddress.getByName(it))).isTrue()
    }

    @Test
    fun loopbackAndTheUnspecifiedAddressAreRefused() {
        assertRefused("127.0.0.1", "127.255.255.254", "0.0.0.0", "::1", "::", "::ffff:127.0.0.1")
    }

    @Test
    fun theDevicesOwnNetworkIsRefused() {
        assertRefused(
            "10.0.0.1", "172.16.0.1", "192.168.1.1",
            "169.254.169.254", // link-local, i.e. metadata services
            "224.0.0.1", "ff02::1", // multicast
            "fe80::1",
            "fc00::1", "fd12:3456::1", // unique local, which the JDK calls neither
        )
    }

    /** Special-purpose ranges no JDK predicate covers; CGNAT reaches other subscribers of the carrier. */
    @Test
    fun rangesThatAreNotTheInternetAreRefusedToo() {
        assertRefused("100.64.0.1", "100.127.255.255", "198.18.0.1", "198.19.255.255", "255.255.255.255")
    }

    /** Their neighbours, so the ranges cannot quietly widen and take the internet with them. */
    @Test
    fun theAddressesNextToThoseRangesAreStillAllowed() {
        assertAllowed("100.63.255.255", "100.128.0.1", "198.17.255.255", "198.20.0.1", "255.255.255.254")
    }

    @Test
    fun publicAddressesAreAllowed() {
        assertAllowed("93.184.216.34", "8.8.8.8", "2606:2800:220:1:248:1893:25c8:1946")
    }
}
