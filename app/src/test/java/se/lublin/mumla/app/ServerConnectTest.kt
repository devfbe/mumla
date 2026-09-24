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
package se.lublin.mumla.app

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.Server
import se.lublin.mumla.service.MumlaService
import se.lublin.mumla.testing.drainMainUntil
import se.lublin.mumla.testing.installDatabase

@RunWith(RobolectricTestRunner::class)
class ServerConnectTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = Server(1, "Home", "example.org", 64738, "me", null)
    private val service: MumlaService = mockk(relaxed = true)

    @Test
    fun theServiceIsStartedConfiguredForTheServerAndConnected() {
        installDatabase(mockk(relaxed = true))
        val component = ComponentName(app, MumlaService::class.java)
        shadowOf(app).setComponentNameAndServiceForBindService(component, MumlaService.MumlaBinder(service))

        startServerConnect(app, server)
        drainMainUntil { runCatching { verify { service.connect() } }.isSuccess }

        verifyOrder {
            service.configure(match { it.server === server })
            service.connect()
        }
        assertThat(shadowOf(app).nextStartedService.component).isEqualTo(component)
        assertThat(shadowOf(app).unboundServiceConnections).hasSize(1)
    }
}
