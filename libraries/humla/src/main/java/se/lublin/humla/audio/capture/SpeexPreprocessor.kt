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

import se.lublin.humla.audio.native.SpeexPreprocessApi
import se.lublin.humla.audio.native.SpeexPreprocessNative

/**
 * The Speex denoiser as one capture stage (spec B9): denoise on, Speex's own VAD threshold raised,
 * a configurable suppression depth, and **no AGC**.
 *
 * ### Which control calls this library actually answers
 *
 * `speex_preprocess_ctl` returns -1 for a request it does not implement, `PP(ctlInt)` returns -1
 * for a request its allow list does not carry, and the two are indistinguishable from here -- so
 * "the call is in the source" says nothing about whether it did anything. Each call below was
 * checked against the vendored `third_party/speexdsp/libspeexdsp/preprocess.c` and against
 * `jni_speexdsp.cpp`, and the ones that are missing are missing on purpose:
 *
 * | request                       | what this build does with it                                   |
 * |-------------------------------|----------------------------------------------------------------|
 * | `SET_DENOISE` (0)             | sets `st->denoise_enabled`, read on every frame (line 933).     |
 * | `SET_NOISE_SUPPRESS` (18)     | sets `st->noise_suppress`, used by `compute_gain_floor` (825).  |
 * | `GET_PROB` (45)               | `st->speech_prob`, always computed (992), scaled 0..100.        |
 * | `SET_VAD` (4)                 | answered, but see below.                                        |
 * | `SET_PROB_START` (14)         | answered, but see below.                                        |
 * | `SET_AGC` (2), `SET_AGC_TARGET` (46) | **-1.** Both sit under `#ifndef FIXED_POINT` (1057, 1193) and this build defines `FIXED_POINT` (CMakeLists.txt:108). `PreprocessingEncoder.kt:39,43` issues both and reads neither status. |
 * | `SET_DEREVERB` (8)            | **answered and inert.** It stores `st->dereverb_enabled`, which nothing in `preprocess.c` ever reads. |
 * | `SET_PROB_CONTINUE` (16)      | **-1.** Not on the bridge's allow list, so it never reaches speex at all. Hence the VAD's continue threshold stays at its default and cannot be set from here. |
 *
 * `SET_VAD` and `SET_PROB_START` are the spec B9 fix (`PreprocessingEncoder.kt:47` passes
 * `GET_PROB_START`, which reads the threshold instead of raising it) and they are issued here, but
 * be clear about what they buy: `st->vad_enabled`, `speech_prob_start` and `speech_prob_continue`
 * are read in exactly one place, the hysteresis at `preprocess.c:993-1002`, and that decides only
 * the **return value of `speex_preprocess_run`**. This stage reports `GET_PROB` instead, which is
 * computed either way, so the VAD's own decision is deliberately discarded -- task 7 applies its
 * own threshold with its own hysteresis, and a second one inside speex, whose continue half cannot
 * even be configured through the bridge, would only be a hidden second opinion. So the pair is
 * answered by the library and observably inert **for this stage**; they are kept because spec B9
 * asks for the corrected request and because a future consumer of the VAD flag would otherwise
 * inherit the library's 35 % default.
 *
 * ### The frame path
 *
 * Runs on the capture thread on every 10 ms frame and allocates nothing: the probability comes out
 * of [PROBABILITIES], which is pre-boxed, and the `int` in and out of `ctlInt` travels in
 * [ctlValue], which is allocated once and only ever touched with the base class's lock held.
 */
class SpeexPreprocessor(
    private val api: SpeexPreprocessApi,
    frameSize: Int = DEFAULT_FRAME_SIZE,
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
    /**
     * How deep the denoiser is allowed to cut, in dB. One of [SUPPORTED_NOISE_SUPPRESS_DB]; more
     * negative is stronger. Fixed for the life of the stage, because the handle it would have to
     * be pushed to belongs to [SingleHandleStage] and is reachable only from a callback -- a
     * change means a new chain, which is how every other audio setting in spec §4 is switched.
     */
    val noiseSuppressDb: Int = DEFAULT_NOISE_SUPPRESS_DB,
) : SingleHandleStage(configuredState(api, frameSize, sampleRate, noiseSuppressDb), WHAT) {

    /** The in/out argument of `ctlInt`, reused so the frame path allocates nothing. */
    private val ctlValue = IntArray(1)

    /**
     * Frames the bridge refused, i.e. frames shorter than the size this state was created with.
     *
     * A stage cannot report anything itself -- spec §4's channel is `AudioHandler.captureState`,
     * and only whoever built the chain holds it -- so this is the observable it leaves behind for
     * that owner, and it is the only thing that separates "speex refused the frame" from "this
     * stage has no opinion", both of which are `null`. Written only from [onCaptureFrame], i.e.
     * with the one lock held; `@Volatile` so a reader on another thread sees it.
     */
    @Volatile
    var rejectedFrames: Int = 0
        private set

    override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? {
        // -1 is reachable from a wrong-sized capture buffer, not only from a broken build: the
        // bridge refuses a frame shorter than the state's frame size rather than let speex write
        // past the array. Mapping it with `!= 0`, as a boolean-returning adapter has to, reports
        // the most confident possible "this is speech" for a frame speex never looked at. An
        // exception instead would be worse: it happens once per frame and it kills the capture
        // thread, which is the user going silent with no warning at all.
        if (api.run(handle, frame) < 0) {
            rejectedFrames++
            return null
        }
        ctlValue[0] = 0
        // A refused read leaves ctlValue[0] at 0, which is "certainly not speech" -- and task 7
        // gates transmission on this number, so reporting it would mute the user. No opinion is
        // the honest answer, and it lets the other stages in the chain speak.
        if (api.ctlInt(handle, SpeexPreprocessNative.SPEEX_PREPROCESS_GET_PROB, ctlValue) != 0) {
            return null
        }
        return PROBABILITIES[ctlValue[0].coerceIn(0, 100)]
    }

    override fun onReleaseHandle(handle: Long) = api.destroy(handle)

    companion object {
        const val DEFAULT_FRAME_SIZE = 480
        const val DEFAULT_SAMPLE_RATE = 48000
        const val DEFAULT_NOISE_SUPPRESS_DB = -25

        /** The three depths spec B9 exposes; the preference offers exactly these. */
        val SUPPORTED_NOISE_SUPPRESS_DB = listOf(-15, -25, -35)

        private const val WHAT = "speex preprocessor"

        /**
         * Every value [onCaptureFrame] can return, boxed once.
         *
         * `GET_PROB` answers an integer percent, so there are exactly 101 of them. Without this,
         * a stage that computes a probability boxes a `java.lang.Float` on every frame -- 16 B per
         * 10 ms per stage, which `CaptureThreadAllocationTest` deliberately does not cover because
         * the skeleton it measures never computes one. Declared `Array<Float?>`, not
         * `Array<Float>`: the latter would be unboxed on read and re-boxed for the nullable return
         * type, which is the allocation this exists to remove.
         */
        private val PROBABILITIES: Array<Float?> = Array(101) { it / 100f }
    }
}

/**
 * How confident speex has to be before its own VAD flips to speech, in percent.
 *
 * The library's default is 35 % (`SPEECH_PROB_START_DEFAULT`, preprocess.c:77) and the value here
 * is the one `PreprocessingEncoder.kt:47` has been trying to set since 2014 under the comment
 * "increase VAD difficulty". Its partner `SPEECH_PROB_CONTINUE_DEFAULT` (20 %) stays as it is:
 * `SET_PROB_CONTINUE` is not on the bridge's allow list, so it cannot be set from Kotlin at all.
 */
private const val PROB_START_PERCENT = 99

/**
 * Creates the state and puts it in the shape spec B9 asks for, returning 0 if speex could not
 * allocate one -- [SingleHandleStage] turns that into the exception.
 *
 * It is a file-private function rather than anything on the class because it runs as the argument
 * of the superclass constructor: that is the only point at which a subclass can both own the
 * handle briefly and hand it to the base without keeping a second copy of it.
 *
 * The level is validated **before** `init`, so an unsupported one cannot leak a state: the
 * constructor throws, there is no instance, and nothing can call [SpeexPreprocessor.release].
 */
private fun configuredState(
    api: SpeexPreprocessApi,
    frameSize: Int,
    sampleRate: Int,
    noiseSuppressDb: Int,
): Long {
    require(noiseSuppressDb in SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB) {
        "unsupported speex noise suppression $noiseSuppressDb dB, expected one of " +
            SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB
    }
    val state = api.init(frameSize, sampleRate)
    if (state == 0L) return 0L
    val value = IntArray(1)
    // Named through SpeexPreprocessNative's constants on purpose: those are what jni_speexdsp.cpp
    // documents its allow list as mirroring, so a request the bridge would refuse has no constant
    // to spell it with and does not compile. SPEEX_PREPROCESS_SET_PROB_CONTINUE is the one that
    // matters -- it has no constant, and it is the call that reads as the obvious partner below.
    for ((request, setting) in arrayOf(
        SpeexPreprocessNative.SPEEX_PREPROCESS_SET_DENOISE to 1,
        SpeexPreprocessNative.SPEEX_PREPROCESS_SET_VAD to 1,
        SpeexPreprocessNative.SPEEX_PREPROCESS_SET_PROB_START to PROB_START_PERCENT,
        SpeexPreprocessNative.SPEEX_PREPROCESS_SET_NOISE_SUPPRESS to noiseSuppressDb,
    )) {
        value[0] = setting
        api.ctlInt(state, request, value)
    }
    return state
}
