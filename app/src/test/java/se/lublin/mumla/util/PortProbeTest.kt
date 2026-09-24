package se.lublin.mumla.util

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
class PortProbeTest {
    @Test
    fun aListeningPortIsOpen() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            assertThat(runBlocking { isPortOpen("127.0.0.1", server.localPort, 2000) }).isTrue()
        }
    }

    @Test
    fun aClosedPortIsNotOpen() {
        val port = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

        assertThat(runBlocking { isPortOpen("127.0.0.1", port, 2000) }).isFalse()
    }
}
