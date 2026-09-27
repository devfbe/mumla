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
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.WhisperTargetChannel
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.testutil.HumlaSessionHarness
import se.lublin.humla.testutil.onEvents
import se.lublin.humla.util.VoiceTargetMode

/** The split session API: requests through [SessionActions], never an exception without a connection. */
@RunWith(RobolectricTestRunner::class)
class HumlaSessionActionsTest {

    private val h = HumlaSessionHarness()

    @After
    fun tearDown() = h.close()

    private fun connected() = h.connectAndSynchronize().also {
        h.drainUntil("the synced snapshot") {
            h.session.model.value?.self != null
        }
    }

    @Test
    fun withoutAConnectionEveryCallAnswersAndNothingIsSent() {
        val actions = h.session.actions
        val calls = listOf<() -> Any?>(
            { actions.joinChannel(2) },
            { actions.moveUser(1, 2) },
            { actions.createChannel(0, "n", "d", 0, false) },
            { actions.removeChannel(1) },
            { actions.linkChannels(1, 2) },
            { actions.unlinkChannels(1, 2) },
            { actions.unlinkAllChannels(1) },
            { actions.setListening(1, true) },
            { actions.sendAccessTokens(listOf("t")) },
            { actions.requestPermissions(0) },
            { actions.requestComment(1) },
            { actions.requestChannelDescription(0) },
            { actions.requestUserStats(1) },
            { actions.registerUser(1) },
            { actions.kickBanUser(1, "r", false) },
            { actions.setUserComment(1, "c") },
            { actions.setPrioritySpeaker(1, true) },
            { actions.setMuteDeafState(1, true, false) },
            { actions.setSelfMuteDeafState(true, false) },
            { actions.setLocalMuted(1, true) },
            { actions.setLocalIgnored(1, true) },
            { actions.setLocalVolume(1, 0.5f) },
            { actions.stopWhispering() },
            { h.session.audio.setTalking(false) },
            { h.session.audio.selectAutomaticDevice() },
        )

        calls.forEach { it() }

        assertThat(actions.sendUserTextMessage(1, "m")).isNull()
        assertThat(actions.sendChannelTextMessage(1, "m", false)).isNull()
        assertThat(actions.whisperTo(WhisperTargetChannel(ChannelState(1), false, false, null))).isFalse()
        assertThat(actions.voiceTargetMode).isEqualTo(VoiceTargetMode.NORMAL)
        assertThat(h.session.serverInfo).isNull()
        assertThat(h.session.audio.devices).isEmpty()
        assertThat(h.session.audio.currentBandwidth).isEqualTo(-1)
        assertThat(h.transports.tcps).isEmpty()
    }

    @Test
    fun joiningAChannelMovesTheOwnUser() {
        val tcp = connected()

        h.session.actions.joinChannel(3)

        val move = tcp.sentMessages.filterIsInstance<Mumble.UserState>().last()
        assertThat(move.session).isEqualTo(1)
        assertThat(move.channelId).isEqualTo(3)
    }

    @Test
    fun aSentMessageCarriesItsTargetsAsTheyWereAndIsPublished() {
        connected()
        val published = mutableListOf<HumlaEvent.MessageSent>()
        h.session.onEvents { if (it is HumlaEvent.MessageSent) published += it }

        val message = h.session.actions.sendChannelTextMessage(0, "hi", tree = true)!!
        h.mainLooper.idle()

        assertThat(message.actor).isEqualTo(1)
        assertThat(message.actorName).isEqualTo("me")
        assertThat(message.targetChannels.map { it.name }).containsExactly("Root")
        assertThat(message.targetTrees.map { it.name }).containsExactly("Root")
        assertThat(published.map { it.message }).containsExactly(message)
    }

    @Test
    fun whisperingRegistersATargetAndStoppingFreesIt() {
        val tcp = connected()
        val target = WhisperTargetChannel(ChannelState(0, "Root"), false, true, null)

        assertThat(h.session.actions.whisperTo(target)).isTrue()
        assertThat(h.session.actions.voiceTargetMode).isEqualTo(VoiceTargetMode.WHISPER)
        assertThat(h.session.actions.whisperTarget?.name).isEqualTo("Root")
        assertThat(tcp.sentMessages.filterIsInstance<Mumble.VoiceTarget>()).hasSize(1)

        h.session.actions.stopWhispering()

        assertThat(h.session.actions.voiceTargetMode).isEqualTo(VoiceTargetMode.NORMAL)
        assertThat(h.session.actions.whisperTarget).isNull()
    }

    @Test
    fun theServerInfoIsReadWhileSynchronized() {
        connected()

        val info = h.session.serverInfo!!

        assertThat(info.maxBandwidth).isEqualTo(72_000)
    }
}
