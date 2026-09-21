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

package se.lublin.mumla.audio

import se.lublin.humla.audio.capture.CaptureRequest
import se.lublin.humla.audio.capture.PcmCaptureSource
import se.lublin.humla.audio.capture.PcmCaptureSourceFactory
import se.lublin.humla.audio.native.RnnoiseApi
import se.lublin.humla.audio.native.SpeexPreprocessApi
import se.lublin.humla.audio.native.SpeexPreprocessNative
import se.lublin.humla.audio.native.WebRtcApmApi
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A microphone that hands out a scripted list of frames and then blocks, like the real one does
 * between frames.
 *
 * Every fake here can produce **more than one value** on every input the production file branches
 * on -- the sample rate, the frame contents, the length -- because a fake that answers a constant
 * closes a whole dimension of the enumeration without anybody noticing (spec 4.04).
 */
class TestCaptureSource(
    frames: List<ShortArray>,
    override val sampleRate: Int = 48000,
    /** Repeats the last frame forever instead of blocking, for tests that need a steady stream. */
    private val loopLastFrame: Boolean = false,
) : PcmCaptureSource {
    override val audioSessionId = 1
    val events = CopyOnWriteArrayList<String>()
    private val queue = LinkedBlockingQueue(frames.ifEmpty { listOf(ShortArray(0)) })
    private var last: ShortArray? = null

    @Volatile
    private var stopped = false

    override fun start() {
        events += "start"
    }

    override fun read(buffer: ShortArray, length: Int): Int {
        while (!stopped) {
            val frame = queue.poll(2, TimeUnit.MILLISECONDS) ?: last?.takeIf { loopLastFrame } ?: continue
            if (frame.isEmpty()) continue
            last = frame
            val n = minOf(frame.size, length)
            System.arraycopy(frame, 0, buffer, 0, n)
            return n
        }
        return 0
    }

    override fun stop() {
        events += "stop"
        stopped = true
    }

    override fun release() {
        events += "release"
    }

    override fun setSilenceListener(listener: ((Boolean) -> Unit)?) = Unit

    class Factory(private val source: PcmCaptureSource) : PcmCaptureSourceFactory {
        var request: CaptureRequest? = null
        override fun open(request: CaptureRequest): PcmCaptureSource {
            this.request = request
            return source
        }
    }
}

class TestPlaybackSink : PcmPlaybackSink {
    val events = CopyOnWriteArrayList<String>()
    val written = CopyOnWriteArrayList<ShortArray>()

    override fun play() {
        events += "play"
    }

    override fun write(buffer: ShortArray, length: Int): Int {
        written += buffer.copyOf(length)
        return length
    }

    override fun pause() {
        events += "pause"
    }

    override fun flush() {
        events += "flush"
    }

    override fun stop() {
        events += "stop"
    }

    override fun release() {
        events += "release"
    }

    class Factory(private val sink: PcmPlaybackSink) : PcmPlaybackSinkFactory {
        var openedWith: Pair<Int, Int>? = null
        override fun open(audioStream: Int, sampleRate: Int): PcmPlaybackSink {
            openedWith = audioStream to sampleRate
            return sink
        }
    }
}

/**
 * Scales every sample by [gain] and reports a fixed speech probability, so that a test can tell
 * whether the meter measured the frame before or after the preprocessor ran.
 */
class ScalingSpeexApi(
    private val gain: Float,
    private val probabilityPercent: Int = 0,
) : SpeexPreprocessApi {
    override fun init(frameSize: Int, sampleRate: Int): Long = 1L

    override fun run(state: Long, frame: ShortArray): Int {
        for (i in frame.indices) frame[i] = (frame[i] * gain).toInt().toShort()
        return if (probabilityPercent >= 50) 1 else 0
    }

    override fun ctlInt(state: Long, request: Int, value: IntArray): Int {
        if (request == SpeexPreprocessNative.SPEEX_PREPROCESS_GET_PROB) {
            value[0] = probabilityPercent
            return 0
        }
        return 0
    }

    override fun destroy(state: Long) = Unit
}

/** A chain that refuses to come up, so the factory falls back to no stage at all. */
class AbsentRnnoiseApi : RnnoiseApi {
    override fun create(): Long = 0L
    override fun processFrame(handle: Long, frame: ShortArray): Float = 0f
    override fun destroy(handle: Long) = Unit
}

class AbsentApmApi : WebRtcApmApi {
    override fun create(
        sampleRate: Int,
        echoCancellation: Boolean,
        noiseSuppression: Boolean,
        noiseSuppressionLevel: Int,
        gainControl: Boolean,
        highPass: Boolean,
    ): Long = 0L

    override fun frameSize(handle: Long): Int = 0
    override fun processCapture(handle: Long, frame: ShortArray): Int = -1
    override fun processRender(handle: Long, frame: ShortArray): Int = -1
    override fun lastCaptureLevelDbfs(handle: Long): Float = -100f
    override fun destroy(handle: Long) = Unit
}
