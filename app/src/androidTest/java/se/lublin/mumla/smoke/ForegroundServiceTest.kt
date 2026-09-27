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

package se.lublin.mumla.smoke

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.lublin.humla.model.Server
import se.lublin.humla.session.ConnectionConfig
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import se.lublin.mumla.app.MumlaActivity
import se.lublin.mumla.service.MumlaService
import se.lublin.mumla.session.SessionManager
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * A connect starts the foreground service and the end of the session stops it. The server is a
 * local socket that accepts and never answers the TLS handshake, so the session stays Connecting
 * until the test hangs up.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundServiceTest {
    @get:Rule
    val permissions = grant(Manifest.permission.RECORD_AUDIO)

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sessions = SessionManager.get(context)

    @Suppress("DEPRECATION") // Still lists the caller's own services.
    private fun serviceRunning(): Boolean = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE).any { it.service.className == MumlaService::class.java.name }

    @Test
    fun aSessionRunsTheServiceFromConnectingUntilItEnds() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val client = AtomicReference<Socket?>()
            val acceptor = thread { runCatching { client.set(server.accept()) } }
            ActivityScenario.launch(MumlaActivity::class.java).use {
                val target = Server(Server.NOT_SAVED, "smoke", "127.0.0.1", server.localPort, "smoke", null)
                val config = SessionConfig(ConnectionConfig(server = target))
                InstrumentationRegistry.getInstrumentation().runOnMainSync { sessions.connect(config) }
                try {
                    waitFor("the client's connection") { client.get() != null }
                    waitFor("the foreground service") { serviceRunning() }
                    assertThat(sessions.currentState).isEqualTo(SessionState.Connecting)

                    client.get()!!.close()

                    waitFor("the session to end") { sessions.currentState is SessionState.Disconnected }
                    waitFor("the service to stop") { !serviceRunning() }
                } finally {
                    InstrumentationRegistry.getInstrumentation().runOnMainSync { sessions.disconnect() }
                    acceptor.join()
                }
            }
        }
    }
}
