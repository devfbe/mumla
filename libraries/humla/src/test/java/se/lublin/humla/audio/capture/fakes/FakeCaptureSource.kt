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

import se.lublin.humla.audio.capture.AndroidAudioRecordSource
import se.lublin.humla.audio.capture.PcmCaptureSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [PcmCaptureSource] that behaves like a blocking `AudioRecord`, scripted read by read: full,
 * short and zero reads, negative codes, reads that answer only after [stop] or keep answering after
 * it, a throwing [start], and reads after [release].
 *
 * Copied from the real thing on purpose:
 * - Reads ignore thread interrupts; only `stop()` or an error ends a native read.
 * - [codeAfterStop] is what an in-flight read answers once [stop] has run; a real one may come
 *   back with a negative code when the recorder is taken away underneath it.
 * - [sampleRate] throws after [release], as `AudioRecord`'s accessors do.
 */
class FakeCaptureSource(
    private val rate: Int = 48000,
    script: List<Read> = emptyList(),
    /** Held shut, a read in flight at [stop] does not return until it opens: a hung native read. */
    private val hangAfterStop: CountDownLatch? = null,
    private val failStart: Boolean = false,
    private val codeAfterStop: Int = 0,
) : PcmCaptureSource {
    /** One scripted answer from [read]. */
    sealed interface Read {
        /** Copied into the caller's buffer; the count returned is how many samples fit. */
        class Frame(val samples: ShortArray) : Read

        /** Returned verbatim: `0` for "nothing was read", negative for an error. */
        class Code(val value: Int) : Read
    }

    override val sampleRate: Int
        get() = if (released) error("sampleRate read after release") else rate

    override val audioSessionId: Int = 42

    /** `start`, `stop` and `release` in the order they happened. */
    val events = CopyOnWriteArrayList<String>()

    /** By identity, not name: coroutines rename the threads they run on. */
    val readingThreads = CopyOnWriteArraySet<Thread>()

    val reads = AtomicInteger()

    var silenceListener: ((Boolean) -> Unit)? = null
        private set

    private val queue = LinkedBlockingQueue<Read>().apply { addAll(script) }

    @Volatile
    var stopped = false
        private set

    @Volatile
    private var released = false

    override fun start() {
        events += "start"
        stopped = false
        if (failStart) throw IllegalStateException("startRecording failed")
    }

    override fun read(buffer: ShortArray, length: Int): Int {
        readingThreads += Thread.currentThread()
        reads.incrementAndGet()
        while (true) {
            if (released) return AndroidAudioRecordSource.ERROR_RELEASED
            try {
                when (val next = queue.poll(2, TimeUnit.MILLISECONDS)) {
                    is Read.Frame -> {
                        val n = minOf(next.samples.size, length)
                        System.arraycopy(next.samples, 0, buffer, 0, n)
                        return n
                    }
                    is Read.Code -> return next.value
                    null -> if (stopped) {
                        hangIgnoringInterrupts()
                        return codeAfterStop
                    }
                }
            } catch (e: InterruptedException) {
                // Swallowed, as a blocking native read does.
            }
        }
    }

    private fun hangIgnoringInterrupts() {
        val gate = hangAfterStop ?: return
        while (true) {
            try {
                gate.await()
                return
            } catch (e: InterruptedException) {
                // A native read does not return on interrupt either.
            }
        }
    }

    override fun stop() {
        events += "stop"
        stopped = true
    }

    override fun release() {
        events += "release"
        released = true
    }

    override fun setSilenceListener(listener: ((Boolean) -> Unit)?) {
        silenceListener = listener
    }
}
