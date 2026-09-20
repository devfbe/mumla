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
 * Assembles the capture chain for the Java `AudioHandler`, which is the only caller.
 *
 * It exists because that caller cannot be tested: its constructor opens an `AudioRecord` and its
 * encoders load native libraries, so everything decided inside it is decided in the dark. Here the
 * two things that have to be true -- **which stages actually run**, and **that a chain that cannot
 * be built leaves capture working** -- are decided in one function with injectable seams, and
 * `CaptureWiringTest` reads both back.
 *
 * What it deliberately does not do: nothing about echo cancellation beyond refusing to configure
 * it. AEC3 needs the far-end reference from `AudioOutput`, and an APM that never sees one measured
 * **-0.62 dB of residual echo against -22.32 dB wired correctly** (task 2). Half of it is worse
 * than none: it costs a stage on the audio thread and cancels nothing.
 */
object CaptureWiring {
    private const val TAG = "CaptureWiring"

    /**
     * @param inputSampleRate `AudioInput.sampleRate`; a resampler is inserted only when it differs
     *   from [AudioHandler.SAMPLE_RATE], which is also the condition under which the old
     *   `ResamplingEncoder` used to be wrapped around the encoder. Exactly one of the two may
     *   exist, and since task 9 it is this one.
     * @param noise the suppressor to run. `NONE` is a chain with no stage at all, not a stage with
     *   neutral settings.
     * @param logger where a stage that could not be built is reported, in the user's chat log.
     */
    @JvmStatic
    @JvmOverloads
    fun capturePipeline(
        inputSampleRate: Int,
        inputMode: IInputMode,
        amplitudeBoost: Float,
        noise: NoiseSuppressionMode,
        logger: HumlaLogger? = null,
        factory: CapturePreprocessorFactory? = null,
        newResampler: (Int, Int) -> Resampler = { from, to -> SpeexResampler(from, to) },
    ): CapturePipeline {
        val log: (String) -> Unit = { message ->
            Log.w(TAG, message)
            logger?.logWarning(message)
        }
        val chain = (factory ?: CapturePreprocessorFactory(log = log)).create(noise, EchoCancellationMode.NONE)
        // The fallback the whole factory exists for, read back rather than assumed: a missing .so
        // or a native allocation failure leaves NoopPreprocessor, capture keeps running, and the
        // user is told instead of wondering why the noise is still there.
        if (noise != NoiseSuppressionMode.NONE && chain.preprocessor === NoopPreprocessor) {
            log("noise suppression (${noise.preferenceValue}) is not running on this device")
        }
        val resampler =
            if (inputSampleRate == AudioHandler.SAMPLE_RATE) null
            else newResampler(inputSampleRate, AudioHandler.SAMPLE_RATE)
        return CapturePipeline(
            resampler,
            chain.preprocessor,
            inputMode,
            amplitudeBoost,
            AudioHandler.FRAME_SIZE,
            log,
        )
    }
}
