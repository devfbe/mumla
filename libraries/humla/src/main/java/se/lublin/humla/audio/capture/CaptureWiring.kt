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
 * Echo cancellation is wired here too, and it is the one stage with **two** ends. The near end is
 * a stage in the capture chain like any other; the far end is [Wiring.farEnd], which `AudioOutput`
 * pushes every mixed buffer into on the playback thread. Both halves or neither: an APM that never
 * sees the reference measured **-0.62 dB of residual echo against -22.32 dB wired correctly**
 * (task 2), and it still costs a stage on the audio thread.
 */
object CaptureWiring {
    private const val TAG = "CaptureWiring"

    /**
     * What one call wires up: the chain the capture thread runs, and the far-end tap the playback
     * thread feeds.
     *
     * They are handed back together because they are one chain seen from two threads -- the
     * chunker's sink *is* the stage in [pipeline] -- and a caller that received them from two
     * calls could hold halves of two different chains. `CaptureChain`'s KDoc has the long version.
     *
     * @param farEnd null whenever the WebRTC canceller is not in the chain, which includes the
     *   case where it was asked for and could not be built. The playback path then has no tap at
     *   all rather than a tap that does nothing.
     */
    class Wiring(val pipeline: CapturePipeline, val farEnd: FarEndFrameChunker?)

    /**
     * @param inputSampleRate `AudioInput.sampleRate`; a resampler is inserted only when it differs
     *   from [AudioHandler.SAMPLE_RATE], which is also the condition under which the old
     *   `ResamplingEncoder` used to be wrapped around the encoder. Exactly one of the two may
     *   exist, and since task 9 it is this one.
     * @param noise the suppressor to run. `NONE` is a chain with no stage at all, not a stage with
     *   neutral settings.
     * @param echo which canceller runs. Only [EchoCancellationMode.WEBRTC] builds anything here.
     * @param speexNoiseSuppressDb how deep the Speex denoiser may cut (spec B9). It sits in front
     *   of [logger] so that `AudioHandler`'s Java call site reaches it through a generated
     *   `@JvmOverloads` overload instead of having to pass a resampler lambda.
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
        // The fallback the whole factory exists for, read back rather than assumed: a missing .so
        // or a native allocation failure leaves NoopPreprocessor, capture keeps running, and the
        // user is told instead of wondering why the noise is still there.
        if (noise != NoiseSuppressionMode.NONE && chain.preprocessor === NoopPreprocessor) {
            log("noise suppression (${noise.preferenceValue}) is not running on this device")
        }
        // The same read-back for the canceller, and it needs its own: the APM is one of two stages,
        // so a chain that kept the noise suppressor and lost the APM is not NoopPreprocessor and
        // the line above stays silent. A missing far-end sink is the observable -- without it the
        // playback thread has nothing to feed and the near-end half would cancel nothing anyway.
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
        // Sized from the chain, never from AudioHandler.FRAME_SIZE: a frame longer than the APM's
        // is accepted and silently truncated, so a constant here is a 21 dB loss that no counter
        // and no test above this line can see. CaptureChain.farEndFrameSize carries the APM's own
        // answer.
        val farEnd = chain.farEndSink?.let { FarEndFrameChunker(chain.farEndFrameSize, it) }
        return Wiring(pipeline, farEnd)
    }
}
