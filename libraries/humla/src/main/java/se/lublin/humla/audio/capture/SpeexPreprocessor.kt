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
 * The Speex denoiser as a capture stage: denoise on, configurable suppression depth, no AGC.
 *
 * Only `SET_DENOISE` and `SET_NOISE_SUPPRESS` are issued. AGC controls are compiled out in this
 * `FIXED_POINT` build, `SET_DEREVERB` is inert, and Speex's own VAD (`SET_VAD`/`SET_PROB_START`)
 * only affects `speex_preprocess_run`'s return value, which is ignored: the stage reports
 * `GET_PROB` directly and the gate applies its own threshold and hysteresis downstream.
 *
 * The frame path allocates nothing ([PROBABILITIES] is pre-boxed; [ctlValue] is reused under the
 * base class lock).
 */
class SpeexPreprocessor(
    private val api: SpeexPreprocessApi,
    frameSize: Int = DEFAULT_FRAME_SIZE,
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
    /**
     * Maximum suppression in dB, one of [SUPPORTED_NOISE_SUPPRESS_DB]; more negative is stronger.
     * Fixed for the life of the stage; changing it means building a new chain.
     */
    val noiseSuppressDb: Int = DEFAULT_NOISE_SUPPRESS_DB,
) : SingleHandleStage(configuredState(api, frameSize, sampleRate, noiseSuppressDb), WHAT) {

    /** The in/out argument of `ctlInt`, reused so the frame path allocates nothing. */
    private val ctlValue = IntArray(1)

    /**
     * Frames the bridge refused (shorter than the state's frame size). The only way to tell "refused"
     * from "no opinion", since both yield null. Written under the stage lock.
     */
    @Volatile
    var rejectedFrames: Int = 0
        private set

    override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? {
        // Refused frame (wrong size): report no opinion rather than treating -1 as "speech".
        if (api.run(handle, frame) < 0) {
            rejectedFrames++
            return null
        }
        ctlValue[0] = 0
        // A failed read would leave 0 ("not speech") and mute the user; report no opinion instead.
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

        /** The depths the preference offers. */
        val SUPPORTED_NOISE_SUPPRESS_DB = listOf(-15, -25, -35)

        private const val WHAT = "speex preprocessor"

        /**
         * Every value [onCaptureFrame] can return (integer percent), boxed once to avoid a per-frame
         * allocation. `Float?` rather than `Float` so reads aren't unboxed and re-boxed.
         */
        private val PROBABILITIES: Array<Float?> = Array(101) { it / 100f }
    }
}

/**
 * Creates and configures the state, returning 0 if speex could not allocate one ([SingleHandleStage]
 * turns that into the exception). Runs as the superclass constructor argument so no second copy of
 * the handle is kept. The level is validated before `init`, so a bad level can't leak a state.
 */
private fun configuredState(
    api: SpeexPreprocessApi,
    frameSize: Int,
    sampleRate: Int,
    noiseSuppressDb: Int,
): Long {
    // Programmer-error guard: speex stores -ABS(value), so a sign slip would be silently ignored.
    require(noiseSuppressDb in SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB) {
        "unsupported speex noise suppression $noiseSuppressDb dB, expected one of " +
            SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB
    }
    val state = api.init(frameSize, sampleRate)
    if (state == 0L) return 0L
    val value = IntArray(1)
    // Only requests on the bridge's allow list have constants in SpeexPreprocessNative. SET_VAD and
    // SET_PROB_START are deliberately not issued (see class KDoc).
    for ((request, setting) in arrayOf(
        SpeexPreprocessNative.SPEEX_PREPROCESS_SET_DENOISE to 1,
        SpeexPreprocessNative.SPEEX_PREPROCESS_SET_NOISE_SUPPRESS to noiseSuppressDb,
    )) {
        value[0] = setting
        api.ctlInt(state, request, value)
    }
    return state
}
