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

/**
 * A [WebRtcApmApi] that behaves like `jni_webrtc_apm.cpp` does.
 *
 * Three behaviours are modelled rather than knobbed, each of them a return value the bridge
 * produces on its own:
 *
 * - an unsupported sample rate answers 0 from [create], which is the real reason a stage's
 *   construction fails; [failCreate] exists for the allocation failure that has no other cause.
 * - [processCapture] and [processRender] refuse a frame shorter than [frameSize] with
 *   [SHORT_FRAME] and never touch it, so a stage that ignores the return value cannot record a
 *   refused call as a successful one. The far-end direction is where that matters most: a
 *   dropped reference frame costs about 21 dB of echo cancellation and nothing above this layer
 *   can see it (task 2, `tests/test_apm.c`).
 * - [lastCaptureLevelDbfs] counts its reads, so a stage that reads the level of a frame the APM
 *   refused is visible here rather than only in the number it returns.
 *
 * [createdWith] reassembles the six flat parameters back into a [WebRtcApmConfig] so a test can
 * compare one value. That only pins the *positional* mapping when the booleans it compares
 * differ -- see `WebRtcApmPreprocessorTest`, which passes two configs chosen so that every pair
 * of the four booleans differs in at least one of them.
 */
class FakeWebRtcApmApi(
    var levelDbfs: Float = -100f,
    var captureError: Int = 0,
    var renderError: Int = 0,
    private val onCapture: (ShortArray) -> Unit = {},
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
            echoCancellation, noiseSuppression, noiseSuppressionLevel, gainControl, highPass,
        )
        if (failCreate || sampleRate !in SUPPORTED_RATES) return 0L
        frameSize = sampleRate / 100
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
