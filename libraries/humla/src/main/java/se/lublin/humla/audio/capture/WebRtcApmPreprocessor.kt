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

import se.lublin.humla.audio.native.WebRtcApmApi

/**
 * How the APM is configured for one chain.
 *
 * This is a capture-side value object, not a second declaration of the native interface: spec §4.1
 * rules that `WebRtcApmApi` is task 3's, in `se.lublin.humla.audio.native`, and it takes its six
 * parameters flat. This type exists so the factory's decision travels as one named value that a
 * test can compare, instead of as positional booleans nobody can read at the call site.
 *
 * It holds **four** flags where the bridge takes six parameters. The sample rate is the stage's,
 * not the chain's; and the noise-suppression *level* is deliberately absent -- see
 * [WebRtcApmPreprocessor.UNUSED_NOISE_SUPPRESSION_LEVEL].
 */
data class WebRtcApmConfig(
    val echoCancellation: Boolean,
    val noiseSuppression: Boolean,
    val gainControl: Boolean,
    val highPass: Boolean = true,
) {
    companion object {
        /**
         * What `CapturePreprocessorFactory` builds the APM with when echo cancellation is set to
         * WEBRTC -- which is the only way the APM enters a chain, because the noise-suppression
         * setting offers None, Speex and RNNoise and never this.
         *
         * **AEC3, AGC2 and the high-pass are on, and the APM's own noise suppression is off**
         * (spec §4.1, decided). B2 used to read "NS + AEC3 + AGC2 + high-pass" and this stage
         * implemented that literally; the second half of the consequence settled it. With the user
         * on Speex or RNNoise, two noise suppressors ran cascaded -- RNNoise's model is speech plus
         * additive noise, and pre-suppressed audio is not the input it was trained on -- and with
         * the user on **None** the APM suppressed noise anyway, which is a switch that does not do
         * what it says. This project has spent its length removing exactly those.
         *
         * Two things follow, and neither is cosmetic:
         *
         * - **AGC2 stays on, and it is the only *automatic* gain control inside the capture chain,
         *   today.** Speex's is dead code in a fixed-point build (spec §4.1). Both qualifiers
         *   carry weight: `AudioHandler.java:454-458` applies `mAmplitudeBoost` on the capture path
         *   right now, and spec B6 requires Android's `AutomaticGainControl` as a settings toggle,
         *   so "the only gain control left in the project" would be false in two directions.
         * - **The VAD window moves.** `humla_apm.cpp:88-91` measures `last_level_dbfs` on the
         *   **processed** frame, i.e. after NS and AGC2. With NS off every non-speech frame
         *   measures louder, and with noise suppression NONE and echo cancellation WEBRTC
         *   [LevelToProbability] is the only opinion in the chain -- which is what task 7 gates
         *   transmission on. Re-checking that -50/-20 dBFS window against the new levels is task
         *   7's, and it is in the ledger so it is not met as a surprise.
         *
         * The echo canceller loses nothing by it: `humla_apm.cpp:59-71` configures
         * `echo_canceller` and `noise_suppression` as **separate submodules**, and the
         * residual-echo suppressor sits inside EchoCanceller3.
         *
         * Each of the four flags going the other way turns
         * `CapturePreprocessorFactoryTest.the apm is built for echo cancellation at 48 kHz` red,
         * because that test writes the value out instead of comparing against this constant.
         * Measured: the `noiseSuppression = true` this replaced does exactly that.
         */
        val FOR_ECHO_CANCELLATION = WebRtcApmConfig(
            echoCancellation = true,
            noiseSuppression = false,
            gainControl = true,
        )
    }
}

/**
 * Maps the APM's output level in dBFS onto a "voice probability": [SILENCE_DBFS] and below is 0,
 * [FULL_DBFS] and above is 1, linear in between.
 *
 * **It is a loudness threshold wearing a voice probability's type.** Spec §4.1 wants that written
 * down rather than hidden: with noise suppression NONE and echo cancellation WEBRTC this is the
 * only opinion in the chain, so task 7 receives a number that looks like RNNoise's and is not one.
 * A threshold tuned against a speech model does not transfer, and the two do not even fail the
 * same way -- a quiet talker in a quiet room is speech to RNNoise and silence to this.
 *
 * **The window is set against what this chain measures, not against a round number.** The APM
 * reads `last_level_dbfs` on the *processed* frame (`humla_apm.cpp:88-91`) with its own noise
 * suppressor off (spec §4.1), so AGC2's noise cap binds and the non-speech floor stops tracking
 * the input. Measured on the host against the real APM, two runs, three non-speech characters:
 * the floor spans -43.55 to -47.80 dBFS with a **median about -45**, flat against the *input
 * level* inside one character. That is the axis, and -45 is a measured median with spread -- it
 * is **not** webrtc's `max_output_noise_level_dbfs`, which is -50.
 *
 * `fromDbfs` is a ratio over the window **width**, so the two edges are not independent: raising
 * the bottom alone rescales the whole curve and *tightens* the top rather than leaving it where
 * it was. [FULL_DBFS] is therefore chosen to hold the start contract fixed, not left at its old
 * value -- spec §4.1 carries the arithmetic and
 * `VoiceActivityDetectorTest.the probability defaults sit at these dBFS levels on the apm window`
 * says both readings out loud.
 */
object LevelToProbability {
    /**
     * At or below this level the stage reports no voice at all: the measured non-speech floor of
     * this chain. Below it, the default stop threshold was unreachable -- a live defect.
     */
    const val SILENCE_DBFS = -45f

    /**
     * At or above this level the stage reports full confidence. Not calibratable without a real
     * talker (spec §4.1, QA item, B task 13), so it is set to the value that changes the start
     * contract least: with the bottom on the floor, 0.6 still means **13.0 dB above the floor**,
     * exactly what the shipping -50/-20 window demanded. The stop moves from 4.0 to 6.5 dB, which
     * is forced -- one free parameter, two contracts.
     */
    const val FULL_DBFS = -23.3f

    fun fromDbfs(dbfs: Float): Float =
        ((dbfs - SILENCE_DBFS) / (FULL_DBFS - SILENCE_DBFS)).coerceIn(0f, 1f)
}

/**
 * The WebRTC APM as one capture stage (spec B3): AEC3, AGC2 and a high-pass filter on the near-end
 * path, and the far-end signal on [analyzeReverseStream]. Its **own noise suppressor is off** in
 * the one configuration this project builds -- see [WebRtcApmConfig.FOR_ECHO_CANCELLATION], which
 * carries the decision and its two effects.
 *
 * ### Two audio threads, one lock
 *
 * This is the stage [SingleHandleStage] was built for. `processCapture` runs on the capture thread
 * and `processRender` on the playback thread, and the control thread may free the handle from a
 * third. The base class's one lock covers all three, [analyzeReverseStream] included -- which is
 * why this class declares [FarEndSink] in its supertype list and overrides [onFarEndFrame], and
 * why it writes no `override fun analyzeReverseStream` of its own. That line used to be the single
 * unlocked route from a subclass into the native layer, and it no longer exists to be written.
 *
 * ### The two streams have to be fed in a fixed relation
 *
 * For each 10 ms tick the far-end frame goes in first, then the near-end frame that will contain
 * its echo. Getting that wrong is silent: every call still returns 0 and about 21 dB of echo
 * cancellation is simply gone (task 2, `tests/test_apm.c`). AEC3's delay estimator absorbs one
 * frame of slack and no more. `FarEndFrameChunker` is what turns the playback thread's arbitrary
 * mix buffers into those frames.
 *
 * ### What the frame path costs
 *
 * One `java.lang.Float` box per frame for the probability, as for [RnnoisePreprocessor]; spec §4.1
 * decided to pay it and `CaptureThreadAllocationTest` measures it cold and hot. The far-end path
 * measures **below 8.0 B per call** -- half the smallest object the JVM can allocate, which is
 * what the instrument can separate from zero; `CaptureThreadAllocationTest.HALF_AN_OBJECT` states
 * why "nothing at all" is not a claim a heap-delta measurement can make.
 *
 * `humla_apm_set_stream_delay_ms` is deliberately not bridged, so there is no entry point here for
 * it: AEC3 estimates the delay itself and feeding it the true delay moved the residual echo by
 * less than 0.02 dB (task 2).
 */
class WebRtcApmPreprocessor private constructor(
    private val api: WebRtcApmApi,
    handle: Long,
    sampleRate: Int,
) : SingleHandleStage(handle, "the webrtc audio processing module at $sampleRate Hz"),
    FarEndSink {

    constructor(
        api: WebRtcApmApi,
        config: WebRtcApmConfig,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
    ) : this(
        api,
        api.create(
            sampleRate,
            config.echoCancellation,
            config.noiseSuppression,
            UNUSED_NOISE_SUPPRESSION_LEVEL,
            config.gainControl,
            config.highPass,
        ),
        sampleRate,
    )

    /**
     * How many samples one far-end frame has to hold, **asked of the APM** rather than computed
     * from the rate this stage was asked for. `FarEndFrameChunker` is built with this number.
     *
     * The direction that needs it is the one with no observable: `jni_webrtc_apm.cpp:55` refuses a
     * frame *shorter* than the APM's -- that is [rejectedFarEndFrames] -- but accepts a longer one
     * and silently drops its tail, so a chunker built for 960 samples against a 480-sample APM
     * loses about 21 dB of echo cancellation with every counter in this class still reading 0.
     * A constant at the wiring site is exactly how that happens; `WebRtcApmApi.frameSize` is the
     * number the native side will really read, and this is its production caller.
     *
     * Read once, while the handle is still this constructor's: it cannot change for the life of
     * the handle, and a released stage still answers what its chunker was sized for rather than
     * the 0 the bridge would report.
     */
    val farEndFrameSize: Int = api.frameSize(handle)

    /**
     * Near-end frames the APM refused, i.e. any non-zero `webrtc::AudioProcessing::Error` -- -8 for
     * a frame shorter than 10 ms, -5 for a failure inside the JVM. Same role as
     * [SpeexPreprocessor.rejectedFrames]: the observable that separates "the APM refused this
     * frame" from "this stage has no opinion", for whoever owns `captureState`.
     */
    @Volatile
    var rejectedFrames: Int = 0
        private set

    /**
     * Far-end frames the APM refused. This is the counter that has no alternative: the reverse
     * stream returns nothing to its caller, so a reference frame the APM would not take costs
     * about 21 dB of ERLE without one call anywhere returning an error.
     *
     * **It only sees frames that are too short, and that is one of the two directions a chunker
     * can be wrong in.** `jni_webrtc_apm.cpp:55` refuses on `GetArrayLength(frame) <
     * humla_apm_frame_size(apm)`; a frame that is too *long* is accepted, and
     * `humla_apm.cpp:101` reads exactly `num_frames()` samples out of it and drops the tail
     * silently. So a chunker built for 960 samples against a 480-sample APM is precisely the
     * lossy case this counter is supposed to expose, and it stays at 0 throughout. The visible
     * half is the undersized chunker; the oversized one has no observable at this layer at all.
     *
     * It also stays at 0 for a released stage, because a released stage has nothing to report; it
     * is simply gone.
     */
    @Volatile
    var rejectedFarEndFrames: Int = 0
        private set

    override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? {
        if (api.processCapture(handle, frame) != 0) {
            rejectedFrames++
            // Not the level of the previous frame, and not the -100 dBFS a released handle
            // answers: both are this stage stating an opinion about a frame the APM never
            // processed, and task 7 gates transmission on that opinion.
            return null
        }
        return LevelToProbability.fromDbfs(api.lastCaptureLevelDbfs(handle))
    }

    override fun onFarEndFrame(handle: Long, frame: ShortArray) {
        if (api.processRender(handle, frame) != 0) rejectedFarEndFrames++
    }

    override fun onReleaseHandle(handle: Long) = api.destroy(handle)

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48000

        /**
         * `humla_apm_create` takes a noise-suppression level whatever the suppressor's state, so
         * *something* has to be passed. It is deliberately **not** a field of [WebRtcApmConfig]:
         * the APM's own suppressor is off for good (spec §4.1), and `humla_apm.cpp:62-70` clamps
         * this value into `NoiseSuppression::Level` and hands it to a submodule `ApplyConfig`
         * then leaves unbuilt. A config field that reaches webrtc and changes nothing is the same
         * lying switch the decision above removed, one layer down.
         *
         * **Unpinnable, and measured rather than asserted** (spec 4.05: check that a mutation
         * changes what the code *does* before recording a survivor). The one mutation that would
         * distinguish it is `0` -> `3`; it survives the whole module, and it survives because it
         * is a no-op. Whoever turns the APM's suppressor back on puts the level back in the
         * config in the same edit, with a test that reads it.
         */
        private const val UNUSED_NOISE_SUPPRESSION_LEVEL = 0
    }
}
