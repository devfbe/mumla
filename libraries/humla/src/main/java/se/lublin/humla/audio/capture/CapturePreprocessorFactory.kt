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

import se.lublin.humla.audio.native.RnnoiseApi
import se.lublin.humla.audio.native.RnnoiseNative
import se.lublin.humla.audio.native.SpeexPreprocessApi
import se.lublin.humla.audio.native.SpeexPreprocessNative
import se.lublin.humla.audio.native.WebRtcApmApi
import se.lublin.humla.audio.native.WebRtcApmNative

private const val WEBRTC_APM = "WebRTC APM"
private const val SPEEX = "Speex noise suppression"
private const val RNNOISE = "RNNoise noise suppression"

/**
 * One assembled capture chain: the stage the capture thread runs, and the far-end entry point the
 * playback thread feeds (only when the WebRTC canceller is in the chain). On a mode switch, publish
 * the whole chain before releasing the old one, so the reference never reaches the wrong canceller.
 *
 * @param farEndSink must be fed frames of exactly [farEndFrameSize] samples (use [FarEndFrameChunker]).
 * @param farEndFrameSize as reported by the APM, 0 without a sink.
 */
internal class CaptureChain(
    val preprocessor: CapturePreprocessor,
    val farEndSink: FarEndSink?,
    val farEndFrameSize: Int = 0,
)

/**
 * Builds the capture chain: the WebRTC APM first when echo cancellation is WEBRTC, then Speex or
 * RNNoise. AGC2 stays in the canceller's APM, in front of RNNoise: behind it, AGC2 lifted what
 * RNNoise leaves of babble by about 25 dB and of fan noise by 15-18 dB
 * (`RnnoiseAttenuationLimitDeviceTest`).
 * The APIs are factories because touching the native objects loads the library; a missing `.so`
 * becomes a skipped stage and a [log] line.
 */
internal class CapturePreprocessorFactory(
    private val speexApi: () -> SpeexPreprocessApi = { SpeexPreprocessNative },
    private val rnnoiseApi: () -> RnnoiseApi = { RnnoiseNative },
    private val apmApi: () -> WebRtcApmApi = { WebRtcApmNative },
    /** Receives a message for each stage that could not be built, so the user can be told. */
    private val log: (String) -> Unit = {},
) {
    fun create(
        noise: NoiseSuppressionMode,
        echo: EchoCancellationMode,
        speexNoiseSuppressDb: Int = SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB,
    ): CaptureChain {
        val stages = mutableListOf<CapturePreprocessor>()
        var farEnd: FarEndSink? = null
        var farEndFrameSize = 0

        try {
            // AEC first: anything time-varying in front keeps it from converging.
            if (echo == EchoCancellationMode.WEBRTC) {
                val apm = tryStage(WEBRTC_APM) {
                    WebRtcApmPreprocessor(apmApi(), WebRtcApmConfig.FOR_ECHO_CANCELLATION)
                }
                if (apm != null) {
                    stages += apm
                    // The stage itself, so both audio threads share its lock.
                    farEnd = apm
                    farEndFrameSize = apm.farEndFrameSize
                }
            }
            when (noise) {
                NoiseSuppressionMode.NONE -> Unit
                NoiseSuppressionMode.SPEEX ->
                    tryStage(SPEEX) { SpeexPreprocessor(speexApi(), noiseSuppressDb = speexNoiseSuppressDb) }
                        ?.let { stages += it }
                NoiseSuppressionMode.RNNOISE ->
                    tryStage(RNNOISE) { RnnoisePreprocessor(rnnoiseApi()) }?.let { stages += it }
            }
        } catch (e: Throwable) {
            for (stage in stages) stage.release()
            throw e
        }

        val preprocessor: CapturePreprocessor = when (stages.size) {
            0 -> NoopPreprocessor
            1 -> stages[0]
            else -> ChainedPreprocessor(stages)
        }
        return CaptureChain(preprocessor, farEnd, farEndFrameSize)
    }

    /**
     * Builds one stage, or logs why there is none and returns null. A missing `.so` surfaces as a
     * [LinkageError]; [IllegalStateException] means the handle couldn't be allocated.
     */
    private fun <T : CapturePreprocessor> tryStage(name: String, build: () -> T): T? = try {
        build()
    } catch (e: LinkageError) {
        log("$name is unavailable, continuing without it: $e")
        null
    } catch (e: IllegalStateException) {
        log("$name is unavailable, continuing without it: ${e.message}")
        null
    }
}
