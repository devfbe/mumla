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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A microphone that hands out a scripted list of frames and then blocks, like the real one does
 * between frames. Varies sample rate, contents and length so no input dimension is constant.
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
