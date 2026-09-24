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
 * The Speex denoiser as a capture stage: denoise on, configurable suppression depth. AGC is compiled
 * out in the `FIXED_POINT` build; the stage reports `GET_PROB` and ignores Speex's own VAD verdict.
 * The frame path allocates nothing.
 */
class SpeexPreprocessor(
    private val api: SpeexPreprocessApi,
    frameSize: Int = DEFAULT_FRAME_SIZE,
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
    /** Maximum suppression in dB, one of [SUPPORTED_NOISE_SUPPRESS_DB]; fixed for the stage's life. */
    val noiseSuppressDb: Int = DEFAULT_NOISE_SUPPRESS_DB,
) : SingleHandleStage(configuredState(api, frameSize, sampleRate, noiseSuppressDb), WHAT) {

    private val ctlValue = IntArray(1)

    /** Frames the bridge refused (too short); distinguishes "refused" from "no opinion". */
    @Volatile
    var rejectedFrames: Int = 0
        private set

    override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? {
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

        val SUPPORTED_NOISE_SUPPRESS_DB = listOf(-15, -25, -35)

        private const val WHAT = "speex preprocessor"

        /** Pre-boxed results (integer percent), so the frame path doesn't allocate. */
        private val PROBABILITIES: Array<Float?> = Array(101) { it / 100f }
    }
}

/** Creates and configures the state, or returns 0; validates before `init` so nothing leaks. */
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
    for ((request, setting) in arrayOf(
        SpeexPreprocessNative.SPEEX_PREPROCESS_SET_DENOISE to 1,
        SpeexPreprocessNative.SPEEX_PREPROCESS_SET_NOISE_SUPPRESS to noiseSuppressDb,
    )) {
        value[0] = setting
        api.ctlInt(state, request, value)
    }
    return state
}
