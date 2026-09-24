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

import se.lublin.humla.audio.native.WebRtcApmApi

/** How the APM is configured for one chain (the sample rate belongs to the stage). */
data class WebRtcApmConfig(
    val echoCancellation: Boolean,
    val noiseSuppression: Boolean,
    val gainControl: Boolean,
    val highPass: Boolean = true,
) {
    companion object {
        /** AEC3, AGC2 and high-pass on; APM noise suppression off so the user's setting stays authoritative. */
        val FOR_ECHO_CANCELLATION = WebRtcApmConfig(
            echoCancellation = true,
            noiseSuppression = false,
            gainControl = true,
        )
    }
}

/**
 * Maps the APM's output level in dBFS linearly onto a "voice probability" between [SILENCE_DBFS]
 * (0) and [FULL_DBFS] (1). A loudness threshold, not a speech model; AGC2 holds the non-speech floor
 * near -45 dBFS regardless of input level.
 */
object LevelToProbability {
    const val SILENCE_DBFS = -45f

    const val FULL_DBFS = -23.3f

    fun fromDbfs(dbfs: Float): Float =
        ((dbfs - SILENCE_DBFS) / (FULL_DBFS - SILENCE_DBFS)).coerceIn(0f, 1f)
}

/**
 * The WebRTC APM as a capture stage, with the far-end signal fed via [analyzeReverseStream] on the
 * playback thread ([SingleHandleStage]'s lock covers both threads and release). Per 10 ms tick the
 * far-end frame must go in before the near-end frame containing its echo, or cancellation silently
 * degrades. AEC3 estimates the stream delay itself.
 */
class WebRtcApmPreprocessor private constructor(
    private val api: WebRtcApmApi,
    handle: Long,
    sampleRate: Int,
) : SingleHandleStage(handle, "the webrtc audio processing module at $sampleRate Hz"),
    FarEndSink {

    constructor(
        api: WebRtcApmApi,
        config: WebRtcApmConfig,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
    ) : this(
        api,
        api.create(
            sampleRate,
            config.echoCancellation,
            config.noiseSuppression,
            UNUSED_NOISE_SUPPRESSION_LEVEL,
            config.gainControl,
            config.highPass,
        ),
        sampleRate,
    )

    /** Samples per far-end frame as reported by the APM; oversized frames lose their tail silently. */
    val farEndFrameSize: Int = api.frameSize(handle)

    /** Near-end frames the APM refused; distinguishes "refused" from "no opinion" (both yield null). */
    @Volatile
    var rejectedFrames: Int = 0
        private set

    /** Far-end frames the APM refused (too-short only); the only error signal of the reverse stream. */
    @Volatile
    var rejectedFarEndFrames: Int = 0
        private set

    override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? {
        if (api.processCapture(handle, frame) != 0) {
            rejectedFrames++
            return null
        }
        return LevelToProbability.fromDbfs(api.lastCaptureLevelDbfs(handle))
    }

    override fun onFarEndFrame(handle: Long, frame: ShortArray) {
        if (api.processRender(handle, frame) != 0) rejectedFarEndFrames++
    }

    override fun onReleaseHandle(handle: Long) = api.destroy(handle)

    companion object {
        const val DEFAULT_SAMPLE_RATE = 48000

        /** No effect while the APM's own suppressor is off. */
        private const val UNUSED_NOISE_SUPPRESSION_LEVEL = 0
    }
}
