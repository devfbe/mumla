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

import android.view.View
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.humla.util.VoiceTargetMode
import se.lublin.mumla.R
import se.lublin.mumla.testing.addNow
import se.lublin.mumla.testing.hostWith
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubActions
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubEvents
import se.lublin.mumla.testing.stubModel

/** The prominent whisper panel: shown while a whisper target is armed, its stop button, and its text. */
@RunWith(RobolectricTestRunner::class)
class ChannelFragmentWhisperTest {
    private val session: IHumlaSession = mockk(relaxed = true)
    private val actions = session.stubActions()

    private fun setUp(): ChannelFragment {
        every { actions.whisperTarget } returns null
        every { actions.isWhisperActive } returns false
        session.stubModel(serverState(self = 1) { channel(0, "Root"); user(1, "me") })
        session.stubConnected()
        val controller = hostWith(session)
        return controller.get().addNow(ChannelFragment(), "channel").also { idleMainLooper() }
    }

    private fun whisperTo(name: String) {
        every { actions.whisperTarget } returns mockk<WhisperTarget> { every { this@mockk.name } returns name }
        every { actions.isWhisperActive } returns true
        session.stubEvents().tryEmit(HumlaEvent.VoiceTargetChanged(VoiceTargetMode.WHISPER))
        idleMainLooper()
    }

    @Test
    fun thePanelIsHiddenWithoutAWhisperTarget() {
        val fragment = setUp()

        val panel = fragment.requireView().findViewById<View>(R.id.target_panel)

        assertThat(panel.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun thePanelShowsTheArmedTargetsName() {
        val fragment = setUp()

        whisperTo("Lobby")

        val panel = fragment.requireView().findViewById<View>(R.id.target_panel)
        val text = fragment.requireView().findViewById<TextView>(R.id.target_panel_warning)
        assertThat(panel.visibility).isEqualTo(View.VISIBLE)
        assertThat(text.text.toString()).isEqualTo(fragment.getString(R.string.shout_target, "Lobby"))
    }

    @Test
    fun theStopButtonEndsWhispering() {
        val fragment = setUp()
        whisperTo("Lobby")

        fragment.requireView().findViewById<View>(R.id.target_panel_cancel).performClick()

        verify { actions.stopWhispering() }
    }
}
