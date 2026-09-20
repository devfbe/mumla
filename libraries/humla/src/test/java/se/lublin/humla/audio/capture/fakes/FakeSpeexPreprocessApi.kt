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

/**
 * A [SpeexPreprocessApi] that behaves like `jni_speexdsp.cpp` does, not like libspeexdsp's header
 * reads.
 *
 * Three behaviours are modelled rather than knobbed, because every one of them is a `-1` the
 * bridge produces on its own and a stage can get wrong without any test noticing:
 *
 * - [run] refuses a frame shorter than the frame size the state was created with, exactly as
 *   `PP(run)` does, and the frame then never reaches [onRun] -- speex did not touch it either.
 * - [ctlInt] refuses any request outside [SpeexPreprocessorRequests.ALLOWED] with `-1`, exactly as
 *   `preprocessRequestAllowed` does, and the request never reaches the switch.
 * - a request in [refuse] answers `-1` too, which is what speex itself returns for a request this
 *   fixed-point build compiled out (`SET_AGC`, `SET_AGC_TARGET`). The two are indistinguishable
 *   from Kotlin, and that is the documented contract of `SpeexPreprocessApi.ctlInt` -- so the fake
 *   does not distinguish them either.
 */
class FakeSpeexPreprocessApi(
    /** What `SPEEX_PREPROCESS_GET_PROB` answers, in percent, unclamped on purpose. */
    var probability: Int = 0,
    private val onRun: (ShortArray) -> Unit = {},
) : SpeexPreprocessApi {
    /** Every write-direction ctl that got through, as (request, value). */
    val setCalls = mutableListOf<Pair<Int, Int>>()

    /** Every read-direction ctl that got through. */
    val getRequests = mutableListOf<Int>()

    /** Every request the stage issued, whatever the answer was -- including the refused ones. */
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
        return if (probability >= 50) 1 else 0
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
 * The speex request ids, from the one place that decides which of them exist at all.
 *
 * The values come from `SpeexPreprocessNative`'s `const val`s, which Kotlin inlines at this call
 * site -- so this object names them without loading that class, and therefore without
 * `System.loadLibrary("humla_speexdsp")` on a JVM that has no such library. It also means that
 * removing one of those constants breaks this file at **compile** time.
 *
 * [ALLOWED] mirrors `preprocessRequestAllowed` in `src/main/cpp/jni_speexdsp.cpp`, which that file
 * documents as being exactly the ctl requests `SpeexPreprocessNative` declares as constants.
 * Anything else is refused with -1 before it reaches speex -- and -1 is also what speex answers
 * for a request it does not implement, so a stage cannot tell the two apart at run time. The
 * checkable place is therefore here.
 */
object SpeexPreprocessorRequests {
    const val SET_DENOISE = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_DENOISE          // 0
    const val SET_AGC = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_AGC                  // 2
    const val SET_VAD = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_VAD                  // 4
    const val SET_DEREVERB = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_DEREVERB        // 8
    const val SET_PROB_START = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_PROB_START    // 14
    const val GET_PROB_START = SpeexPreprocessNative.SPEEX_PREPROCESS_GET_PROB_START    // 15
    const val SET_NOISE_SUPPRESS = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_NOISE_SUPPRESS // 18
    const val GET_PROB = SpeexPreprocessNative.SPEEX_PREPROCESS_GET_PROB                // 45
    const val SET_AGC_TARGET = SpeexPreprocessNative.SPEEX_PREPROCESS_SET_AGC_TARGET    // 46

    /**
     * `SPEEX_PREPROCESS_SET_PROB_CONTINUE`. A literal, because `SpeexPreprocessNative` deliberately
     * does not declare it: it is **not** on the bridge's allow list, so it never reaches speex and
     * `ctlInt` answers -1 -- indistinguishable from a request speex does not know.
     */
    const val SET_PROB_CONTINUE = 16

    val ALLOWED = setOf(
        SET_DENOISE, SET_AGC, SET_VAD, SET_DEREVERB, SET_PROB_START,
        GET_PROB_START, SET_NOISE_SUPPRESS, GET_PROB, SET_AGC_TARGET,
    )

    /** The two allowed requests that answer by writing `value[0]`; every other one is a write. */
    val READ_DIRECTION = setOf(GET_PROB_START, GET_PROB)
}
