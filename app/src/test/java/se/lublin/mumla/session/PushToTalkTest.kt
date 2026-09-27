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
package se.lublin.mumla.session

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.SessionState
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.stubState

/** The talk keys act only in push-to-talk, only while connected, and hold or toggle as set. */
@RunWith(RobolectricTestRunner::class)
class PushToTalkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var talking = false
    private val session = mockk<IHumlaSession>(relaxed = true) {
        every { isTalking } answers { talking }
        every { setTalkingState(any()) } answers { talking = firstArg() }
    }
    private val keys = PushToTalk(context)

    init {
        installSession(session)
    }

    private fun pushToTalk(toggle: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Settings.INPUT_METHOD.key, Settings.ARRAY_INPUT_METHOD_PTT)
            .putBoolean(Settings.PTT_TOGGLE.key, toggle)
            .commit()
    }

    @Test
    fun holdToTalkTalksWhileTheKeyIsDown() {
        pushToTalk(toggle = false)

        keys.onKeyDown()
        assertThat(talking).isTrue()
        keys.onKeyUp()
        assertThat(talking).isFalse()
    }

    @Test
    fun toggleToTalkFlipsOnEveryRelease() {
        pushToTalk(toggle = true)

        keys.onKeyDown()
        assertThat(talking).isFalse()
        keys.onKeyUp()
        assertThat(talking).isTrue()
        keys.onKeyDown()
        assertThat(talking).isTrue()
        keys.onKeyUp()
        assertThat(talking).isFalse()
    }

    @Test
    fun theKeysDoNothingOutsidePushToTalk() {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString(Settings.INPUT_METHOD.key, Settings.ARRAY_INPUT_METHOD_VOICE).commit()

        keys.onKeyDown()
        assertThat(talking).isFalse()
        talking = true
        keys.onKeyUp()
        assertThat(talking).isTrue()
    }

    /** Also while the connection is established but not synchronized yet. */
    @Test
    fun theKeysDoNothingWithoutASynchronizedSession() {
        pushToTalk(toggle = false)
        session.stubState(SessionState.Connecting)

        keys.onKeyDown()
        assertThat(talking).isFalse()
        talking = true
        keys.onKeyUp()
        assertThat(talking).isTrue()
    }

    @Test
    fun toggleModeWithoutAConnectionDoesNotFlip() {
        pushToTalk(toggle = true)
        session.stubState(SessionState.Disconnected())

        keys.onKeyUp()

        assertThat(talking).isFalse()
    }

    @Test
    fun aHoldReleaseWhileNotTalkingStaysSilent() {
        pushToTalk(toggle = false)

        keys.onKeyUp()

        assertThat(talking).isFalse()
    }
}
