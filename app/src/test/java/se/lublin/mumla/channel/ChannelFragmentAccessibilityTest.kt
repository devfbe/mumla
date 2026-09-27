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

import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.AudioControls
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.TalkState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.stubAudio
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubModel
import se.lublin.mumla.testing.stubTalkStates

/** The talk button tells accessibility services whether we are transmitting. */
@RunWith(RobolectricTestRunner::class)
class ChannelFragmentAccessibilityTest {
    private val preferences =
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<Context>())
    private lateinit var fragment: ChannelFragment
    private lateinit var session: IHumlaSession
    private lateinit var audio: AudioControls
    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var talkStates: MutableStateFlow<Map<Int, TalkState>>

    @Before
    fun setUp() {
        preferences.edit(commit = true) { putString(Settings.INPUT_METHOD.key, Settings.ARRAY_INPUT_METHOD_PTT) }
        session = mockk(relaxed = true)
        audio = session.stubAudio()
        session.stubModel(serverState(self = SESSION) { channel(0, "Root"); user(SESSION, "me") })
        talkStates = session.stubTalkStates()
        session.stubConnected()
        controller = Robolectric.buildActivity(ServiceHostActivity::class.java).setup()
        val activity = controller.get()
        activity.bind(session)
        fragment = ChannelFragment()
        activity.supportFragmentManager.beginTransaction().add(fragment, "channel").commitNow()
        idleMainLooper()
    }

    @After
    fun tearDown() = preferences.edit { clear() }

    private val talkButton: View get() = fragment.requireView().findViewById(R.id.pushtotalk)

    @Test
    fun theTalkButtonStatesThatWeTransmitAndStopsWhenWeDoNot() {
        assertThat(ViewCompat.getStateDescription(talkButton)).isNull()

        talkStates.value = mapOf(SESSION to TalkState.TALKING)
        idleMainLooper()
        assertThat(ViewCompat.getStateDescription(talkButton))
            .isEqualTo(fragment.getString(R.string.a11y_transmitting))

        talkStates.value = emptyMap()
        idleMainLooper()
        assertThat(ViewCompat.getStateDescription(talkButton)).isNull()
    }

    private fun clickForAccessibility() =
        talkButton.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)

    @Test
    fun anAccessibilityClickStartsAndStopsTransmitting() {
        every { audio.isTalking } returns false
        assertThat(clickForAccessibility()).isTrue()
        verify(exactly = 1) { audio.setTalking(true) }

        every { audio.isTalking } returns true
        clickForAccessibility()
        verify(exactly = 1) { audio.setTalking(false) }
    }

    @Test
    fun whatAnAccessibilityClickStartedIsReleasedOnPause() {
        every { audio.isTalking } returns false
        clickForAccessibility()

        controller.pause()

        verify(exactly = 1) { audio.setTalking(false) }
    }

    private companion object {
        const val SESSION = 5
    }
}
