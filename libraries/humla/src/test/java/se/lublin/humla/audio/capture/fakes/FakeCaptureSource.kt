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
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [PcmCaptureSource] that behaves like a blocking `AudioRecord`, scripted read by read.
 *
 * Every value the production loop branches on has to be producible here, or the branch is closed
 * off by the double rather than by the code (spec 4.04's fakes pass): a full frame, a **short**
 * frame, a **zero** read, a **negative** error code, a read that answers only after [stop], one
 * that keeps answering for a while **after** [stop], a [start] that throws, and a [read] after
 * [release].
 *
 * Two behaviours are copied from the real thing on purpose:
 * - **Reads ignore thread interrupts.** A native `AudioRecord.read` does; only `stop()` (or an
 *   error) ends one. A fake that returned on an interrupt would let `AudioInput.stopRecording`
 *   pass its test with the `source.stop()` call deleted.
 * - **[sampleRate] throws once [release] has run**, because `AudioRecord`'s accessors do. Without
 *   that, "the rate is cached, not read off a released recorder" is not a statement any test in
 *   this module can distinguish -- and the Java original's `getSampleRate()` dereferenced a field
 *   `shutdown()` had just nulled out.
 */
class FakeCaptureSource(
    private val rate: Int = 48000,
    script: List<Read> = emptyList(),
    private val hangAfterStopMs: Long = 0,
    private val failStart: Boolean = false,
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

    /** By identity, never by name: `kotlinx.coroutines` renames threads (spec 4.05). */
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
                        return 0
                    }
                }
            } catch (e: InterruptedException) {
                // Swallowed, as a blocking native read does.
            }
        }
    }

    /** A recorder whose `stop()` does not make the in-flight read return, for the join timeout. */
    private fun hangIgnoringInterrupts() {
        val deadline = System.currentTimeMillis() + hangAfterStopMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return
            try {
                Thread.sleep(left)
            } catch (e: InterruptedException) {
                // Again: a native read does not come back because someone interrupted us.
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
