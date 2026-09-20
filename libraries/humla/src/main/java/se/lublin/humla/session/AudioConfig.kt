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

@file:Suppress("DEPRECATION") // se.lublin.humla.Constants is deprecated; TRANSMIT_* has no successor yet.

package se.lublin.humla.session

import android.media.AudioManager
import android.media.MediaRecorder
import se.lublin.humla.Constants

/**
 * Everything the audio pipeline is configured with. Immutable; `HumlaService` replaces it on every
 * settings change and [AudioController] rebuilds the pipeline when the value differs - which is
 * what makes structural equality over every field load-bearing (AudioConfigTest pins it).
 *
 * The last eight fields carry the spec 4 EXTRAS_* values for stream B, which reads them in
 * [DefaultAudioHandlerFactory].
 */
data class AudioConfig(
    val audioStream: Int = AudioManager.STREAM_MUSIC,
    val audioSource: Int = MediaRecorder.AudioSource.MIC,
    val inputSampleRate: Int = 48_000,
    val targetBitrate: Int = 40_000,
    val targetFramesPerPacket: Int = 2,
    val amplitudeBoost: Float = 1.0f,
    val transmitMode: Int = Constants.TRANSMIT_VOICE_ACTIVITY,
    val halfDuplexRequested: Boolean = false,
    val preprocessorEnabled: Boolean = false,
    /**
     * Legacy `EXTRAS_ECHO_CANCELLATION_METHOD` value, passed to the existing
     * `AudioHandler.Builder.setEchoCancellationMethod`. Stream B must not read this one for the new
     * pipeline - read [echoCancellationMode]. Never null: `AudioHandler`'s constructor calls
     * `equals("system")` on it, where the builder's own Java default of null threw.
     */
    val legacyEchoCancellationMethod: String = "none",
    /** True while a Bluetooth SCO route is the active communication device. */
    val bluetoothActive: Boolean = false,
    val noiseSuppression: String = "none",
    /** Spec 4 `EXTRAS_ECHO_CANCELLATION` value ("none"/"android"/"webrtc"); the one stream B reads. */
    val echoCancellationMode: String = "none",
    val vadMode: String = "amplitude",
    val vadStart: Float = 0.6f,
    val vadStop: Float = 0.3f,
    val vadHoldMs: Int = 250,
    val androidNoiseSuppressor: Boolean = false,
    val androidAgc: Boolean = false,
) {
    /**
     * Half duplex only applies to push-to-talk (spec A7).
     *
     * The axis that matters is *which* transmit mode the rule reads: this config's, which is the
     * mode in force. The old `EXTRAS_HALF_DUPLEX` handling read `extras.getInt(EXTRAS_TRANSMIT_MODE)`
     * of the same bundle, which is 0 - voice activity - whenever that bundle does not also carry the
     * mode, so a settings write that changed only half duplex always resolved to false.
     *
     * Being a `get()` rather than a stored val is not part of that: the value is immutable and
     * `copy` re-derives, so no test can tell the two apart, and none claims to.
     */
    val halfDuplex: Boolean get() = halfDuplexRequested && transmitMode == Constants.TRANSMIT_PUSH_TO_TALK
}
