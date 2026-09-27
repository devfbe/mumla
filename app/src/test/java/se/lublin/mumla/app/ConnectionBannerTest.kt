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

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Server
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.ConnectionBannerBinding
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.stubState
import java.time.Duration

/** The banner shows connecting and reconnecting without blocking the screen, and its Cancel stops them. */
@RunWith(RobolectricTestRunner::class)
class ConnectionBannerTest {

    class HostActivity : ThemedActivity() {
        lateinit var binding: ConnectionBannerBinding

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            binding = ConnectionBannerBinding.inflate(layoutInflater)
            setContentView(binding.root)
            ConnectionBanner(this, binding, Settings.getInstance(this), SessionManager.get(this))
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session: IHumlaSession = mockk(relaxed = true) {
        every { targetServer } returns Server(1, "Home", "example.org", 64738, "me", null)
    }

    init {
        session.stubState(SessionState.Disconnected())
        installSession(session)
    }

    private val controller: ActivityController<HostActivity> =
        Robolectric.buildActivity(HostActivity::class.java).setup()

    private val banner get() = controller.get().binding

    private fun show(state: SessionState) {
        session.stubState(state)
        idleMainLooper()
    }

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    private fun text() = banner.connectionBannerText.text.toString()

    @Test
    fun itShowsWhileConnectingAndHidesWhenConnectedOrDisconnected() {
        assertThat(banner.root.visibility).isEqualTo(View.GONE)

        show(SessionState.Connecting)
        assertThat(banner.root.visibility).isEqualTo(View.VISIBLE)
        assertThat(text()).isEqualTo(context.getString(R.string.connecting_to_server, "example.org"))

        show(SessionState.Connected)
        assertThat(banner.root.visibility).isEqualTo(View.GONE)

        show(SessionState.Reconnecting(null))
        assertThat(text()).isEqualTo(context.getString(R.string.connection_reconnecting_to, "example.org"))

        show(SessionState.Disconnected())
        assertThat(banner.root.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun aLostConnectionCountsDownToTheRetry() {
        show(SessionState.ConnectionLost(5_000L, 1, null))
        assertThat(text()).isEqualTo(context.getString(R.string.connection_lost_retrying_in, 5))

        advance(2_000L)
        assertThat(text()).isEqualTo(context.getString(R.string.connection_lost_retrying_in, 3))

        advance(3_000L)
        assertThat(text()).isEqualTo(context.getString(R.string.connection_lost_reconnecting))
    }

    @Test
    fun theCountdownGoesOnAfterRotation() {
        show(SessionState.ConnectionLost(5_000L, 1, null))
        advance(2_000L)

        controller.recreate()
        idleMainLooper()

        assertThat(text()).isEqualTo(context.getString(R.string.connection_lost_retrying_in, 3))
    }

    @Test
    fun cancelWhileConnectingDisconnects() {
        show(SessionState.Connecting)

        banner.connectionBannerCancel.performClick()

        verify { session.disconnect() }
        verify(exactly = 0) { session.cancelReconnect() }
    }

    @Test
    fun cancelWhileLostOrReconnectingGivesUpTheReconnect() {
        show(SessionState.ConnectionLost(5_000L, 1, null))
        banner.connectionBannerCancel.performClick()
        show(SessionState.Reconnecting(null))
        banner.connectionBannerCancel.performClick()

        verify(exactly = 2) { session.cancelReconnect() }
        verify(exactly = 0) { session.disconnect() }
    }

    @Test
    fun itIsAPoliteLiveRegionWithAFullSizeCancel() {
        val minTouch = 48 * context.resources.displayMetrics.density

        assertThat(banner.connectionBannerText.accessibilityLiveRegion).isEqualTo(View.ACCESSIBILITY_LIVE_REGION_POLITE)
        assertThat(banner.connectionBannerCancel.minHeight.toFloat()).isAtLeast(minTouch)
    }
}
