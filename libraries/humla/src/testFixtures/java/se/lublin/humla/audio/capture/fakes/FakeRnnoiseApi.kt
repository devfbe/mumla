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
 * A [RnnoiseApi] that behaves like `jni_rnnoise.cpp`: a frame shorter than
 * [RnnoiseNative.FRAME_SIZE] answers -1 and is neither recorded in [processedLengths] nor passed to
 * [onProcess], so a refused call cannot look like a successful one.
 *
 * `HANDLE` differs from 0 and from [FakeWebRtcApmApi.HANDLE], so [check] catches a handle passed to
 * the wrong bridge.
 */
internal class FakeRnnoiseApi(
    /** What `rnnoise_process_frame` answers for an accepted frame. Unclamped on purpose. */
    var probability: Float = 0f,
    private val onProcess: (ShortArray) -> Unit = {},
) : RnnoiseApi {
    var failCreate = false

    var created = 0
        private set

    /** Every call to [create], including refused ones, so "never asked" is distinguishable. */
    var createAttempts = 0
        private set

    var destroyed = 0
        private set

    /** The length of every frame the bridge accepted, in order. A refused frame leaves nothing. */
    val processedLengths = mutableListOf<Int>()

    override fun create(): Long {
        createAttempts++
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
