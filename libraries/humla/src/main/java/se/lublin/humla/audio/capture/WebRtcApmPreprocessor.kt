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

/**
 * How the APM is configured for one chain. Holds four of the bridge's six parameters: the sample
 * rate belongs to the stage and the noise-suppression level is unused (see
 * [WebRtcApmPreprocessor.UNUSED_NOISE_SUPPRESSION_LEVEL]).
 */
data class WebRtcApmConfig(
    val echoCancellation: Boolean,
    val noiseSuppression: Boolean,
    val gainControl: Boolean,
    val highPass: Boolean = true,
) {
    companion object {
        /**
         * Used when echo cancellation is WEBRTC: AEC3, AGC2 and high-pass on, the APM's own noise
         * suppression off, so the user's noise suppression setting stays authoritative (no cascaded
         * suppressors, and "None" means none).
         */
        val FOR_ECHO_CANCELLATION = WebRtcApmConfig(
            echoCancellation = true,
            noiseSuppression = false,
            gainControl = true,
        )
    }
}

/**
 * Maps the APM's output level in dBFS onto a "voice probability": [SILENCE_DBFS] and below is 0,
 * [FULL_DBFS] and above is 1, linear in between.
 *
 * This is a loudness threshold, not a speech model; with noise suppression NONE and echo
 * cancellation WEBRTC it is the only opinion in the chain, and thresholds tuned against RNNoise do
 * not transfer. The window matches this chain's processed output, where AGC2 holds the non-speech
 * floor near -45 dBFS regardless of input level.
 */
object LevelToProbability {
    /** Typical non-speech floor of this chain; at or below it the stage reports 0. */
    const val SILENCE_DBFS = -45f

    /** At or above this level the stage reports 1 (a 0.6 threshold is then 13 dB over the floor). */
    const val FULL_DBFS = -23.3f

    fun fromDbfs(dbfs: Float): Float =
        ((dbfs - SILENCE_DBFS) / (FULL_DBFS - SILENCE_DBFS)).coerceIn(0f, 1f)
}

/**
 * The WebRTC APM as a capture stage: AEC3, AGC2 and high-pass on the near-end path, far-end signal
 * via [analyzeReverseStream]; its own noise suppressor is off (see
 * [WebRtcApmConfig.FOR_ECHO_CANCELLATION]).
 *
 * `processCapture` runs on the capture thread, `processRender` on the playback thread, and release
 * may come from a third; [SingleHandleStage]'s lock covers all three.
 *
 * Per 10 ms tick the far-end frame must go in before the near-end frame containing its echo; getting
 * this wrong fails silently with much worse cancellation. `FarEndFrameChunker` produces those frames.
 * The stream delay is not bridged: AEC3 estimates it itself.
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

    /**
     * Samples per far-end frame, as reported by the APM; size `FarEndFrameChunker` with this. Oversized
     * far-end frames are accepted with their tail silently dropped, so this must not be a guessed
     * constant. Read once at construction, so it stays valid after release.
     */
    val farEndFrameSize: Int = api.frameSize(handle)

    /**
     * Near-end frames the APM refused (any non-zero `webrtc::AudioProcessing::Error`, e.g. -8 for a
     * too-short frame). Separates "refused" from "no opinion", since both yield null.
     */
    @Volatile
    var rejectedFrames: Int = 0
        private set

    /**
     * Far-end frames the APM refused. The reverse stream returns nothing to its caller, so this is the
     * only signal. Only too-short frames are counted; too-long frames lose their tail unnoticed.
     */
    @Volatile
    var rejectedFarEndFrames: Int = 0
        private set

    override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? {
        if (api.processCapture(handle, frame) != 0) {
            rejectedFrames++
            // No opinion on a frame the APM never processed (not a stale or -100 dBFS level).
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

        /**
         * `humla_apm_create` always takes a noise-suppression level; it has no effect while the APM's
         * own suppressor is off. If that is ever turned back on, move the level into [WebRtcApmConfig].
         */
        private const val UNUSED_NOISE_SUPPRESSION_LEVEL = 0
    }
}
