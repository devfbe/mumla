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

import se.lublin.humla.audio.capture.CapturePipeline
import se.lublin.humla.audio.capture.CapturePreprocessorFactory
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.FarEndFrameChunker
import se.lublin.humla.audio.capture.IInputMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.NoopPreprocessor
import se.lublin.humla.audio.capture.Resampler
import se.lublin.humla.audio.capture.RnnoisePreprocessor
import se.lublin.humla.audio.capture.SpeexPreprocessor
import se.lublin.humla.audio.capture.SpeexResampler
import se.lublin.humla.util.HumlaLog
import se.lublin.humla.util.HumlaLogger

/**
 * Assembles the capture chain for `AudioHandler`. Echo cancellation has two ends, the near-end stage
 * and [Wiring.farEnd] fed by `AudioOutput` on the playback thread: both or neither.
 */
internal object CaptureWiring {
    private const val TAG = "CaptureWiring"

    /** @param farEnd null whenever the WebRTC canceller is not in the chain. */
    class Wiring(val pipeline: CapturePipeline, val farEnd: FarEndFrameChunker?)

    /**
     * @param inputSampleRate a resampler is inserted when it differs from [AudioHandler.SAMPLE_RATE].
     * @param logger receives a user-visible line for each stage that could not be built.
     */
    @Suppress("LongParameterList") // Every setting the chain is built from, and the test seams.
    fun wire(
        inputSampleRate: Int,
        inputMode: IInputMode,
        amplitudeBoost: Float,
        noise: NoiseSuppressionMode,
        echo: EchoCancellationMode,
        speexNoiseSuppressDb: Int = SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB,
        rnnoiseAttenuationLimitDb: Float = RnnoisePreprocessor.ATTENUATION_LIMIT_DB,
        logger: HumlaLogger? = null,
        factory: CapturePreprocessorFactory? = null,
        newResampler: (Int, Int) -> Resampler = { from, to -> SpeexResampler(from, to) },
    ): Wiring {
        val log: (String) -> Unit = { message ->
            HumlaLog.w(TAG, message)
            logger?.logWarning(message)
        }
        val chain = (factory ?: CapturePreprocessorFactory(log = log))
            .create(noise, echo, speexNoiseSuppressDb, rnnoiseAttenuationLimitDb)
        if (noise != NoiseSuppressionMode.NONE && chain.preprocessor === NoopPreprocessor) {
            log("noise suppression (${noise.preferenceValue}) is not running on this device")
        }
        if (echo == EchoCancellationMode.WEBRTC && chain.farEndSink == null) {
            log("echo cancellation (${echo.preferenceValue}) is not running on this device")
        }
        val resampler =
            if (inputSampleRate == AudioHandler.SAMPLE_RATE) null
            else newResampler(inputSampleRate, AudioHandler.SAMPLE_RATE)
        val pipeline = CapturePipeline(
            resampler,
            chain.preprocessor,
            inputMode,
            amplitudeBoost,
            AudioHandler.FRAME_SIZE,
            log,
        )
        val farEnd = chain.farEndSink?.let { FarEndFrameChunker(chain.farEndFrameSize, it) }
        return Wiring(pipeline, farEnd)
    }
}
