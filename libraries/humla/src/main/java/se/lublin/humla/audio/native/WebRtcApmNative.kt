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
 * freedesktop webrtc-audio-processing v2.1: AEC3, noise suppression, AGC2 and a high-pass filter.
 *
 * Split into an interface so the capture-pipeline adapters can be unit-tested against a fake
 * without loading a native library.
 *
 * **A handle may only be passed back to the object that issued it.** 0 is always safe, and
 * [destroy] additionally refuses any value this library did not hand out, but [frameSize],
 * [processCapture], [processRender] and [lastCaptureLevelDbfs] do not: they run on the audio
 * threads, cannot afford the lock that check needs, and dereference whatever they are given. A
 * [RnnoiseNative] handle (a different library with its own handle table), a field read before it
 * was assigned, or two arguments swapped in an adapter is therefore a type-confused dereference
 * or a segmentation fault in native code, with no Java stack trace. Keep each handle in one field
 * of one owner.
 *
 * **The two streams have to be fed in a fixed relation.** For every 10 ms tick:
 *
 * ```
 * processRender(handle, frameAboutToBePlayed)
 * processCapture(handle, frameJustRecorded)
 * ```
 *
 * The far-end frame has to be handed over before the near-end frame that will contain its echo.
 * Getting this wrong is silent -- every call still returns 0 and echo cancellation just stops
 * working, by about 21 dB. The native host test drives these entry points and measures it
 * (`tests/test_jni_bridges.cpp`); feeding nothing, feeding the wrong buffer or feeding the
 * reference late all fail there. AEC3's delay estimator absorbs one 10 ms frame of slack and no
 * more, so there is nothing to spend.
 */
interface WebRtcApmApi {
    /**
     * @param sampleRate 8000, 16000, 32000 or 48000; any other rate returns 0.
     * @param noiseSuppressionLevel 0 low .. 3 very high; out-of-range values are clamped, not
     *   rejected, because this comes from a user preference.
     * @return a handle, or 0 on failure.
     */
    fun create(
        sampleRate: Int,
        echoCancellation: Boolean,
        noiseSuppression: Boolean,
        noiseSuppressionLevel: Int,
        gainControl: Boolean,
        highPass: Boolean,
    ): Long

    /**
     * Samples per 10 ms frame at the rate [handle] was created with, i.e. exactly how long the
     * arrays passed to [processCapture] and [processRender] have to be. 0 for a released handle.
     *
     * Callers should size their buffers from this rather than from their own idea of the sample
     * rate: it is the number the native side will actually read and write.
     */
    fun frameSize(handle: Long): Int

    /**
     * Processes one 10 ms mono near-end frame in place.
     *
     * [frame] must hold at least [frameSize] samples; a shorter array is refused with -8 rather
     * than overrun. Only the first [frameSize] samples are touched.
     *
     * @return 0 on success, or a negative `webrtc::AudioProcessing::Error` (-5 for a released
     *   handle or a failure inside the JVM, -8 for a short frame).
     */
    fun processCapture(handle: Long, frame: ShortArray): Int

    /**
     * Feeds one 10 ms mono far-end frame; the APM may modify it in place. Same length rule and
     * same return values as [processCapture]. Must be called before the capture frame that
     * carries its echo.
     */
    fun processRender(handle: Long, frame: ShortArray): Int

    /** dBFS RMS level of the last frame [processCapture] returned; -100 for silence and for a
     *  released handle. */
    fun lastCaptureLevelDbfs(handle: Long): Float

    /**
     * Releases [handle]. Calling this twice with the same handle is safe and frees once, and the
     * process functions on a released handle return -5 instead of corrupting the heap (see
     * `jni_native_handle.h`). What is still not allowed is releasing a handle while another
     * thread is inside one of the process functions with it: stop both streams first.
     */
    fun destroy(handle: Long)
}

/**
 * The JNI binding of `libhumlaapm` (`jni_webrtc_apm.cpp`); the two files are one interface.
 *
 * Threading: [processCapture] and [processRender] take no lock and are meant to run on the
 * capture and playback threads, one each, concurrently with one another. Two threads must not
 * call the *same* one of them for the same handle. [create] and [destroy] take a lock, allocate
 * and free, and do not belong on either audio thread.
 *
 * `humla_apm_set_stream_delay_ms` is deliberately not bridged: AEC3 estimates the delay itself,
 * and feeding it the true delay was measured to move the residual echo by less than 0.02 dB.
 */
object WebRtcApmNative : WebRtcApmApi {
    init {
        System.loadLibrary("humlaapm")
    }

    external override fun create(
        sampleRate: Int,
        echoCancellation: Boolean,
        noiseSuppression: Boolean,
        noiseSuppressionLevel: Int,
        gainControl: Boolean,
        highPass: Boolean,
    ): Long

    external override fun frameSize(handle: Long): Int

    external override fun processCapture(handle: Long, frame: ShortArray): Int

    external override fun processRender(handle: Long, frame: ShortArray): Int

    external override fun lastCaptureLevelDbfs(handle: Long): Float

    external override fun destroy(handle: Long)
}
