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

package se.lublin.humla.audio.capture

import android.media.MediaRecorder
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * All 2 x 2 x 2 corners of (noise suppressor, AGC, echo mode) are enumerated: `||` mutated to
 * `xor` survives a per-clause sweep, differing only at (true, true).
 */
class AudioSourcePolicyTest {
    private val mic = MediaRecorder.AudioSource.MIC
    private val voiceComm = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    private val voiceRecognition = MediaRecorder.AudioSource.VOICE_RECOGNITION

    /** Every corner of (noiseSuppressor, automaticGainControl, echo) with the answer it must give. */
    private val corners: List<Triple<AndroidAudioEffects, EchoCancellationMode, Boolean>> =
        listOf(false, true).flatMap { ns ->
            listOf(false, true).flatMap { agc ->
                EchoCancellationMode.entries.map { echo ->
                    Triple(
                        AndroidAudioEffects(noiseSuppressor = ns, automaticGainControl = agc),
                        echo,
                        ns || agc || echo != EchoCancellationMode.NONE,
                    )
                }
            }
        }

    @Test
    fun `communication mode is needed exactly when an effect or a canceller is active`() {
        assertThat(corners).hasSize(8) // four effect corners times two cancellers
        val wrong = corners.filter {
            AudioSourcePolicy.needsCommunicationMode(it.first, it.second) != it.third
        }
        assertThat(wrong).isEmpty()
    }

    @Test
    fun `the source is overridden exactly when communication mode is needed`() {
        val wrong = corners.filter {
            val resolved = AudioSourcePolicy.resolve(mic, it.first, it.second)
            resolved != (if (it.third) voiceComm else mic)
        }
        assertThat(wrong).isEmpty()
    }

    /** The pass-through returns the caller's source, not a hardcoded MIC. */
    @Test
    fun `plain capture keeps whichever source was requested`() {
        assertThat(AudioSourcePolicy.resolve(voiceRecognition, AndroidAudioEffects(), EchoCancellationMode.NONE))
            .isEqualTo(voiceRecognition)
        assertThat(AudioSourcePolicy.resolve(mic, AndroidAudioEffects(), EchoCancellationMode.NONE))
            .isEqualTo(mic)
    }

    /** Both effects at once is the corner the `||`-to-`xor` mutation lives in. */
    @Test
    fun `both android effects at once still force voice communication`() {
        val both = AndroidAudioEffects(noiseSuppressor = true, automaticGainControl = true)
        assertThat(AudioSourcePolicy.needsCommunicationMode(both, EchoCancellationMode.NONE)).isTrue()
        assertThat(AudioSourcePolicy.resolve(mic, both, EchoCancellationMode.NONE)).isEqualTo(voiceComm)
    }

    /**
     * WebRTC echo cancellation is ours, not the platform's, but it still takes the communication
     * source: AEC3 needs the capture and playback clocks the communication path shares.
     */
    @Test
    fun `webrtc echo cancellation forces voice communication`() {
        val none = AndroidAudioEffects()
        assertThat(AudioSourcePolicy.resolve(mic, none, EchoCancellationMode.WEBRTC)).isEqualTo(voiceComm)
        assertThat(AudioSourcePolicy.resolve(mic, none, EchoCancellationMode.NONE)).isEqualTo(mic)
    }
}
