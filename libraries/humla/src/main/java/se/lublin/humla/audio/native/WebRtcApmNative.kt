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
 * webrtc-audio-processing: AEC3, noise suppression, AGC2 and a high-pass filter.
 *
 * **A handle may only be passed back to the object that issued it.** 0, released and invented
 * handles are refused, but a handle from another bridge may name a live APM that belongs to
 * someone else.
 * Each 10 ms tick, call [processRender] before [processCapture] with the frame containing its echo.
 */
internal interface WebRtcApmApi {
    /**
     * @param sampleRate 8000, 16000, 32000 or 48000; any other rate returns 0.
     * @param noiseSuppressionLevel 0 low .. 3 very high; out-of-range values are clamped.
     * @param aec3Tuning null for webrtc's default AEC3, else one value per `Aec3Param` (see
     *   `Aec3Tuning.toArray`); another length, a non-finite value or a masking pair out of order
     *   returns 0. Only read here, and ignored without [echoCancellation].
     * @return a handle, or 0 on failure.
     */
    @Suppress("LongParameterList") // mirrors the flat JNI signature; WebRtcApmConfig is the structured side
    fun create(
        sampleRate: Int,
        echoCancellation: Boolean,
        noiseSuppression: Boolean,
        noiseSuppressionLevel: Int,
        gainControl: Boolean,
        highPass: Boolean,
        aec3Tuning: FloatArray?,
    ): Long

    /** Samples per 10 ms frame at [handle]'s rate; 0 for a released handle. */
    fun frameSize(handle: Long): Int

    /**
     * Processes one 10 ms mono near-end frame in place (first [frameSize] samples).
     *
     * @return 0 on success, or a negative `webrtc::AudioProcessing::Error` (-5 for a released
     *   handle or a JVM failure, -8 for a frame shorter than [frameSize]).
     */
    fun processCapture(handle: Long, frame: ShortArray): Int

    /** Feeds one 10 ms mono far-end frame (may be modified); same rules as [processCapture]. */
    fun processRender(handle: Long, frame: ShortArray): Int

    /** dBFS RMS level of the last processed capture frame; -100 for silence or a released handle. */
    fun lastCaptureLevelDbfs(handle: Long): Float

    /**
     * Releases [handle]; a second call is a no-op and later process calls return -5. Must not
     * race a process call on another thread: stop both streams first.
     */
    fun destroy(handle: Long)
}

/**
 * JNI binding of the APM (`jni_webrtc_apm.cpp`). [processCapture] and [processRender] take no
 * lock and may run concurrently, each from one thread per handle. [create] and [destroy] lock and allocate; keep
 * them off the audio threads.
 */
internal object WebRtcApmNative : WebRtcApmApi {
    init {
        HumlaNativeLibrary.load()
    }

    external override fun create(
        sampleRate: Int,
        echoCancellation: Boolean,
        noiseSuppression: Boolean,
        noiseSuppressionLevel: Int,
        gainControl: Boolean,
        highPass: Boolean,
        aec3Tuning: FloatArray?,
    ): Long

    external override fun frameSize(handle: Long): Int

    external override fun processCapture(handle: Long, frame: ShortArray): Int

    external override fun processRender(handle: Long, frame: ShortArray): Int

    external override fun lastCaptureLevelDbfs(handle: Long): Float

    external override fun destroy(handle: Long)
}
