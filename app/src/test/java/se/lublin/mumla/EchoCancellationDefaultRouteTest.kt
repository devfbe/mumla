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
import org.junit.Test
import se.lublin.humla.audio.routing.AudioDeviceCategory

/**
 * The echo canceller is on by default on the speaker and the earpiece. Every canceller asks for
 * `MODE_IN_COMMUNICATION`, where output follows the communication device; that only works because
 * the router routes every device explicitly and playback is on [Settings.PLAYBACK_STREAM], the
 * voice-call stream. Whether the route is audible is for device QA.
 */
class EchoCancellationDefaultRouteTest {
    @Test
    fun `playback is always on the voice call stream`() {
        assertThat(Settings.PLAYBACK_STREAM).isEqualTo(AudioManager.STREAM_VOICE_CALL)
    }

    @Test
    fun `the speaker cancels echo by default`() {
        assertThat(AudioDeviceCategory.SPEAKER.echoCancellationByDefault).isTrue()
    }
}
