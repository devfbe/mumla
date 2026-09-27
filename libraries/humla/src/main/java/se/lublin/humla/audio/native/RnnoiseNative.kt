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

package se.lublin.humla.audio.native

/**
 * xiph RNNoise with the model embedded in the library. Handles are opaque; 0 means failure. An
 * interface so the capture adapters can be tested against a fake.
 *
 * **A handle may only be passed back to the object that issued it.** 0, released and invented
 * handles are refused, but a handle from another bridge may name a live denoiser that belongs to
 * someone else.
 */
internal interface RnnoiseApi {
    /** A new denoiser, or 0 if one could not be allocated. */
    fun create(): Long

    /**
     * Denoises one 10 ms 48 kHz mono frame in place; only the first [RnnoiseNative.FRAME_SIZE]
     * samples are touched.
     *
     * @return the voice probability in `[0, 1]`, or -1 for a released handle, a null or short
     *   frame, or a failure inside the JVM.
     */
    fun processFrame(handle: Long, frame: ShortArray): Float

    /**
     * Releases [handle]; a second call is a no-op and later [processFrame] calls return -1. Must
     * not race a [processFrame] on another thread: stop feeding frames first.
     */
    fun destroy(handle: Long)
}

/**
 * JNI binding of RNNoise (`jni_rnnoise.cpp`).
 *
 * A handle must not be used from two threads at once. [processFrame] takes no lock; [create] and
 * [destroy] lock and allocate, so keep them off the audio thread.
 */
internal object RnnoiseNative : RnnoiseApi {
    /** Samples per frame RNNoise is built around: 10 ms at 48 kHz. Not configurable. */
    const val FRAME_SIZE = 480

    init {
        HumlaNativeLibrary.load()
    }

    external override fun create(): Long

    external override fun processFrame(handle: Long, frame: ShortArray): Float

    external override fun destroy(handle: Long)
}
