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

import se.lublin.humla.audio.native.SpeexResamplerApi

/**
 * Answers like `jni_speexdsp.cpp` does, including the one detail the adapter has to survive:
 * on an error the bridge returns **before** writing `outLen`, so the caller's own
 * `outLen[0] = output.size` is still standing when it comes back. A reader that trusts it
 * reports a full frame that was never produced.
 *
 * [produced] is the number of samples a successful call writes; [errorCode] makes every call fail.
 */
class FakeSpeexResamplerApi(
    private val handle: Long = 7L,
    private val produced: Int = 480,
    private val errorCode: Int = 0,
    private val fill: Short = 0,
) : SpeexResamplerApi {
    var initCalls = mutableListOf<IntArray>()
        private set
    var destroyed = mutableListOf<Long>()
        private set
    var lastInLength = -1
    var lastOutCapacity = -1
    var lastChannelIndex = -1
    var processCalls = 0
        private set

    override fun init(channels: Int, inRate: Int, outRate: Int, quality: Int, error: IntArray?): Long {
        initCalls += intArrayOf(channels, inRate, outRate, quality)
        return handle
    }

    override fun processInt(
        state: Long,
        channelIndex: Int,
        input: ShortArray,
        inLen: IntArray,
        out: ShortArray,
        outLen: IntArray,
    ): Int {
        processCalls++
        lastChannelIndex = channelIndex
        lastInLength = inLen[0]
        lastOutCapacity = outLen[0]
        // A destroyed or null state is refused by the bridge with RESAMPLER_ERR_INVALID_ARG, and
        // outLen is left exactly as the caller set it.
        if (state == 0L) return ERR_INVALID_ARG
        if (errorCode != 0) return errorCode
        val n = minOf(produced, out.size)
        for (i in 0 until n) out[i] = fill
        inLen[0] = minOf(inLen[0], input.size)
        outLen[0] = n
        return 0
    }

    override fun destroy(state: Long) {
        destroyed += state
    }

    companion object {
        /** `RESAMPLER_ERR_INVALID_ARG` in `speex_resampler.h`. */
        const val ERR_INVALID_ARG = 3

        /** `RESAMPLER_ERR_ALLOC_FAILED`. */
        const val ERR_ALLOC_FAILED = 1
    }
}

/** Fails to create a state, the way speex does when it cannot allocate. */
class RefusingSpeexResamplerApi : SpeexResamplerApi {
    override fun init(channels: Int, inRate: Int, outRate: Int, quality: Int, error: IntArray?): Long = 0L
    override fun processInt(
        state: Long,
        channelIndex: Int,
        input: ShortArray,
        inLen: IntArray,
        out: ShortArray,
        outLen: IntArray,
    ): Int = FakeSpeexResamplerApi.ERR_INVALID_ARG
    override fun destroy(state: Long) = Unit
}
