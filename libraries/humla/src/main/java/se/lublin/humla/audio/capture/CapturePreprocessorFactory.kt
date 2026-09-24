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

/**
 * One assembled capture chain: the stage the capture thread runs, and the far-end entry point the
 * playback thread feeds (present only when the WebRTC canceller is in the chain).
 *
 * On a mode switch, publish the whole [CaptureChain] (via `@Volatile` or a shared lock) before
 * releasing the old one; publishing the halves separately would feed the reference signal to the
 * wrong canceller. A released stage returns null like a stage without an opinion, so whoever swaps
 * chains must report failures; the per-stage `rejected*Frames` counters are available for that.
 *
 * @param farEndSink must be fed frames of exactly [farEndFrameSize] samples (use
 *   [FarEndFrameChunker]); others are refused and counted in
 *   `WebRtcApmPreprocessor.rejectedFarEndFrames`.
 * @param farEndFrameSize the length [farEndSink] demands, as reported by the APM (480 at 48 kHz, 0
 *   without a sink). Oversized frames are silently truncated, so never size the chunker from a
 *   constant.
 */
class CaptureChain(
    val preprocessor: CapturePreprocessor,
    val farEndSink: FarEndSink?,
    val farEndFrameSize: Int = 0,
)

/**
 * Builds the capture chain: the WebRTC APM first when echo cancellation is WEBRTC, then Speex or
 * RNNoise; the probability is the last non-null one.
 *
 * The APIs are passed as factories because touching the native objects runs `System.loadLibrary`;
 * [tryStage] turns a missing `.so` into a skipped stage and a log line instead of breaking the
 * pipeline. A disabled mode yields [NoopPreprocessor] itself, which holds no native state or lock.
 */
class CapturePreprocessorFactory(
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
            // AEC first: anything time-varying in front keeps it from converging. See ChainedPreprocessor.
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
            // Nobody else holds the stages built so far; release them (idempotent) before rethrowing.
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
     * Builds one stage, or logs why there is none and returns null.
     *
     * Catches [LinkageError] (a missing `.so` surfaces as `ExceptionInInitializerError`, then
     * `NoClassDefFoundError`, neither an `Exception`) and [IllegalStateException] (library loaded but
     * the handle couldn't be allocated). Anything else is a programmer error and propagates; [create]
     * releases already-built stages in that case.
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

    private companion object {
        const val WEBRTC_APM = "WebRTC APM"
        const val SPEEX = "Speex noise suppression"
        const val RNNOISE = "RNNoise noise suppression"
    }
}
