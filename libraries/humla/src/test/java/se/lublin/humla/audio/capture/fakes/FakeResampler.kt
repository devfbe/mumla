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

import se.lublin.humla.audio.capture.Resampler

/**
 * Repeats each input sample [factor] times (a crude upsampler that keeps values recognisable).
 *
 * [releases] counts rather than flags, because "released" as a boolean cannot separate
 * "released once" from "released twice" -- and a double release is precisely what
 * `CapturePipeline.release()` clearing its field is there to prevent.
 */
class FakeResampler(private val factor: Int) : Resampler {
    var releases = 0
        private set

    val released: Boolean get() = releases > 0

    override fun resample(input: ShortArray, inputLength: Int, output: ShortArray): Int {
        val n = minOf(inputLength * factor, output.size)
        for (i in 0 until n) output[i] = input[i / factor]
        return n
    }

    override fun release() {
        releases++
    }
}

/**
 * Produces nothing and writes nothing, which is what the real adapter does when speex answers
 * with an error code: the output buffer keeps whatever was in it. It is the fixture for
 * "a frame the pipeline could not fill must not be handed on as the previous frame".
 */
class FailingResampler : Resampler {
    var calls = 0
        private set

    override fun resample(input: ShortArray, inputLength: Int, output: ShortArray): Int {
        calls++
        return 0
    }

    override fun release() = Unit
}
