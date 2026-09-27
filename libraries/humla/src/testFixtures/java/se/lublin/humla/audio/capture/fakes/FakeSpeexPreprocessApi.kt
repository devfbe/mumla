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

package se.lublin.humla.audio.capture.fakes

import se.lublin.humla.audio.native.SpeexPreprocessApi
import se.lublin.humla.audio.native.SpeexPreprocessNative

private const val VOICE_PROBABILITY_PERCENT = 50

/**
 * A [SpeexPreprocessApi] that behaves like `jni_speexdsp.cpp`, including the `-1`s the bridge
 * produces on its own:
 * - [run] refuses a frame shorter than the state's frame size; [onRun] never sees it.
 * - [ctlInt] refuses any request outside [SpeexPreprocessorRequests.ALLOWED].
 * - requests in [refuse] also answer `-1`, as speex does for controls compiled out of this
 *   fixed-point build (`SET_AGC`, `SET_AGC_TARGET`). Kotlin cannot tell the two apart.
 */
internal class FakeSpeexPreprocessApi(
    /** What `SPEEX_PREPROCESS_GET_PROB` answers, in percent, unclamped on purpose. */
    var probability: Int = 0,
    private val onRun: (ShortArray) -> Unit = {},
) : SpeexPreprocessApi {
    /** Every write-direction ctl that got through, as (request, value). */
    val setCalls = mutableListOf<Pair<Int, Int>>()

    /** Every read-direction ctl that got through. */
    val getRequests = mutableListOf<Int>()

    /** Every request the stage issued, including refused ones. */
    val attemptedRequests = mutableListOf<Int>()

    /** Requests this build answers with -1, as the fixed-point AGC controls do. */
    val refuse = mutableSetOf<Int>()

    var runs = 0
        private set
    var destroyed = 0
        private set
    var createdWith: Pair<Int, Int>? = null
        private set
    var created = 0
        private set
    var failCreate = false

    private var frameSize = 0

    override fun init(frameSize: Int, sampleRate: Int): Long {
        createdWith = frameSize to sampleRate
        if (failCreate || frameSize <= 0) return 0L
        this.frameSize = frameSize
        created++
        return HANDLE
    }

    override fun run(state: Long, frame: ShortArray): Int {
        check(state == HANDLE) { "unknown state $state" }
        runs++
        if (frame.size < frameSize) return -1
        onRun(frame)
        return if (probability >= VOICE_PROBABILITY_PERCENT) 1 else 0
    }

    override fun ctlInt(state: Long, request: Int, value: IntArray): Int {
        check(state == HANDLE) { "unknown state $state" }
        check(value.size >= 1) { "ctlInt needs a one-element array" }
        attemptedRequests += request
        if (request !in SpeexPreprocessorRequests.ALLOWED || request in refuse) return -1
        if (request in SpeexPreprocessorRequests.READ_DIRECTION) {
            getRequests += request
            value[0] = if (request == SpeexPreprocessorRequests.GET_PROB) probability else 0
        } else {
            setCalls += request to value[0]
        }
        return 0
    }

    override fun destroy(state: Long) {
        check(state == HANDLE) { "unknown state $state" }
        destroyed++
    }

    companion object {
        const val HANDLE = 0x5EEDL
    }
}

/**
 * The speex request ids. The `const val`s are inlined, so this does not load
 * `SpeexPreprocessNative` (and its native library) on the JVM.
 *
 * [ALLOWED] mirrors `preprocessRequestAllowed` in `jni_speexdsp.cpp`; anything else is refused
 * with -1, which a stage cannot tell apart from an unimplemented request.
 */
internal object SpeexPreprocessorRequests {
    const val SET_DENOISE = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_DENOISE          // 0
    const val SET_AGC = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_AGC                  // 2
    const val SET_VAD = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_VAD                  // 4
    const val SET_DEREVERB = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_DEREVERB        // 8
    const val SET_PROB_START = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_PROB_START    // 14
    const val GET_PROB_START = SpeexPreprocessNative.SPEEX_PREPROCESS_GET_PROB_START    // 15
    const val SET_NOISE_SUPPRESS = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_NOISE_SUPPRESS // 18
    const val GET_PROB = SpeexPreprocessNative.SPEEX_PREPROCESS_GET_PROB                // 45
    const val SET_AGC_TARGET = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_AGC_TARGET    // 46

    val ALLOWED = setOf(
        SET_DENOISE, SET_AGC, SET_VAD, SET_DEREVERB, SET_PROB_START,
        GET_PROB_START, SET_NOISE_SUPPRESS, GET_PROB, SET_AGC_TARGET,
    )

    /** The two allowed requests that answer by writing `value[0]`; every other one is a write. */
    val READ_DIRECTION = setOf(GET_PROB_START, GET_PROB)
}
