package se.lublin.mumla.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class HostPolicyTest {

    private val policy = PublicHostsOnly()

    /** Resolves nothing and answers from this map, so no test here depends on a working resolver. */
    private fun policyResolving(vararg entries: Pair<String, List<String>>): PublicHostsOnly {
        val table = entries.toMap()
        return PublicHostsOnly { host ->
            val addresses = table[host] ?: throw UnknownHostException(host)
            addresses.map { InetAddress.getByName(it) }.toTypedArray()
        }
    }

    /**
     * Every one of these is a legitimate spelling of the loopback interface that an `<img src>` in a
     * chat message may carry, and the JDK resolves all of them without asking a name server.
     */
    @Test
    fun loopbackIsRefusedHoweverItIsSpelled() {
        assertThat(policy.isAllowed("127.0.0.1")).isFalse()
        assertThat(policy.isAllowed("127.1")).isFalse()
        assertThat(policy.isAllowed("2130706433")).isFalse() // 127.0.0.1 as one decimal number
        assertThat(policy.isAllowed("0")).isFalse() // 0.0.0.0, which routes to this host
        assertThat(policy.isAllowed("0.0.0.0")).isFalse()
        assertThat(policy.isAllowed("[::1]")).isFalse() // as URL.getHost() spells it
        assertThat(policy.isAllowed("::1")).isFalse()
        assertThat(policy.isAllowed("[::ffff:127.0.0.1]")).isFalse()
    }

    @Test
    fun theDevicesOwnNetworkIsRefused() {
        assertThat(policy.isAllowed("10.0.0.1")).isFalse()
        assertThat(policy.isAllowed("172.16.0.1")).isFalse()
        assertThat(policy.isAllowed("192.168.1.1")).isFalse()
        assertThat(policy.isAllowed("169.254.169.254")).isFalse() // link-local, i.e. metadata services
        assertThat(policy.isAllowed("224.0.0.1")).isFalse() // multicast
        assertThat(policy.isAllowed("[fe80::1]")).isFalse()
        assertThat(policy.isAllowed("[fc00::1]")).isFalse() // unique local, which the JDK calls neither
        assertThat(policy.isAllowed("[fd12:3456::1]")).isFalse()
    }

    @Test
    fun publicAddressesAreAllowed() {
        assertThat(policy.isAllowed("93.184.216.34")).isTrue()
        assertThat(policy.isAllowed("8.8.8.8")).isTrue()
        assertThat(policy.isAllowed("[2606:2800:220:1:248:1893:25c8:1946]")).isTrue()
    }

    /**
     * The literal spellings are the easy half. A name is what an attacker actually uses: nothing
     * stops `images.example` from having an A record of 127.0.0.1, so the answer, not the spelling,
     * has to decide.
     */
    @Test
    fun aNameThatResolvesIntoTheLanIsRefused() {
        val resolving = policyResolving("images.example" to listOf("192.168.0.5"))
        assertThat(resolving.isAllowed("images.example")).isFalse()
    }

    @Test
    fun oneBadAnswerAmongGoodOnesIsEnoughToRefuse() {
        val resolving = policyResolving("mixed.example" to listOf("93.184.216.34", "127.0.0.1"))
        assertThat(resolving.isAllowed("mixed.example")).isFalse()
    }

    @Test
    fun aNameThatResolvesPubliclyIsAllowed() {
        val resolving = policyResolving("images.example" to listOf("93.184.216.34"))
        assertThat(resolving.isAllowed("images.example")).isTrue()
    }

    /**
     * A host that does not resolve is left to the connection, which fails on its own and reports
     * NETWORK. Refusing here would turn every DNS hiccup into "unsupported source", which is both
     * untrue and permanently cached.
     */
    @Test
    fun anUnresolvableHostIsLeftToTheConnection() {
        val resolving = policyResolving()
        assertThat(resolving.isAllowed("nothing.invalid")).isTrue()
    }

    @Test
    fun theAnyHostPolicyAllowsWhatTheDefaultRefuses() {
        assertThat(HostPolicy.ANY_HOST.isAllowed("127.0.0.1")).isTrue()
    }
}
