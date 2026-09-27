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

package se.lublin.mumla.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.UserState
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.humla.util.VoiceTargetMode
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.selfServerState
import se.lublin.mumla.testing.stubActions
import se.lublin.mumla.testing.stubEvents
import se.lublin.mumla.testing.stubModel
import se.lublin.mumla.testing.stubState
import se.lublin.mumla.testing.stubTalkStates

@RunWith(RobolectricTestRunner::class)
class SessionViewModelTest {
    private val session = mockk<IHumlaSession>(relaxed = true)
    private val state = session.stubState(SessionState.Connecting)
    private val model = session.stubModel(selfServerState(UserState(1, "me", 0)))
    private val talkStates = session.stubTalkStates()
    private val actions = session.stubActions()
    private val viewModel = SessionViewModel(SessionManager.get(ApplicationProvider.getApplicationContext<Context>()))

    @Test
    fun ourStateIsKnownOnlyWhileSynchronized() {
        installSession(session)
        idleMainLooper()
        assertThat(viewModel.self.value).isNull()

        state.value = SessionState.Connected
        idleMainLooper()
        assertThat(viewModel.self.value).isEqualTo(SelfState(false, false, cannotTalk = false, isTalking = false))

        state.value = SessionState.Disconnected()
        idleMainLooper()
        assertThat(viewModel.self.value).isNull()
    }

    @Test
    fun ourStateFollowsTheModelAndOurTalkState() {
        state.value = SessionState.Connected
        installSession(session)

        model.value = selfServerState(UserState(1, "me", 0, isSelfMuted = true, isSelfDeafened = true))
        talkStates.value = mapOf(1 to TalkState.TALKING, 2 to TalkState.SHOUTING)
        idleMainLooper()

        assertThat(viewModel.self.value).isEqualTo(SelfState(true, true, cannotTalk = true, isTalking = true))
    }

    @Test
    fun beingMutedOrSuppressedByTheServerMeansWeCannotTalk() {
        state.value = SessionState.Connected
        installSession(session)

        model.value = selfServerState(UserState(1, "me", 0, isSuppressed = true))
        idleMainLooper()
        assertThat(viewModel.self.value!!.cannotTalk).isTrue()

        model.value = selfServerState(UserState(1, "me", 0, isMuted = true))
        idleMainLooper()
        assertThat(viewModel.self.value!!.cannotTalk).isTrue()
    }

    @Test
    fun theWhisperTargetIsNamedWhileRegisteredWhetherOrNotItIsActive() {
        every { actions.whisperTarget } returns null
        every { actions.isWhisperActive } returns false
        state.value = SessionState.Connected
        installSession(session)
        val events = session.stubEvents()
        idleMainLooper()
        assertThat(viewModel.whisperTarget.value).isNull()
        assertThat(viewModel.isWhisperActive.value).isFalse()

        // Armed but not active: the panel shows the target, without it receiving voice yet.
        every { actions.whisperTarget } returns mockk<WhisperTarget> { every { name } returns "Lobby" }
        every { actions.isWhisperActive } returns false
        events.tryEmit(HumlaEvent.VoiceTargetChanged(VoiceTargetMode.NORMAL))
        idleMainLooper()
        assertThat(viewModel.whisperTarget.value).isEqualTo("Lobby")
        assertThat(viewModel.isWhisperActive.value).isFalse()

        every { actions.isWhisperActive } returns true
        events.tryEmit(HumlaEvent.VoiceTargetChanged(VoiceTargetMode.WHISPER))
        idleMainLooper()
        assertThat(viewModel.whisperTarget.value).isEqualTo("Lobby")
        assertThat(viewModel.isWhisperActive.value).isTrue()

        state.value = SessionState.Disconnected()
        idleMainLooper()
        assertThat(viewModel.whisperTarget.value).isNull()
        assertThat(viewModel.isWhisperActive.value).isFalse()
    }

    @Test
    fun setWhisperActiveActsOnTheConnectedSession() {
        state.value = SessionState.Connected
        installSession(session)

        viewModel.setWhisperActive(true)

        verify { actions.setWhisperActive(true) }
    }

    @Test
    fun theTogglesAndWhisperingActOnTheConnectedSession() {
        state.value = SessionState.Connected
        installSession(session)
        model.value = selfServerState(UserState(1, "me", 0, isSelfMuted = true))

        viewModel.toggleMute()
        viewModel.toggleDeafen()
        viewModel.stopWhispering()

        verify { actions.setSelfMuteDeafState(false, false) }
        verify { actions.setSelfMuteDeafState(true, true) }
        verify { actions.stopWhispering() }
    }
}
