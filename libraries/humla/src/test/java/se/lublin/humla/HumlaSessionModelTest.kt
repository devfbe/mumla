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
package se.lublin.humla

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.TalkState
import se.lublin.humla.net.FakeTcpTransport
import se.lublin.humla.net.HumlaTCPMessageType
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.testutil.HumlaSessionHarness
import se.lublin.humla.testutil.awaitUntil

/** The session's published model: snapshots, local choices through the writer, talk states. */
@RunWith(RobolectricTestRunner::class)
class HumlaSessionModelTest {

    private val h = HumlaSessionHarness()

    @After
    fun tearDown() = h.close()

    private fun awaitModel(description: String, condition: (ServerState) -> Boolean): ServerState {
        awaitUntil(description = description) {
            h.mainLooper.idle()
            h.session.model.value?.let(condition) == true
        }
        return h.session.model.value!!
    }

    private fun FakeTcpTransport.userJoins(session: Int, name: String) = simulateMessage(
        HumlaTCPMessageType.UserState,
        Mumble.UserState.newBuilder().setSession(session).setName(name).setChannelId(0).build().toByteArray(),
    )

    @Test
    fun theSynchronizedModelKnowsTheServerAndUs() {
        h.connectAndSynchronize()

        val model = awaitModel("the synced snapshot") { it.self != null }

        assertThat(model.self!!.name).isEqualTo("me")
        assertThat(model.selfChannel!!.name).isEqualTo("Root")
    }

    @Test
    fun aLocalMuteAndVolumeReachTheModelAndThePlayback() {
        val tcp = h.connectAndSynchronize()
        tcp.userJoins(2, "Ann")
        awaitModel("Ann") { it.user(2) != null }

        h.session.actions.setLocalMuted(2, true)
        h.session.actions.setLocalVolume(2, 0.5f)

        val model = awaitModel("Ann muted at half volume") { it.user(2)!!.localVolume == 0.5f }
        assertThat(model.user(2)!!.isLocalMuted).isTrue()
        h.drainUntil("the pipeline") { h.audioFactory.hosts.isNotEmpty() }
        val params = h.audioFactory.hosts.single().outputListener.playbackParams
        assertThat(params.isMuted(2)).isTrue()
        assertThat(params.volume(2)).isEqualTo(0.5f)
        assertThat(params.isMuted(1)).isFalse()
    }

    @Test
    fun aLocalChoiceWithoutAConnectionIsIgnored() {
        h.session.actions.setLocalMuted(2, true)

        assertThat(h.session.model.value).isNull()
    }

    @Test
    fun talkStatesArePublishedAndClearedWhenTheConnectionEnds() {
        h.connectAndSynchronize()
        awaitModel("the synced snapshot") { it.self != null }
        h.drainUntil("the pipeline") { h.audioFactory.hosts.isNotEmpty() }
        val output = h.audioFactory.hosts.single().outputListener

        Thread { output.onTalkStateUpdated(2, TalkState.SHOUTING) }.apply { start() }.join()
        h.mainLooper.idle()
        assertThat(h.session.talkStates.value).containsExactly(2, TalkState.SHOUTING)

        h.session.disconnect()
        h.mainLooper.idle()

        assertThat(h.session.talkStates.value).isEmpty()
        assertThat(h.session.model.value).isNull()
    }
}
