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

package se.lublin.humla.audio

import android.media.AudioManager
import android.media.MediaRecorder
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.RnnoisePreprocessor
import se.lublin.humla.audio.capture.SpeexPreprocessor

/** The user's settings the audio pipeline is built from, handed through unchanged. */
public data class PipelineSettings(
    val audioStream: Int = AudioManager.STREAM_MUSIC,
    val audioSource: Int = MediaRecorder.AudioSource.MIC,
    val inputSampleRate: Int = 48_000,
    /** Target encoder bitrate in bps. */
    val bitrate: Int = 40_000,
    val framesPerPacket: Int = 2,
    val amplitudeBoost: Float = 1.0f,
    val noiseSuppression: NoiseSuppressionMode = NoiseSuppressionMode.NONE,
    /** One of [SPEEX_NOISE_SUPPRESS_DB]. */
    val speexNoiseSuppressDb: Int = SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB,
    /**
     * How far RNNoise may pull a frame down, dB, at least 0; [Float.POSITIVE_INFINITY] for no limit.
     * Only read with [NoiseSuppressionMode.RNNOISE].
     */
    val rnnoiseAttenuationLimitDb: Float = DEFAULT_RNNOISE_ATTENUATION_LIMIT_DB,
    val androidEffects: AndroidAudioEffects = AndroidAudioEffects(),
) {
    init {
        require(rnnoiseAttenuationLimitDb >= 0f) { "attenuation limit must be at least 0 dB" }
    }

    public companion object {
        /** The maximum suppressions in dB Speex's denoiser is offered at. */
        public val SPEEX_NOISE_SUPPRESS_DB: List<Int> get() = SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB

        /** RNNoise's attenuation limit when the user has not chosen one. */
        public const val DEFAULT_RNNOISE_ATTENUATION_LIMIT_DB: Float = RnnoisePreprocessor.ATTENUATION_LIMIT_DB
    }
}

/**
 * Everything a pipeline is built from: [settings] plus what the transmit mode and the route decide,
 * see [AudioSettings.toAudioConfig]. [AudioController] rebuilds the pipeline when a new value
 * differs, so structural equality over every field matters.
 */
internal data class AudioConfig(
    val settings: PipelineSettings = PipelineSettings(),
    /** Whether outgoing audio mutes playback; only ever true in push-to-talk. */
    val halfDuplex: Boolean = false,
    /**
     * The `AudioDeviceInfo` type of the device [se.lublin.humla.audio.routing.AudioRouter] routes
     * voice to, or null while the route is the platform's own. A type rather than a flag: playback
     * must follow any routed device, and SCO needs to be told apart.
     */
    val routedDeviceType: Int? = null,
    val echoCancellation: EchoCancellationMode = EchoCancellationMode.NONE,
) {
    /**
     * The stream the playback track is opened on: the configured one while nothing is routed, the
     * voice-call stream otherwise, because a media-stream track does not follow the communication
     * device.
     */
    val playbackStream: Int
        get() = if (routedDeviceType != null) AudioManager.STREAM_VOICE_CALL else settings.audioStream
}
