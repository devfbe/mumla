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
import se.lublin.humla.session.AudioDeviceCategory

/**
 * The echo canceller is on by default now - on the speaker and the earpiece - and this is the test
 * that used to stop exactly that, rewritten to the fix it was waiting for.
 *
 * **What was measured, on a Galaxy S25.** With a canceller on, the user heard nobody at all. Every
 * canceller asks for `MODE_IN_COMMUNICATION` (`AudioSourcePolicy.needsCommunicationMode`), and in
 * that mode Android routes output by the *communication device* - which a track on `STREAM_MUSIC`
 * does not follow. The canceller had to stay off until the route was fixed: the communication
 * device chosen explicitly, and the track on the communication stream.
 *
 * **The fix, and what this test now pins.** The audio router holds the communication mode for the
 * whole session and routes every device explicitly, the speaker included, and playback is always
 * on [Settings.PLAYBACK_STREAM], the voice-call stream. So a canceller's communication mode is
 * never in conflict with the stream, whichever device it comes on for.
 *
 * **What the host cannot say.** That the route is audible on an S25. Device QA: join a channel on
 * the speaker, listen, switch to the earpiece and back.
 */
class EchoCancellationDefaultRouteTest {
    private companion object {
        val NO_EFFECTS = AndroidAudioEffects(false, false)
    }

    @Test
    fun `playback is always on the voice call stream`() {
        assertThat(Settings.PLAYBACK_STREAM).isEqualTo(AudioManager.STREAM_VOICE_CALL)
    }

    /**
     * The pair that used to be forbidden, now shipped: the speaker's default canceller asks for
     * communication mode, and the stream is the one that follows it.
     */
    @Test
    fun `the speaker's default canceller asks for the mode the voice call stream follows`() {
        assertThat(AudioDeviceCategory.SPEAKER.echoCancellationByDefault).isTrue()
        assertWithMessage("WebRTC's canceller must run in communication mode")
            .that(AudioSourcePolicy.needsCommunicationMode(NO_EFFECTS, EchoCancellationMode.WEBRTC))
            .isTrue()
        assertThat(Settings.PLAYBACK_STREAM).isEqualTo(AudioManager.STREAM_VOICE_CALL)
    }
}
