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
 * playback thread feeds -- present only when the WebRTC canceller is in the chain.
 *
 * ### Publishing this to the audio threads is the caller's job, and it is not a figure of speech
 *
 * A mode switch builds a new chain, **publishes it, and only then releases the old one**, in that
 * order. "Publishes" means a `@Volatile` field, or the same lock the capture thread reads it
 * under. With a plain `var` the capture thread keeps reading the old, just-released reference --
 * every stage in it returns null and touches no frame, so noise suppression and echo cancellation
 * are off from then on with no exception, no log line and no change in `captureState`. Today
 * neither `CapturePipeline` nor `AudioOutput` has such a field: both take their chain in the
 * constructor, so whoever adds the switch adds the publication point with it.
 *
 * Both halves have to move together. [preprocessor] and [farEndSink] are read by two different
 * audio threads, and a switch that publishes one before the other leaves the playback thread
 * feeding the reference signal to the old canceller while the capture thread is already on the new
 * one -- which is the "reference fed late" case task 2 measured at about 21 dB. Publish the
 * [CaptureChain], not its parts.
 *
 * ### There is no observable for "my chain is dead"
 *
 * A released stage and a stage that legitimately has no opinion both answer `null`, on purpose.
 * Spec §4 has the channel for the rest -- `AudioHandler.captureState` carries `Error(msg)` and
 * `HumlaService` forwards it to `onLogWarning` -- and it has to be reported by whoever performs
 * the swap, because it cannot be detected from inside a stage. The per-stage counters
 * (`SpeexPreprocessor.rejectedFrames`, `RnnoisePreprocessor.rejectedFrames`,
 * `WebRtcApmPreprocessor.rejectedFrames` and `rejectedFarEndFrames`) are what that owner reads.
 *
 * @param farEndSink must be fed frames of exactly `sampleRate / 100` samples -- 480 at 48 kHz.
 *   [FarEndFrameChunker] is what produces them; anything else is refused and counted in
 *   `WebRtcApmPreprocessor.rejectedFarEndFrames`.
 */
class CaptureChain(val preprocessor: CapturePreprocessor, val farEndSink: FarEndSink?)

/**
 * Builds the capture chain spec B2 describes: the WebRTC APM first when echo cancellation is set
 * to WEBRTC, then Speex or RNNoise, and the probability is the last non-null one.
 *
 * ### Why the apis arrive as functions
 *
 * `SpeexPreprocessNative`, `RnnoiseNative` and `WebRtcApmNative` all call `System.loadLibrary` in
 * their object initialiser, so *touching* any of them is what can fail. Passing them as
 * `() -> Api` keeps that touch inside [tryStage], where a missing `.so` becomes a skipped stage
 * and a log line instead of taking the whole pipeline -- and the codecs with it -- down (§0.3
 * decision 3). All three branches, not two: each of them has a load-failure test and a
 * state-failure test in `CapturePreprocessorFactoryTest`.
 *
 * ### Off is off
 *
 * For a mode that is off the factory returns [NoopPreprocessor] **itself**, not a stage built with
 * neutral parameters. Spec §4.1 asks for the identity to be pinned rather than the frame contents:
 * a stage with neutral parameters leaves the frame alone too, but it still holds native state,
 * still takes a lock on every frame and still has to be released, and no assertion about the
 * samples can tell the two apart.
 */
class CapturePreprocessorFactory(
    private val speexApi: () -> SpeexPreprocessApi = { SpeexPreprocessNative },
    private val rnnoiseApi: () -> RnnoiseApi = { RnnoiseNative },
    private val apmApi: () -> WebRtcApmApi = { WebRtcApmNative },
    /**
     * Where a stage that could not be built is reported. The default drops it, which is right only
     * for a caller that has no log yet: the whole point of skipping a stage instead of failing is
     * that the user can be told their noise suppression is not running.
     */
    private val log: (String) -> Unit = {},
) {
    fun create(
        noise: NoiseSuppressionMode,
        echo: EchoCancellationMode,
        speexNoiseSuppressDb: Int = SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB,
    ): CaptureChain {
        val stages = mutableListOf<CapturePreprocessor>()
        var farEnd: FarEndSink? = null

        try {
            // First, always. AEC3 tracks a linear path from the reference to the microphone and
            // adapts it over seconds; a noise suppressor or a gain stage in front of it changes
            // that path from frame to frame for reasons the reference cannot explain, and an
            // unconverged canceller does not fail loudly -- it returns the frame nearly unchanged.
            // 21.7 dB of ERLE, measured in task 2: -22.3 dB residual converged against -0.6 dB
            // unconverged (`tests/test_apm.c`). ChainedPreprocessor's KDoc has the long version.
            if (echo == EchoCancellationMode.WEBRTC) {
                val apm = tryStage(WEBRTC_APM) {
                    WebRtcApmPreprocessor(apmApi(), WebRtcApmConfig.FOR_ECHO_CANCELLATION)
                }
                if (apm != null) {
                    stages += apm
                    // The stage itself, not an adapter around it: the playback thread and the
                    // capture thread have to meet the same object, because that object is what
                    // holds the one lock over the one handle (spec §4.1).
                    farEnd = apm
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
            // Whatever [tryStage] deliberately does not catch leaves this method, and until it
            // returns a [CaptureChain] nobody else holds the stages built so far. An escaping
            // throw would strand them: an `IllegalArgumentException` from the speex depth with
            // `echo = WEBRTC` leaks one `webrtc::AudioProcessing` with its AEC3 state per call,
            // unreachable and never freed. `release()` is idempotent and takes each stage's own
            // lock, so this is safe for a stage that never came up as well.
            for (stage in stages) stage.release()
            throw e
        }

        val preprocessor: CapturePreprocessor = when (stages.size) {
            0 -> NoopPreprocessor
            1 -> stages[0]
            else -> ChainedPreprocessor(stages)
        }
        return CaptureChain(preprocessor, farEnd)
    }

    /**
     * Builds one stage, or reports why there is none.
     *
     * [LinkageError] and not [UnsatisfiedLinkError]: `System.loadLibrary` runs in the Kotlin object
     * initialiser of `RnnoiseNative`/`WebRtcApmNative`, so a missing `.so` arrives as
     * `ExceptionInInitializerError` on the first touch and as `NoClassDefFoundError` on every later
     * one. Neither is an `UnsatisfiedLinkError` and neither is an `Exception`, so both would pass
     * straight through a `catch (e: Exception)`.
     *
     * [IllegalStateException] is the library that loaded but could not allocate -- that is what
     * [SingleHandleStage] throws for a handle of 0.
     *
     * Nothing wider. An `IllegalArgumentException`, for instance, means a caller handed this
     * factory a speex suppression depth that does not exist; that is a programmer error, not a
     * missing library, and swallowing it would turn a bug into a user whose noise suppression is
     * quietly off.
     *
     * **Narrow here means someone else has to clean up.** Everything this does not catch travels
     * out of [create], and the stages built before it are reachable from nowhere else at that
     * moment. [create] releases them on the way out; every future stage that can throw is covered
     * by that without a line of its own.
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
