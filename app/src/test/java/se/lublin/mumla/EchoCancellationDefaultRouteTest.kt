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

package se.lublin.mumla

import android.media.AudioManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.AudioSourcePolicy
import se.lublin.humla.audio.capture.EchoCancellationMode

/**
 * The lock on `echo_cancellation_method`'s default, and the device report behind it.
 *
 * **What was measured, on a Galaxy S25.** With the setting on `"system"` the user hears nobody in
 * the channel at all. The chain, every link of it in this repository:
 *
 * 1. `AudioSourcePolicy.needsCommunicationMode` is true for every echo mode but `NONE`.
 * 2. `AudioHandler`'s constructor then calls `setMode(MODE_IN_COMMUNICATION)`.
 * 3. The playback track is opened on the stream `ServerConnectTask:61` picked at connect time,
 *    which is `STREAM_MUSIC` unless the user turned handset mode on -- `AudioOutput.startPlaying`
 *    hands that straight to the `AudioTrack` constructor.
 * 4. In communication mode Android routes output by the *communication device*. A media-stream
 *    track does not follow it, and what comes out of the earpiece-or-nothing route is what the
 *    report describes.
 *
 * **Why this is a test and not a comment.** Switching the default to the WebRTC canceller is a
 * one-word edit in `Settings`, and every effect of it is on a device. This test is the one place
 * that edit runs into on the host: it turns red for `"webrtc"` and for `"system"` alike, and it
 * stays red until whoever moves the default has also moved the route -- `setCommunicationDevice`
 * to the built-in speaker when handset mode is off, with the track on the communication stream.
 * The `AndroidCommunicationDevices` seam that owns that call is in the core stream, not in this
 * branch, which is why the canceller ships selectable-but-off rather than as the default.
 *
 * **What the host cannot say.** That the fixed route really is audible on an S25. Ledger item,
 * device QA: set the method, join a channel, listen -- with and without handset mode.
 */
class EchoCancellationDefaultRouteTest {
    private companion object {
        /** `AudioHandler` asks the policy with both B6 effects off; it does not own them yet. */
        val NO_EFFECTS = AndroidAudioEffects(false, false)
    }

    /**
     * The stream half of the pair, read from the same expression `ServerConnectTask` uses. It is
     * asserted rather than assumed because the whole conflict disappears if this is ever
     * `STREAM_VOICE_CALL` by default -- and then this test is the wrong guard, not a passing one.
     */
    @Test
    fun `the default playback stream is the media stream`() {
        val stream =
            if (Settings.DEFAULT_HANDSET_MODE) AudioManager.STREAM_VOICE_CALL
            else AudioManager.STREAM_MUSIC

        assertThat(stream).isEqualTo(AudioManager.STREAM_MUSIC)
    }

    @Test
    fun `the shipped default never puts the audio manager into communication mode`() {
        val echo = EchoCancellationMode.fromPreferenceValue(Settings.DEFAULT_ECHO_CANCELLATION_METHOD)

        assertWithMessage(
            "the default echo method would force MODE_IN_COMMUNICATION while playback stays on " +
                "STREAM_MUSIC, which is the Galaxy S25 'I hear nobody' report; fix the playback " +
                "route before moving this default",
        ).that(AudioSourcePolicy.needsCommunicationMode(NO_EFFECTS, echo)).isFalse()
    }

    /**
     * The other half of the same rule, so the guard cannot be satisfied by an echo mode that
     * happens to be spelled wrong: every mode that *does* ask for communication mode is named
     * here, and each of them is a value the default must not take until the route is fixed.
     */
    @Test
    fun `both cancellers ask for communication mode, and only none does not`() {
        assertThat(AudioSourcePolicy.needsCommunicationMode(NO_EFFECTS, EchoCancellationMode.NONE))
            .isFalse()
        assertThat(AudioSourcePolicy.needsCommunicationMode(NO_EFFECTS, EchoCancellationMode.ANDROID))
            .isTrue()
        assertThat(AudioSourcePolicy.needsCommunicationMode(NO_EFFECTS, EchoCancellationMode.WEBRTC))
            .isTrue()
    }
}
