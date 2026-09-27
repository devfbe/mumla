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
package se.lublin.mumla.channel

import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.UserStats
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.humla.testutil.idleMainLooperFor
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.stubActions
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubEvents
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class UserInfoTest {
    private val stats = UserStats(
        session = 7,
        version = "1.5.634",
        release = "Mumble 1.5.634 (Qt 6)",
        os = "Linux",
        osVersion = "Ubuntu 24.04",
        onlineSeconds = 3723,
        idleSeconds = 61,
        bandwidth = 5000,
        tcpPing = UserStats.Ping(12, 20f, 9f),
        udpPing = null,
        fromClient = UserStats.Packets(100, 1, 2, 3),
        fromServer = null,
        certificates = emptyList(),
        strongCertificate = false,
        address = "192.0.2.1",
        opus = true,
    )

    @Test
    fun theFormatterListsWhatTheServerSentAndSkipsTheRest() {
        val rows = UserInfoFormatter(ApplicationProvider.getApplicationContext()).rows(stats)

        assertThat(rows).containsExactly(
            "Version" to "1.5.634 (Mumble 1.5.634 (Qt 6))",
            "Operating system" to "Linux Ubuntu 24.04",
            "Online" to "1:02:03",
            "Idle" to "01:01",
            "Address" to "192.0.2.1",
            "TCP ping" to "20.0 ms ± 3.0 ms",
            "Packets from client" to "Good 100, late 1, lost 2, resync 3",
            "Bandwidth" to "40.0 kbit/s",
        ).inOrder()
    }

    @Test
    fun theDialogRequestsStatsShowsThemAndRefreshesWhileOpen() {
        val context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        val session = mockk<IHumlaSession>(relaxed = true)
        val actions = session.stubActions()
        installSession(session.stubConnected())
        val events = session.stubEvents()
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val tree = ChannelTreeViewModel(SessionManager.get(app), MumlaRepository.get(app), false, flowOf(true))

        val dialog = showUserInfoDialog(context, "Ann", tree.userStats(7))
        idleMainLooper()
        verify(exactly = 1) { actions.requestUserStats(7) }
        val text = dialog.findViewById<TextView>(R.id.user_info_text)!!
        assertThat(text.text.toString()).isEqualTo("Loading…")

        events.tryEmit(HumlaEvent.UserStatsReceived(stats.copy(session = 8)))
        idleMainLooper()
        assertThat(text.text.toString()).isEqualTo("Loading…")

        events.tryEmit(HumlaEvent.UserStatsReceived(stats))
        idleMainLooper()
        assertThat(text.text.toString()).contains("Operating system: Linux Ubuntu 24.04")

        idleMainLooperFor(Duration.ofSeconds(5))
        verify(exactly = 2) { actions.requestUserStats(7) }

        dialog.dismiss()
        idleMainLooperFor(Duration.ofSeconds(20))
        verify(exactly = 2) { actions.requestUserStats(7) }
    }
}
