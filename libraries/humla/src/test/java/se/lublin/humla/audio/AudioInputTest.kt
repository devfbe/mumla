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

package se.lublin.humla.audio

import android.os.Process
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.AndroidAudioRecordSource
import se.lublin.humla.audio.capture.CaptureState
import se.lublin.humla.audio.capture.VoiceActivityDetector
import se.lublin.humla.audio.capture.fakes.FakeCaptureSource
import se.lublin.humla.audio.capture.fakes.FakeCaptureSource.Read
import se.lublin.humla.testutil.awaitUntil
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * `AudioInput` owns the capture thread: it pumps 10 ms frames out of a
 * [se.lublin.humla.audio.capture.PcmCaptureSource] and shuts down without hanging the caller.
 */
@RunWith(RobolectricTestRunner::class)
class AudioInputTest {
    private companion object {
        const val FRAME = 480
        const val SETTLE_MS = 2000L

        /** Default start threshold of the amplitude detector. */
        const val START_THRESHOLD = 0.6f
    }

    private val received = CopyOnWriteArrayList<ShortArray>()
    private val reportedLengths = CopyOnWriteArrayList<Int>()
    private val identities = CopyOnWriteArrayList<ShortArray>()
    private val listenerThreads = CopyOnWriteArrayList<Thread>()
    private val priorities = CopyOnWriteArrayList<Int>()
    private val states = CopyOnWriteArrayList<CaptureState>()
    private var input: AudioInput? = null

    /** Opened in [tearDown], so no read hangs past its test. */
    private val hung = CountDownLatch(1)

    @After
    fun tearDown() {
        hung.countDown()
        input?.shutdown()
    }

    private fun frame(value: Int, length: Int = FRAME) = Read.Frame(ShortArray(length) { value.toShort() })

    /** Records the frame by value and by identity, plus who delivered it and at what priority. */
    private fun recorder(latch: CountDownLatch? = null) = AudioInput.AudioInputListener { f, length ->
        received += f.copyOf()
        reportedLengths += length
        identities += f
        listenerThreads += Thread.currentThread()
        priorities += Process.getThreadPriority(Process.myTid())
        latch?.countDown()
    }

    private fun start(
        source: FakeCaptureSource,
        listener: AudioInput.AudioInputListener = AudioInput.AudioInputListener { _, _ -> },
        joinTimeoutMs: Long = 2000,
    ): AudioInput = AudioInput(listener, source, { states += it }, joinTimeoutMs).also {
        input = it
        it.startRecording()
    }

    private fun waitUntil(what: String, condition: () -> Boolean) = awaitUntil(SETTLE_MS, what, condition)

    @Test
    fun `delivers frames to the listener in order`() {
        val latch = CountDownLatch(3)
        val source = FakeCaptureSource(script = listOf(frame(1), frame(2), frame(3)))

        start(source, recorder(latch))

        assertThat(latch.await(SETTLE_MS, TimeUnit.MILLISECONDS)).isTrue()
        assertThat(received.map { it[0].toInt() }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun `the frame is ten milliseconds at the source rate`() {
        assertThat(AudioInput({ _, _ -> }, FakeCaptureSource()).frameSize).isEqualTo(480)
        assertThat(AudioInput({ _, _ -> }, FakeCaptureSource(rate = 16000)).frameSize).isEqualTo(160)
        assertThat(AudioInput({ _, _ -> }, FakeCaptureSource(rate = 8000)).frameSize).isEqualTo(80)
        assertThat(AudioInput({ _, _ -> }, FakeCaptureSource(rate = 16000)).sampleRate).isEqualTo(16000)
    }

    /** By identity, not name: coroutines rename the threads they run on. */
    @Test
    fun `the listener runs on the capture thread and not on the caller's`() {
        val caller = Thread.currentThread()
        val latch = CountDownLatch(2)
        val source = FakeCaptureSource(script = listOf(frame(1), frame(2)))

        start(source, recorder(latch))
        assertThat(latch.await(SETTLE_MS, TimeUnit.MILLISECONDS)).isTrue()

        assertThat(listenerThreads.toSet()).hasSize(1)
        assertThat(listenerThreads.first()).isNotSameInstanceAs(caller)
        assertThat(source.readingThreads).containsExactly(listenerThreads.first())
    }

    @Test
    fun `the capture thread runs at urgent audio priority`() {
        val latch = CountDownLatch(1)

        start(FakeCaptureSource(script = listOf(frame(1))), recorder(latch))
        assertThat(latch.await(SETTLE_MS, TimeUnit.MILLISECONDS)).isTrue()

        assertThat(priorities.first()).isEqualTo(Process.THREAD_PRIORITY_URGENT_AUDIO)
    }

    /** Listeners may read the buffer but must not keep it. */
    @Test
    fun `the listener is handed the same buffer instance every frame`() {
        val latch = CountDownLatch(3)

        start(FakeCaptureSource(script = listOf(frame(1), frame(2), frame(3))), recorder(latch))
        assertThat(latch.await(SETTLE_MS, TimeUnit.MILLISECONDS)).isTrue()

        assertThat(identities.toSet()).hasSize(1)
    }

    /**
     * The capture buffer is reused, so after a short read the tail would still hold the previous
     * frame; that stale audio would be resent and could push the detector over its threshold.
     */
    @Test
    fun `a short read is padded instead of resending the previous frame's tail`() {
        val latch = CountDownLatch(2)
        val source = FakeCaptureSource(script = listOf(frame(20000), frame(300, length = 100)))

        start(source, recorder(latch))
        assertThat(latch.await(SETTLE_MS, TimeUnit.MILLISECONDS)).isTrue()

        val short = received[1]
        assertThat(short.size).isEqualTo(FRAME)
        // The whole frame is reported, since the encoder needs a complete one.
        assertThat(reportedLengths).containsExactly(FRAME, FRAME)
        assertThat(short.take(100)).containsExactlyElementsIn(List(100) { 300.toShort() })
        assertThat(short.drop(100).toSet()).containsExactly(0.toShort())

        val padded = VoiceActivityDetector.amplitudeScore(short, FRAME)
        val stale = VoiceActivityDetector.amplitudeScore(
            ShortArray(FRAME) { if (it < 100) 300 else 20000 }, FRAME,
        )
        assertThat(padded).isLessThan(START_THRESHOLD)
        assertThat(stale).isGreaterThan(START_THRESHOLD)
    }

    /** A read of 0 means the recorder is no longer running; delivering it would invent silence. */
    @Test
    fun `a read of zero samples delivers nothing`() {
        val latch = CountDownLatch(1)
        val source = FakeCaptureSource(script = listOf(Read.Code(0), Read.Code(0), frame(7)))

        start(source, recorder(latch))
        assertThat(latch.await(SETTLE_MS, TimeUnit.MILLISECONDS)).isTrue()

        assertThat(received).hasSize(1)
        assertThat(received.single()[0]).isEqualTo(7.toShort())
        assertThat(source.reads.get()).isAtLeast(3)
    }

    @Test
    fun `isRecording follows the intent to record`() {
        val source = FakeCaptureSource()
        val audioInput = AudioInput({ _, _ -> }, source)
        input = audioInput

        assertThat(audioInput.isRecording).isFalse()
        audioInput.startRecording()
        assertThat(audioInput.isRecording).isTrue()
        audioInput.stopRecording()
        assertThat(audioInput.isRecording).isFalse()
    }

    /** The source is stopped before the join, which is what makes the blocked read return. */
    @Test
    fun `stopRecording stops the source and the capture thread really exits`() {
        val source = FakeCaptureSource()
        val audioInput = start(source)
        waitUntil("the capture thread started") { "start" in source.events }

        val exited = audioInput.stopRecording()

        assertThat(exited).isTrue()
        assertThat(audioInput.isRecording).isFalse()
        assertThat(source.events.first()).isEqualTo("start")
        assertThat(source.events).contains("stop")
        assertThat(source.events).doesNotContain("release")
    }

    /**
     * A listener blocked waiting for push-to-talk must be woken so the loop reaches the source's
     * `stop()`; otherwise the microphone keeps running.
     */
    @Test
    fun `a listener blocked waiting for input is woken so the loop reaches the source's stop`() {
        val lock = ReentrantLock()
        val condition = lock.newCondition()
        val entered = CountDownLatch(1)
        val source = FakeCaptureSource(script = listOf(frame(1)))
        val audioInput = start(source, { _, _ ->
            lock.withLock {
                entered.countDown()
                try {
                    condition.await()
                } catch (e: InterruptedException) {
                    // Return on the interrupt, so the caller can unwind.
                }
            }
        })
        assertThat(entered.await(SETTLE_MS, TimeUnit.MILLISECONDS)).isTrue()

        val exited = audioInput.stopRecording()

        assertThat(exited).isTrue()
        assertThat(source.events.last()).isEqualTo("stop")
    }

    /** `false` means a capture thread is still alive on a source that is about to be released. */
    @Test
    fun `stopRecording gives up after the join timeout instead of hanging`() {
        val source = FakeCaptureSource(hangAfterStop = hung)
        val audioInput = start(source, joinTimeoutMs = 200)
        waitUntil("the capture thread is inside a read") { source.reads.get() > 0 }

        val exited = audioInput.stopRecording()

        assertThat(exited).isFalse()
        assertThat(audioInput.isRecording).isFalse()
        // Returned while the capture thread was still inside the read; a wall-clock bound would be flaky.
        assertThat(source.events.count { it == "stop" }).isEqualTo(1)
    }

    @Test
    fun `stopping something that never started reports that nothing is running`() {
        val audioInput = AudioInput({ _, _ -> }, FakeCaptureSource())
        input = audioInput

        assertThat(audioInput.stopRecording()).isTrue()
        assertThat(audioInput.stopRecording()).isTrue()
    }

    /** A second stop must not call `AudioRecord.stop()` again. */
    @Test
    fun `stopping twice does not touch the source a second time`() {
        val source = FakeCaptureSource()
        val audioInput = start(source)
        waitUntil("the capture thread started") { "start" in source.events }
        assertThat(audioInput.stopRecording()).isTrue()
        val afterFirst = source.events.toList()

        assertThat(audioInput.stopRecording()).isTrue()

        assertThat(source.events).containsExactlyElementsIn(afterFirst).inOrder()
    }

    @Test
    fun `an interrupted caller keeps its interrupt flag`() {
        val source = FakeCaptureSource(hangAfterStop = hung)
        val audioInput = start(source, joinTimeoutMs = 2000)
        waitUntil("the capture thread is inside a read") { source.reads.get() > 0 }

        Thread.currentThread().interrupt()
        val exited = audioInput.stopRecording()

        assertThat(Thread.interrupted()).isTrue()
        assertThat(exited).isFalse()
    }

    @Test
    fun `platform silencing is reported as capture states`() {
        val source = FakeCaptureSource()
        start(source)
        waitUntil("the silence listener was registered") { source.silenceListener != null }

        source.silenceListener!!.invoke(true)
        source.silenceListener!!.invoke(false)

        assertThat(states).containsExactly(CaptureState.Silenced, CaptureState.Active).inOrder()
    }

    @Test
    fun `the silence listener is unregistered when recording stops`() {
        val source = FakeCaptureSource()
        val audioInput = start(source)
        waitUntil("the silence listener was registered") { source.silenceListener != null }

        audioInput.stopRecording()

        assertThat(source.silenceListener).isNull()
    }

    @Test
    fun `a read error while recording is reported and ends the loop`() {
        val source = FakeCaptureSource(script = listOf(frame(1), Read.Code(-3)))
        start(source)
        waitUntil("the error was reported") { states.isNotEmpty() }

        val error = states.single()
        assertThat(error).isInstanceOf(CaptureState.Error::class.java)
        assertThat((error as CaptureState.Error).message).contains("-3")
        waitUntil("the loop stopped the source") { "stop" in source.events }
    }

    /**
     * A read in flight while stopping fails because the recorder was taken away. `AudioHandler`
     * forwards every [CaptureState.Error] to the chat log, so reporting it would warn on every
     * normal disconnect.
     */
    @Test
    fun `a read that fails while we are stopping is not reported`() {
        val source = FakeCaptureSource(codeAfterStop = AndroidAudioRecordSource.ERROR_RELEASED)
        val audioInput = start(source)
        waitUntil("the capture thread started") { "start" in source.events }

        assertThat(audioInput.stopRecording()).isTrue()

        assertThat(states).isEmpty()
    }

    @Test
    fun `a source that cannot start is reported instead of looping`() {
        val source = FakeCaptureSource(failStart = true)
        start(source)
        waitUntil("the error was reported") { states.isNotEmpty() }

        assertThat((states.single() as CaptureState.Error).message).contains("start")
        assertThat(received).isEmpty()
    }

    @Test
    fun `shutdown stops before it releases, and releases once`() {
        val source = FakeCaptureSource()
        val audioInput = start(source)
        waitUntil("the capture thread started") { "start" in source.events }

        assertThat(audioInput.shutdown()).isTrue()

        assertThat(source.events.count { it == "release" }).isEqualTo(1)
        assertThat(source.events.last()).isEqualTo("release")
        assertThat(source.events.indexOf("stop")).isLessThan(source.events.indexOf("release"))
    }

    /** The rate is read once at construction; the source must not be touched after release. */
    @Test
    fun `the rate and the frame size still answer after shutdown`() {
        val source = FakeCaptureSource(rate = 16000)
        val audioInput = AudioInput({ _, _ -> }, source)
        input = audioInput
        audioInput.startRecording()

        audioInput.shutdown()

        assertThrows(IllegalStateException::class.java) { source.sampleRate }
        assertThat(audioInput.sampleRate).isEqualTo(16000)
        assertThat(audioInput.frameSize).isEqualTo(160)
    }

    /**
     * Two capture threads on one source would interleave the recorder's frames. `isRecording`
     * alone is not enough: it is already false once a join has timed out.
     */
    @Test
    fun `starting again while the capture thread is alive is refused`() {
        val source = FakeCaptureSource()
        val audioInput = start(source)
        waitUntil("the capture thread started") { "start" in source.events }

        assertThrows(IllegalStateException::class.java) { audioInput.startRecording() }

        assertThat(source.events.count { it == "start" }).isEqualTo(1)
    }

    @Test
    fun `starting again is allowed once the timed-out thread has finished`() {
        val source = FakeCaptureSource(hangAfterStop = hung)
        val audioInput = start(source, joinTimeoutMs = 50)
        waitUntil("the capture thread is inside a read") { source.reads.get() > 0 }
        assertThat(audioInput.stopRecording()).isFalse()
        hung.countDown()

        waitUntil("the stale capture thread finished") { source.events.count { it == "stop" } >= 2 }
        audioInput.startRecording()

        waitUntil("the second capture thread started") { source.events.count { it == "start" } == 2 }
    }

    /** `stopRecording` answered false and `isRecording` is false, yet the old thread is alive. */
    @Test
    fun `starting again while a timed-out capture thread is still alive is refused`() {
        val source = FakeCaptureSource(hangAfterStop = hung)
        val audioInput = start(source, joinTimeoutMs = 50)
        waitUntil("the capture thread is inside a read") { source.reads.get() > 0 }
        assertThat(audioInput.stopRecording()).isFalse()
        assertThat(audioInput.isRecording).isFalse()

        assertThrows(IllegalStateException::class.java) { audioInput.startRecording() }

        assertThat(source.events.count { it == "start" }).isEqualTo(1)
    }

    /** `AudioHandler` uses this to decide whether freeing the native capture chain is safe. */
    @Test
    fun `shutdown reports that the capture thread did not exit`() {
        val source = FakeCaptureSource(hangAfterStop = hung)
        val audioInput = start(source, joinTimeoutMs = 50)
        waitUntil("the capture thread is inside a read") { source.reads.get() > 0 }

        assertThat(audioInput.shutdown()).isFalse()

        assertThat(source.events).contains("release")
    }
}
