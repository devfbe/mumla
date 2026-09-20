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
 * xiph RNNoise v0.2 with the model embedded in the library. Handles are opaque; 0 means failure.
 *
 * Split into an interface so the capture-pipeline adapters can be unit-tested against a fake
 * without loading a native library, the way the speex bindings in this package are.
 *
 * **A handle may only be passed back to the object that issued it.** 0 is always safe, and
 * [destroy] additionally refuses any value this library did not hand out, but [processFrame] does
 * not: it runs on the audio thread, cannot afford the lock that check needs, and dereferences
 * whatever it is given. A [WebRtcApmNative] handle (a different library with its own handle
 * table), a field read before it was assigned, or two arguments swapped in an adapter is
 * therefore a type-confused dereference or a segmentation fault in native code, with no Java
 * stack trace. Keep each handle in one field of one owner.
 */
interface RnnoiseApi {
    /** A new denoiser, or 0 if one could not be allocated. */
    fun create(): Long

    /**
     * Denoises one 10 ms 48 kHz mono frame in place.
     *
     * [frame] must hold at least [RnnoiseNative.FRAME_SIZE] samples; a shorter array is refused
     * rather than overrun. Only the first [RnnoiseNative.FRAME_SIZE] samples are touched.
     *
     * @return the voice probability in `[0, 1]`, or -1 for a released handle, a null or short
     *   frame, or a failure inside the JVM.
     */
    fun processFrame(handle: Long, frame: ShortArray): Float

    /**
     * Releases [handle]. Calling this twice with the same handle is safe and frees once, and
     * [processFrame] on a released handle returns -1 instead of corrupting the heap (see
     * `jni_native_handle.h`). What is still not allowed is releasing a handle while another
     * thread is inside [processFrame] with it: stop feeding frames first.
     */
    fun destroy(handle: Long)
}

/**
 * The JNI binding of `libhumlarnnoise` (`jni_rnnoise.cpp`); the two files are one interface.
 *
 * Threading: one handle is a single denoiser with its own adaptation state and must not be used
 * from two threads at once; separate handles are independent. [processFrame] takes no lock and is
 * the audio-thread entry point. [create] and [destroy] take a lock and do not belong on the audio
 * thread -- [create] allocates and [destroy] frees, neither with a bounded worst case.
 */
object RnnoiseNative : RnnoiseApi {
    /** Samples per frame RNNoise is built around: 10 ms at 48 kHz. Not configurable. */
    const val FRAME_SIZE = 480

    init {
        // Not "humla_rnnoise": that CMake target name is taken by the static library this JNI
        // library wraps. Same for humlaapm.
        System.loadLibrary("humlarnnoise")
    }

    external override fun create(): Long

    external override fun processFrame(handle: Long, frame: ShortArray): Float

    external override fun destroy(handle: Long)
}
