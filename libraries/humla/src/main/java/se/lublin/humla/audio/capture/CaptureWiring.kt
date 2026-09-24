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

import android.util.Log
import se.lublin.humla.audio.inputmode.IInputMode
import se.lublin.humla.protocol.AudioHandler
import se.lublin.humla.util.HumlaLogger

/**
 * Assembles the capture chain for `AudioHandler` (the only caller), with injectable seams so stage
 * selection and fallback behaviour are testable.
 *
 * Echo cancellation has two ends: the near-end stage in the capture chain and [Wiring.farEnd], fed
 * by `AudioOutput` on the playback thread. Both or neither: an APM without the reference cancels
 * nothing.
 */
object CaptureWiring {
    private const val TAG = "CaptureWiring"

    /**
     * The chain the capture thread runs and the far-end tap the playback thread feeds, returned
     * together because they are one chain (the chunker's sink is a stage in [pipeline]).
     *
     * @param farEnd null whenever the WebRTC canceller is not in the chain, including when it was
     *   requested but could not be built.
     */
    class Wiring(val pipeline: CapturePipeline, val farEnd: FarEndFrameChunker?)

    /**
     * @param inputSampleRate `AudioInput.sampleRate`; a resampler is inserted only when it differs
     *   from [AudioHandler.SAMPLE_RATE].
     * @param noise the suppressor to run; `NONE` means no stage at all.
     * @param echo which canceller runs; only [EchoCancellationMode.WEBRTC] builds anything.
     * @param speexNoiseSuppressDb how deep the Speex denoiser may cut. Placed before [logger] so the
     *   Java caller can use a `@JvmOverloads` overload.
     * @param logger where a stage that could not be built is reported, in the user's chat log.
     */
    @JvmStatic
    @JvmOverloads
    fun wire(
        inputSampleRate: Int,
        inputMode: IInputMode,
        amplitudeBoost: Float,
        noise: NoiseSuppressionMode,
        echo: EchoCancellationMode,
        speexNoiseSuppressDb: Int = SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB,
        logger: HumlaLogger? = null,
        factory: CapturePreprocessorFactory? = null,
        newResampler: (Int, Int) -> Resampler = { from, to -> SpeexResampler(from, to) },
    ): Wiring {
        val log: (String) -> Unit = { message ->
            Log.w(TAG, message)
            logger?.logWarning(message)
        }
        val chain = (factory ?: CapturePreprocessorFactory(log = log)).create(noise, echo, speexNoiseSuppressDb)
        // Tell the user when a requested suppressor couldn't be built (missing .so, allocation failure).
        if (noise != NoiseSuppressionMode.NONE && chain.preprocessor === NoopPreprocessor) {
            log("noise suppression (${noise.preferenceValue}) is not running on this device")
        }
        // Separate check: losing only the APM doesn't yield NoopPreprocessor; a missing sink shows it.
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
        // Sized from the APM, never a constant: oversized far-end frames are silently truncated.
        val farEnd = chain.farEndSink?.let { FarEndFrameChunker(chain.farEndFrameSize, it) }
        return Wiring(pipeline, farEnd)
    }
}
