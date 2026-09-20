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

import se.lublin.humla.audio.native.SpeexResamplerApi
import se.lublin.humla.audio.native.SpeexResamplerNative

/** Mono sample-rate converter. */
interface Resampler {
    /**
     * Converts [inputLength] samples of [input] into [output].
     *
     * @return the number of output samples written, in `0..output.size`. The upper bound is a
     *   precondition of the caller rather than something [CapturePipeline] re-checks: it pads the
     *   buffer from this index to the end, so a number above `output.size` is an exception on the
     *   audio thread. Both implementations in this tree clamp before returning.
     *
     *   **Zero is a legal answer and means what it says**: no sample of [output] was written, so
     *   whatever the buffer held before is still there. A converter that failed answers 0 rather
     *   than the capacity it was asked for.
     */
    fun resample(input: ShortArray, inputLength: Int, output: ShortArray): Int

    /** Frees native resources; the instance must not be used afterwards. Idempotent. */
    fun release()
}

/**
 * Adapter over F6's [SpeexResamplerNative] (`init` takes a trailing `error: IntArray?`, and
 * `processInt` reports the produced sample count in `outLen[0]` while returning the speex error
 * code). The only place in this stream that names those methods.
 *
 * Single-threaded: the capture thread owns it. The two one-element `int[]` are fields rather than
 * `intArrayOf(...)` at the call site, because the call site is a 10 ms frame path -- see
 * `CaptureThreadAllocationTest`.
 *
 * **The error code is read, and that is the whole reason this class is not three forwarding
 * lines.** `jni_speexdsp.cpp:163-173` refuses a bad argument -- a destroyed state, a null array, a
 * short `int[]` -- by returning `RESAMPLER_ERR_INVALID_ARG` **before** it writes `outLen`, so the
 * `outLen[0] = output.size` set two lines above is still standing when the call comes back.
 * Returning it unchecked reports a full frame that was never written; and since
 * [CapturePipeline]'s frame buffer is reused across frames, "never written" is not silence, it is
 * the previous frame going out on the wire again. Pinned in `SpeexResamplerTest`.
 */
class SpeexResampler @JvmOverloads constructor(
    inputRate: Int,
    outputRate: Int,
    quality: Int = DEFAULT_QUALITY,
    private val api: SpeexResamplerApi = SpeexResamplerNative,
) : Resampler {
    private var state: Long = api.init(1, inputRate, outputRate, quality, null)
    private val inLength = IntArray(1)
    private val outLength = IntArray(1)

    init {
        check(state != 0L) { "speex_resampler_init($inputRate -> $outputRate) failed" }
    }

    override fun resample(input: ShortArray, inputLength: Int, output: ShortArray): Int {
        inLength[0] = inputLength
        outLength[0] = output.size
        val error = api.processInt(state, 0, input, inLength, output, outLength)
        return if (error == RESAMPLER_ERR_SUCCESS) outLength[0] else 0
    }

    override fun release() {
        if (state != 0L) {
            api.destroy(state)
            state = 0L
        }
    }

    companion object {
        /** Same quality the old ResamplingEncoder used. */
        const val DEFAULT_QUALITY = 3

        /** `RESAMPLER_ERR_SUCCESS` in `speex_resampler.h`; every other value is a failure. */
        private const val RESAMPLER_ERR_SUCCESS = 0
    }
}
