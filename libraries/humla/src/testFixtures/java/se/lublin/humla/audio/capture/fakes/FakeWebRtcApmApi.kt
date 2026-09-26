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

import se.lublin.humla.audio.capture.WebRtcApmConfig
import se.lublin.humla.audio.native.WebRtcApmApi

/** What the real bridge reports for silence. */
private const val SILENCE_DBFS = -100f
private const val FRAMES_PER_SECOND = 100

/**
 * A [WebRtcApmApi] that behaves like `jni_webrtc_apm.cpp`:
 * - an unsupported sample rate answers 0 from [create]; [failCreate] covers allocation failure.
 * - [processCapture] and [processRender] refuse a frame shorter than [frameSize] with
 *   [SHORT_FRAME] and never record it.
 * - [lastCaptureLevelDbfs] counts its reads, so reading the level of a refused frame is visible.
 *
 * [createdWith] reassembles the flat parameters into a rate and a [WebRtcApmConfig]; see
 * `WebRtcApmPreprocessorTest` for how swapped booleans are still caught.
 */
class FakeWebRtcApmApi(
    var levelDbfs: Float = SILENCE_DBFS,
    var captureError: Int = 0,
    var renderError: Int = 0,
    private val onCapture: (ShortArray) -> Unit = {},
    /**
     * Called for every accepted far-end frame. Sharing a list with [onCapture] makes the order of
     * the two streams observable.
     */
    private val onRender: (ShortArray) -> Unit = {},
) : WebRtcApmApi {
    var failCreate = false

    var createdWith: Pair<Int, WebRtcApmConfig>? = null
        private set

    var destroyed = 0
        private set

    var levelReads = 0
        private set

    /** The length of every near-end frame the bridge accepted, in order. */
    val capturedLengths = mutableListOf<Int>()

    /** A copy of every far-end frame the bridge accepted, in order. */
    val renderFrames = mutableListOf<ShortArray>()

    private var frameSize = 0

    override fun create(
        sampleRate: Int,
        echoCancellation: Boolean,
        noiseSuppression: Boolean,
        noiseSuppressionLevel: Int,
        gainControl: Boolean,
        highPass: Boolean,
    ): Long {
        createdWith = sampleRate to WebRtcApmConfig(
            echoCancellation, noiseSuppression, gainControl, highPass,
        )
        if (failCreate || sampleRate !in SUPPORTED_RATES) return 0L
        frameSize = sampleRate / FRAMES_PER_SECOND
        return HANDLE
    }

    override fun frameSize(handle: Long): Int {
        check(handle == HANDLE) { "unknown apm handle $handle" }
        return frameSize
    }

    override fun processCapture(handle: Long, frame: ShortArray): Int {
        check(handle == HANDLE) { "unknown apm handle $handle" }
        if (frame.size < frameSize) return SHORT_FRAME
        capturedLengths += frame.size
        onCapture(frame)
        return captureError
    }

    override fun processRender(handle: Long, frame: ShortArray): Int {
        check(handle == HANDLE) { "unknown apm handle $handle" }
        if (frame.size < frameSize) return SHORT_FRAME
        renderFrames += frame.copyOf()
        onRender(frame)
        return renderError
    }

    override fun lastCaptureLevelDbfs(handle: Long): Float {
        check(handle == HANDLE) { "unknown apm handle $handle" }
        levelReads++
        return levelDbfs
    }

    override fun destroy(handle: Long) {
        check(handle == HANDLE) { "unknown apm handle $handle" }
        destroyed++
    }

    companion object {
        const val HANDLE = 0xA9EL

        /** `webrtc::AudioProcessing::kBadDataLengthError`, what the bridge answers a short frame. */
        const val SHORT_FRAME = -8

        /** The only rates `humla_apm_create` accepts; anything else answers 0. */
        val SUPPORTED_RATES = setOf(8000, 16000, 32000, 48000)
    }
}
