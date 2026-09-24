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
 * Everything the audio pipeline is configured with. Immutable; [AudioController] rebuilds the
 * pipeline when a new value differs, so structural equality over every field matters.
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
    /** Whether WebRTC's AEC3 runs; derived by `HumlaService` from the routed device category. */
    val echoCancellation: Boolean = false,
    /**
     * The `AudioDeviceInfo` type of the device [AudioRouter] routes voice to, or null while the
     * route is the platform's own. A type rather than a flag: playback must follow any routed
     * device, and SCO needs to be told apart.
     */
    val routedDeviceType: Int? = null,
    val noiseSuppression: String = "none",
    /** How deep the Speex denoiser may cut, in dB. One of the three supported steps. */
    val speexNoiseSuppressDb: Int = -25,
    val vadMode: String = "amplitude",
    val vadStart: Float = 0.6f,
    val vadStop: Float = 0.3f,
    val vadHoldMs: Int = 250,
    val androidNoiseSuppressor: Boolean = false,
    val androidAgc: Boolean = false,
) {
    /** Half duplex only applies to push-to-talk, as per this config's transmit mode. */
    val halfDuplex: Boolean get() = halfDuplexRequested && transmitMode == Constants.TRANSMIT_PUSH_TO_TALK

    /**
     * The stream the playback track is opened on: [audioStream] while nothing is routed, the
     * voice-call stream otherwise, because a media-stream track does not follow the communication
     * device.
     */
    val playbackStream: Int
        get() = if (routedDeviceType != null) AudioManager.STREAM_VOICE_CALL else audioStream
}
