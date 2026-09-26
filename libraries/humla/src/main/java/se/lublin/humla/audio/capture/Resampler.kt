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
     * @return the number of output samples written, in `0..output.size` (callers rely on the upper
     *   bound). 0 means nothing was written and [output] keeps its previous contents; failures return 0.
     */
    fun resample(input: ShortArray, inputLength: Int, output: ShortArray): Int

    /** Frees native resources; the instance must not be used afterwards. Idempotent. */
    fun release()
}

/**
 * Adapter over [SpeexResamplerNative]. Single-threaded (capture thread); the `int[]` out-params are
 * reused fields to avoid per-frame allocation.
 *
 * The error code must be checked: on invalid arguments the bridge returns an error before writing
 * `outLen`, so the preset `outLen[0] = output.size` would otherwise report a full frame and resend
 * the previous frame's stale buffer contents.
 */
class SpeexResampler(
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
        /** Speex resampler quality (0-10). */
        const val DEFAULT_QUALITY = 3

        /** `RESAMPLER_ERR_SUCCESS` in `speex_resampler.h`; every other value is a failure. */
        private const val RESAMPLER_ERR_SUCCESS = 0
    }
}
