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

package se.lublin.mumla.channel

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.UserState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.audio.AudioPanelSheet
import se.lublin.mumla.testing.addNow
import se.lublin.mumla.testing.hostWith
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubModel
import se.lublin.mumla.testing.stubTalkStates
import se.lublin.mumla.ui.showSnackbar

/**
 * The channel screen's control bar: mute, deafen and the audio panel around push-to-talk at the
 * bottom, or under the tabs with push-to-talk alone at the bottom.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelFragmentControlBarTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val session = mockk<IHumlaSession>(relaxed = true)
    private lateinit var model: MutableStateFlow<ServerState?>
    private lateinit var talkStates: MutableStateFlow<Map<Int, TalkState>>
    private lateinit var fragment: ChannelFragment

    private fun me(muted: Boolean = false, deafened: Boolean = false) = serverState(self = SELF) {
        channel(0, "Root")
        user(UserState(SELF, "me", 0, isSelfMuted = muted || deafened, isSelfDeafened = deafened))
    }

    /** Shows the screen with [inputMethod], and the bar where [position] says, else where it is by default. */
    private fun show(inputMethod: String = Settings.ARRAY_INPUT_METHOD_PTT, position: String? = null) {
        preferences.edit(commit = true) {
            putString(Settings.INPUT_METHOD.key, inputMethod)
            position?.let { putString(Settings.CONTROL_BAR_POSITION.key, it) }
        }
        model = session.stubModel(me())
        talkStates = session.stubTalkStates()
        session.stubConnected()
        fragment = hostWith(session).get().addNow(ChannelFragment(), "channel", inContent = true)
        idleMainLooper()
    }

    private fun <T : View> find(id: Int): T = fragment.requireView().findViewById(id)

    private fun parentOf(id: Int): Int = (find<View>(id).parent as ViewGroup).id

    private fun setPreference(key: String, value: String) {
        preferences.edit(commit = true) { putString(key, value) }
        idleMainLooper()
    }

    @Test
    fun `at the bottom by default, push-to-talk is the tallest control, between the actions`() {
        show()

        assertThat(find<View>(R.id.control_bar).isShown).isTrue()
        assertThat(find<View>(R.id.control_bar_top).visibility).isEqualTo(View.GONE)
        assertThat(parentOf(R.id.control_mute)).isEqualTo(R.id.control_bar_start)
        assertThat(parentOf(R.id.control_deafen)).isEqualTo(R.id.control_bar_start)
        assertThat(parentOf(R.id.control_audio)).isEqualTo(R.id.control_bar_end)
        assertThat(find<View>(R.id.pushtotalk).isShown).isTrue()
        assertThat(find<View>(R.id.pushtotalk).layoutParams.height)
            .isGreaterThan(find<View>(R.id.control_mute).layoutParams.height)
        assertThat(find<View>(R.id.control_talk_state).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `at the top, the actions sit under the tabs and push-to-talk spans the bottom`() {
        show(position = Settings.ARRAY_CONTROL_BAR_TOP)

        assertThat(find<View>(R.id.control_bar_top).isShown).isTrue()
        for (action in listOf(R.id.control_mute, R.id.control_deafen, R.id.control_audio)) {
            assertThat(parentOf(action)).isEqualTo(R.id.control_bar_top)
        }
        assertThat(find<View>(R.id.control_bar_start).visibility).isEqualTo(View.GONE)
        assertThat(find<View>(R.id.control_bar_end).visibility).isEqualTo(View.GONE)
        assertThat(find<View>(R.id.pushtotalk).isShown).isTrue()
        assertThat(find<View>(R.id.control_talk_state).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `changing the position moves the bar at once`() {
        show()

        setPreference(Settings.CONTROL_BAR_POSITION.key, Settings.ARRAY_CONTROL_BAR_TOP)
        assertThat(parentOf(R.id.control_mute)).isEqualTo(R.id.control_bar_top)
        assertThat(find<View>(R.id.control_bar_top).isShown).isTrue()

        setPreference(Settings.CONTROL_BAR_POSITION.key, Settings.ARRAY_CONTROL_BAR_BOTTOM)
        assertThat(parentOf(R.id.control_mute)).isEqualTo(R.id.control_bar_start)
        assertThat(find<View>(R.id.control_bar_top).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `without push-to-talk the bottom bar shows whether we transmit`() {
        show(inputMethod = Settings.ARRAY_INPUT_METHOD_VOICE)
        val talkState = find<View>(R.id.control_talk_state)

        assertThat(find<View>(R.id.pushtotalk_view).visibility).isEqualTo(View.GONE)
        assertThat(talkState.isShown).isTrue()
        assertThat(ViewCompat.getStateDescription(talkState))
            .isEqualTo(context.getString(R.string.a11y_not_transmitting))

        talkStates.value = mapOf(SELF to TalkState.TALKING)
        idleMainLooper()

        assertThat(talkState.isActivated).isTrue()
        assertThat(ViewCompat.getStateDescription(talkState)).isEqualTo(context.getString(R.string.a11y_transmitting))
    }

    @Test
    fun `a hidden push-to-talk button leaves the talk state in its place`() {
        show()

        preferences.edit(commit = true) { putBoolean(Settings.PUSH_BUTTON_HIDE.key, true) }
        idleMainLooper()

        assertThat(find<View>(R.id.pushtotalk_view).visibility).isEqualTo(View.GONE)
        assertThat(find<View>(R.id.control_talk_state).isShown).isTrue()
    }

    @Test
    fun `at the top without push-to-talk nothing is left at the bottom`() {
        show(inputMethod = Settings.ARRAY_INPUT_METHOD_CONTINUOUS, position = Settings.ARRAY_CONTROL_BAR_TOP)

        assertThat(find<View>(R.id.control_bar).visibility).isEqualTo(View.GONE)
    }

    @Test
    fun `mute and deafen say what a tap does, and the state they change`() {
        show()
        val mute = find<View>(R.id.control_mute)
        val deafen = find<View>(R.id.control_deafen)
        assertThat(mute.contentDescription.toString()).isEqualTo(context.getString(R.string.mute))
        assertThat(ViewCompat.getStateDescription(mute)).isNull()
        assertThat(deafen.contentDescription.toString()).isEqualTo(context.getString(R.string.deafen))

        model.value = me(deafened = true)
        idleMainLooper()

        assertThat(mute.contentDescription.toString()).isEqualTo(context.getString(R.string.unmute))
        assertThat(ViewCompat.getStateDescription(mute)).isEqualTo(context.getString(R.string.a11y_state_muted))
        assertThat(deafen.contentDescription.toString()).isEqualTo(context.getString(R.string.undeafen))
        assertThat(ViewCompat.getStateDescription(deafen)).isEqualTo(context.getString(R.string.a11y_state_deafened))
    }

    @Test
    fun `a tap on mute mutes, a tap on deafen deafens`() {
        show()

        find<View>(R.id.control_mute).performClick()
        verify { session.actions.setSelfMuteDeafState(true, false) }

        find<View>(R.id.control_deafen).performClick()
        verify { session.actions.setSelfMuteDeafState(true, true) }
    }

    @Test
    fun `the audio action opens the audio panel`() {
        show()

        find<View>(R.id.control_audio).performClick()
        idleMainLooper()

        assertThat(fragment.parentFragmentManager.findFragmentByTag(AudioPanelSheet.TAG)).isNotNull()
    }

    @Test
    fun `mute and deafen wait for the session to synchronize`() {
        show()

        model.value = null
        idleMainLooper()

        assertThat(find<View>(R.id.control_mute).isEnabled).isFalse()
        assertThat(find<View>(R.id.control_deafen).isEnabled).isFalse()
    }

    @Test
    fun `snackbars sit above the bottom bar, whichever it is`() {
        show()
        assertThat(fragment.requireActivity().showSnackbar("one").anchorView?.id).isEqualTo(R.id.control_bar)

        setPreference(Settings.CONTROL_BAR_POSITION.key, Settings.ARRAY_CONTROL_BAR_TOP)
        assertThat(fragment.requireActivity().showSnackbar("two").anchorView?.id).isEqualTo(R.id.control_bar)

        setPreference(Settings.INPUT_METHOD.key, Settings.ARRAY_INPUT_METHOD_VOICE)
        assertThat(fragment.requireActivity().showSnackbar("three").anchorView).isNull()
    }

    private companion object {
        const val SELF = 1
    }
}
