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
 * rules that `WebRtcApmApi` is task 3's, in `se.lublin.humla.audio.native`, and it takes the six
 * parameters flat. This type exists so the factory's decision travels as one named value that a
 * test can compare, instead of as six positional booleans nobody can read at the call site.
 */
data class WebRtcApmConfig(
    val echoCancellation: Boolean,
    val noiseSuppression: Boolean,
    /** 0 low, 1 moderate, 2 high, 3 very high. Out-of-range values are clamped by the bridge. */
    val noiseSuppressionLevel: Int = DEFAULT_NOISE_SUPPRESSION_LEVEL,
    val gainControl: Boolean,
    val highPass: Boolean = true,
) {
    companion object {
        const val DEFAULT_NOISE_SUPPRESSION_LEVEL = 2

        /**
         * What `CapturePreprocessorFactory` builds the APM with when echo cancellation is set to
         * WEBRTC -- which is the only way the APM enters a chain, because the noise-suppression
         * setting offers None, Speex and RNNoise and never this.
         *
         * It follows spec B2 to the letter ("`WebRtcApm` (NS + AEC3 + AGC2 + high-pass, VAD from
         * level)"), and two consequences of that come with it. **Both are open questions for the
         * settings tasks rather than defects of this stage, and neither is visible in the audio
         * without measuring**:
         *
         * - **The noise suppressor runs twice** when the user also picked Speex or RNNoise, and it
         *   runs *at all* when the user picked None. Two cascaded suppressors over-cut speech, and
         *   "noise suppression: None" that still suppresses noise is a setting that does not mean
         *   what it says. The one-line alternative is to derive `noiseSuppression` from the noise
         *   mode here; it is not taken unasked because spec B2 names the APM's own NS explicitly.
         * - **The gain control runs ahead of the external suppressor.** AGC2 sits inside the APM,
         *   after AEC3's filter, so it does not disturb the canceller (that is the whole reason for
         *   the chain order -- see `ChainedPreprocessor`). But RNNoise was trained on speech plus
         *   additive noise at natural levels, and what reaches it is now a signal whose level is
         *   being chased frame by frame. Measure before shipping both on.
         *
         * AGC2 is also the only automatic gain control this project has: speex's is dead code in a
         * fixed-point build (spec §4.1), so turning this off removes gain control rather than
         * moving it somewhere else.
         */
        val FOR_ECHO_CANCELLATION = WebRtcApmConfig(
            echoCancellation = true,
            noiseSuppression = true,
            gainControl = true,
        )
    }
}

/**
 * Maps the APM's output level in dBFS onto a "voice probability": -50 dBFS and below is 0, -20 dBFS
 * and above is 1, linear in between.
 *
 * **It is a loudness threshold wearing a voice probability's type.** Spec §4.1 wants that written
 * down rather than hidden: with noise suppression NONE and echo cancellation WEBRTC this is the
 * only opinion in the chain, so task 7 receives a number that looks like RNNoise's and is not one.
 * A threshold tuned against a speech model does not transfer, and the two do not even fail the
 * same way -- a quiet talker in a quiet room is speech to RNNoise and silence to this.
 */
object LevelToProbability {
    /** At or below this level the stage reports no voice at all. */
    const val SILENCE_DBFS = -50f

    /** At or above this level the stage reports full confidence. */
    const val FULL_DBFS = -20f

    fun fromDbfs(dbfs: Float): Float =
        ((dbfs - SILENCE_DBFS) / (FULL_DBFS - SILENCE_DBFS)).coerceIn(0f, 1f)
}

/**
 * The WebRTC APM as one capture stage (spec B3): AEC3, noise suppression, AGC2 and a high-pass
 * filter on the near-end path, and the far-end signal on [analyzeReverseStream].
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
 * allocates nothing at all.
 *
 * `humla_apm_set_stream_delay_ms` is deliberately not bridged, so there is no entry point here for
 * it: AEC3 estimates the delay itself and feeding it the true delay moved the residual echo by
 * less than 0.02 dB (task 2).
 */
class WebRtcApmPreprocessor(
    private val api: WebRtcApmApi,
    config: WebRtcApmConfig,
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
) : SingleHandleStage(
    api.create(
        sampleRate,
        config.echoCancellation,
        config.noiseSuppression,
        config.noiseSuppressionLevel,
        config.gainControl,
        config.highPass,
    ),
    "the webrtc audio processing module at $sampleRate Hz",
),
    FarEndSink {

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
     * stream returns nothing to its caller, so a reference frame the APM would not take -- a
     * chunker built for the wrong frame size is how that happens -- costs about 21 dB of echo
     * cancellation without one call anywhere returning an error. It stays at 0 for a released
     * stage, because a released stage has nothing to report; it is simply gone.
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
    }
}
