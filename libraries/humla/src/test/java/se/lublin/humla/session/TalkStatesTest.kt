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
package se.lublin.humla.session

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.TalkState
import se.lublin.humla.testutil.idleMainLooper

@RunWith(RobolectricTestRunner::class)
class TalkStatesTest {

    private val changes = mutableListOf<Pair<Int, TalkState>>()
    private val talkStates = TalkStates(Looper.getMainLooper()) { session, state -> changes += session to state }

    @Test
    fun onlyTalkersAreKeptAndOnlyChangesArePublished() {
        val first = talkStates.states.value
        talkStates.report(3, TalkState.TALKING)
        talkStates.report(3, TalkState.TALKING)
        talkStates.report(4, TalkState.WHISPERING)
        talkStates.report(4, TalkState.PASSIVE)
        idleMainLooper()

        assertThat(talkStates.states.value).containsExactly(3, TalkState.TALKING)
        assertThat(changes).containsExactly(3 to TalkState.TALKING, 4 to TalkState.WHISPERING, 4 to TalkState.PASSIVE)
            .inOrder()
        assertThat(first).isEmpty()
    }

    @Test
    fun clearingDropsTheReportsStillOnTheirWay() {
        talkStates.report(3, TalkState.TALKING)
        idleMainLooper()
        talkStates.report(4, TalkState.TALKING)

        talkStates.clear()
        idleMainLooper()

        assertThat(talkStates.states.value).isEmpty()
    }
}
