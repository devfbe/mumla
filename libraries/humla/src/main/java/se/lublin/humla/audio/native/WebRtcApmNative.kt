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
 * webrtc-audio-processing: AEC3, noise suppression, AGC2 and a high-pass filter. An interface so
 * the capture adapters can be tested against a fake.
 *
 * **A handle may only be passed back to the object that issued it.** 0 is always safe and
 * [destroy] refuses unknown values, but the per-frame functions dereference whatever they are
 * given (no lock on the audio threads), so a foreign or stale handle crashes in native code.
 *
 * Each 10 ms tick, call [processRender] with the frame about to be played before [processCapture]
 * with the frame that will contain its echo. Getting this wrong is silent: echo cancellation just
 * stops working. AEC3 tolerates about one frame of slack.
 */
interface WebRtcApmApi {
    /**
     * @param sampleRate 8000, 16000, 32000 or 48000; any other rate returns 0.
     * @param noiseSuppressionLevel 0 low .. 3 very high; out-of-range values are clamped.
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
     * Samples per 10 ms frame at [handle]'s rate: the array length [processCapture] and
     * [processRender] need. 0 for a released handle.
     */
    fun frameSize(handle: Long): Int

    /**
     * Processes one 10 ms mono near-end frame in place; only the first [frameSize] samples are
     * touched.
     *
     * @return 0 on success, or a negative `webrtc::AudioProcessing::Error` (-5 for a released
     *   handle or a JVM failure, -8 for a frame shorter than [frameSize]).
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
     * Releases [handle]; a second call is a no-op and later process calls return -5. Must not
     * race a process call on another thread: stop both streams first.
     */
    fun destroy(handle: Long)
}

/**
 * JNI binding of `libhumlaapm` (`jni_webrtc_apm.cpp`).
 *
 * [processCapture] and [processRender] take no lock and may run concurrently on the capture and
 * playback threads, but each only from one thread per handle. [create] and [destroy] lock and
 * allocate; keep them off the audio threads. The stream delay is not bridged: AEC3 estimates it.
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
