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

import se.lublin.humla.audio.native.RnnoiseApi
import se.lublin.humla.audio.native.RnnoiseNative

/**
 * A [RnnoiseApi] that behaves like `jni_rnnoise.cpp` does, not like `rnnoise.h` reads.
 *
 * The one behaviour that is modelled rather than knobbed is the short frame: the bridge answers
 * **-1** for a frame shorter than [RnnoiseNative.FRAME_SIZE] and never lets rnnoise touch it. A
 * fake that returns its configured probability for a short frame -- and records the call as
 * processed -- books a **refused** call as a successful one, which is the shape that let an
 * earlier round certify a `-1 -> "certainly speech"` mapping as green. So [processedLengths] gets
 * an entry only for a frame that was really denoised, and [onProcess] runs only then.
 *
 * `HANDLE` is deliberately not 0 and not [FakeWebRtcApmApi.HANDLE]: spec §4.1 says a handle may
 * only go back to the bridge that issued it, and [check] here is the Kotlin-side place that can
 * see an adapter hand the APM's handle to rnnoise.
 */
class FakeRnnoiseApi(
    /** What `rnnoise_process_frame` answers for an accepted frame. Unclamped on purpose. */
    var probability: Float = 0f,
    private val onProcess: (ShortArray) -> Unit = {},
) : RnnoiseApi {
    var failCreate = false

    var created = 0
        private set

    var destroyed = 0
        private set

    /** The length of every frame the bridge accepted, in order. A refused frame leaves nothing. */
    val processedLengths = mutableListOf<Int>()

    override fun create(): Long {
        if (failCreate) return 0L
        created++
        return HANDLE
    }

    override fun processFrame(handle: Long, frame: ShortArray): Float {
        check(handle == HANDLE) { "unknown rnnoise handle $handle" }
        if (frame.size < RnnoiseNative.FRAME_SIZE) return REFUSED
        processedLengths += frame.size
        onProcess(frame)
        return probability
    }

    override fun destroy(handle: Long) {
        check(handle == HANDLE) { "unknown rnnoise handle $handle" }
        destroyed++
    }

    companion object {
        const val HANDLE = 0x0A11L

        /** What the bridge returns for a released handle, a null frame or a short frame. */
        const val REFUSED = -1f
    }
}
