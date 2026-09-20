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
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.CaptureState
import se.lublin.humla.audio.capture.VoiceActivityDetector
import se.lublin.humla.audio.capture.fakes.FakeCaptureSource
import se.lublin.humla.audio.capture.fakes.FakeCaptureSource.Read
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Spec B7 and B8. `AudioInput` owns the one bare thread the audio path is allowed and does nothing
 * else: it pumps 10 ms frames out of a [se.lublin.humla.audio.capture.PcmCaptureSource] and it
 * shuts that thread down without hanging the caller.
 */
@RunWith(RobolectricTestRunner::class)
class AudioInputTest {
    private companion object {
        const val FRAME = 480
        const val SETTLE_MS = 2000L

        /** Spec B5's default start threshold for the amplitude detector. */
        const val START_THRESHOLD = 0.6f
    }

    private val received = CopyOnWriteArrayList<ShortArray>()
    private val reportedLengths = CopyOnWriteArrayList<Int>()
    private val identities = CopyOnWriteArrayList<ShortArray>()
    private val listenerThreads = CopyOnWriteArrayList<Thread>()
    private val priorities = CopyOnWriteArrayList<Int>()
    private val states = CopyOnWriteArrayList<CaptureState>()
    private var input: AudioInput? = null

    @After
    fun tearDown() {
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

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + SETTLE_MS
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(2)
        assertWithMessage("waited %s ms for: %s", SETTLE_MS, what).that(condition()).isTrue()
    }

    // ------------------------------------------------------------------ the frame path

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

    /**
     * By identity, never by name: `kotlinx.coroutines` renames threads while they run a coroutine,
     * and a name assertion passes against the very thread it means to exclude (spec 4.05).
     */
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

    /** Spec section 2 gives the capture thread urgent audio priority; nothing else reads it back. */
    @Test
    fun `the capture thread runs at urgent audio priority`() {
        val latch = CountDownLatch(1)

        start(FakeCaptureSource(script = listOf(frame(1))), recorder(latch))
        assertThat(latch.await(SETTLE_MS, TimeUnit.MILLISECONDS)).isTrue()

        assertThat(priorities.first()).isEqualTo(Process.THREAD_PRIORITY_URGENT_AUDIO)
    }

    /**
     * The same array every frame, like `CaptureFrame.samples` one stage further on. Task 11 reads
     * it and may not keep it.
     */
    @Test
    fun `the listener is handed the same buffer instance every frame`() {
        val latch = CountDownLatch(3)

        start(FakeCaptureSource(script = listOf(frame(1), frame(2), frame(3))), recorder(latch))
        assertThat(latch.await(SETTLE_MS, TimeUnit.MILLISECONDS)).isTrue()

        assertThat(identities.toSet()).hasSize(1)
    }

    /**
     * **The reused buffer, and the reason this file pads.** The capture buffer is allocated once
     * outside the loop, so after a short read the tail still holds the previous frame -- audio the
     * microphone already sent, going out a second time, and measured by the detector a second time.
     *
     * Measured here through the consumer rather than asserted as a shape: 100 quiet samples (300)
     * behind a full frame of 20 000 score **0.9448** over the stale tail and **0.5044** once the
     * tail is zeroed, against spec B5's default start threshold of 0.6. Same 10 ms of microphone,
     * transmitting or not depending on one `fill`.
     */
    @Test
    fun `a short read is padded instead of resending the previous frame's tail`() {
        val latch = CountDownLatch(2)
        val source = FakeCaptureSource(script = listOf(frame(20000), frame(300, length = 100)))

        start(source, recorder(latch))
        assertThat(latch.await(SETTLE_MS, TimeUnit.MILLISECONDS)).isTrue()

        val short = received[1]
        assertThat(short.size).isEqualTo(FRAME)
        // The padding is the point: the count handed over is the whole frame, not the read count,
        // because the encoder one stage further on needs a complete one (`CaptureFrame.length`
        // makes the same choice and says so).
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

    /**
     * A blocking read answers 0 when it has nothing, which for `AudioRecord` means the recorder is
     * no longer running. Delivering it would hand the encoder and the detector 10 ms of silence
     * that the microphone never produced, once per turn of the loop.
     */
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

    // ------------------------------------------------------------------ spec B8, stopping

    @Test
    fun `isRecording follows the intent to record`() {
        val source = FakeCaptureSource()
        val audioInput = AudioInput({ _, _ -> }, source)
        input = audioInput

        assertThat(audioInput.isRecording()).isFalse()
        audioInput.startRecording()
        assertThat(audioInput.isRecording()).isTrue()
        audioInput.stopRecording()
        assertThat(audioInput.isRecording()).isFalse()
    }

    /**
     * The source is stopped **before** the join, which is what makes the blocked read return. Join
     * first and this test times out instead of passing.
     */
    @Test
    fun `stopRecording stops the source and the capture thread really exits`() {
        val source = FakeCaptureSource()
        val audioInput = start(source)
        waitUntil("the capture thread started") { "start" in source.events }

        val exited = audioInput.stopRecording()

        assertThat(exited).isTrue()
        assertThat(audioInput.isRecording()).isFalse()
        assertThat(source.events.first()).isEqualTo("start")
        assertThat(source.events).contains("stop")
        assertThat(source.events).doesNotContain("release")
    }

    /**
     * **The capture thread has to reach the source's own `stop()`, not merely die.** This is the
     * property `ToggleInputMode.waitForInput` catches its `InterruptedException` for: a listener
     * blocked waiting for push-to-talk must be woken so the loop can unwind, or the thread stays
     * parked, `AudioRecord.stop()` is never called, the microphone keeps running and the recording
     * icon stays lit. The listener here blocks exactly the way that one does.
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
                    // As ToggleInputMode.waitForInput does: return, so the caller can unwind.
                }
            }
        })
        assertThat(entered.await(SETTLE_MS, TimeUnit.MILLISECONDS)).isTrue()

        val exited = audioInput.stopRecording()

        assertThat(exited).isTrue()
        assertThat(source.events.last()).isEqualTo("stop")
    }

    /**
     * Spec B8: a join timeout logs and gives up rather than hanging the caller. The answer is the
     * one `AudioHandler` needs -- `false` means a capture thread is still alive on a source that is
     * about to be released.
     */
    @Test
    fun `stopRecording gives up after the join timeout instead of hanging`() {
        val source = FakeCaptureSource(hangAfterStopMs = 1500)
        val audioInput = start(source, joinTimeoutMs = 200)
        waitUntil("the capture thread started") { "start" in source.events }

        val started = System.nanoTime()
        val exited = audioInput.stopRecording()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertThat(exited).isFalse()
        assertThat(elapsedMs).isLessThan(1000)
        assertThat(audioInput.isRecording()).isFalse()
    }

    /** Nothing to join is not a failure to join. */
    @Test
    fun `stopping something that never started reports that nothing is running`() {
        val audioInput = AudioInput({ _, _ -> }, FakeCaptureSource())
        input = audioInput

        assertThat(audioInput.stopRecording()).isTrue()
        assertThat(audioInput.stopRecording()).isTrue()
    }

    /**
     * The Java original called `e.printStackTrace()` and swallowed the interrupt, which loses the
     * flag for every caller above it -- a shutdown path that is itself being cancelled then looks
     * to its own caller as if it had not been.
     */
    @Test
    fun `an interrupted caller keeps its interrupt flag`() {
        val source = FakeCaptureSource(hangAfterStopMs = 1500)
        val audioInput = start(source, joinTimeoutMs = 2000)
        waitUntil("the capture thread started") { "start" in source.events }

        Thread.currentThread().interrupt()
        val exited = audioInput.stopRecording()

        assertThat(Thread.interrupted()).isTrue()
        assertThat(exited).isFalse()
    }

    // ------------------------------------------------------------------ spec B7, silencing

    @Test
    fun `platform silencing is reported as capture states`() {
        val source = FakeCaptureSource()
        start(source)
        waitUntil("the silence listener was registered") { source.silenceListener != null }

        source.silenceListener!!.invoke(true)
        source.silenceListener!!.invoke(false)

        assertThat(states).containsExactly(CaptureState.Silenced, CaptureState.Active).inOrder()
    }

    /** A listener left on a stopped source outlives the thread that gave it a meaning. */
    @Test
    fun `the silence listener is unregistered when recording stops`() {
        val source = FakeCaptureSource()
        val audioInput = start(source)
        waitUntil("the silence listener was registered") { source.silenceListener != null }

        audioInput.stopRecording()

        assertThat(source.silenceListener).isNull()
    }

    // ------------------------------------------------------------------ errors

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
     * A read racing a shutdown answers a negative code because the recorder is gone, which is not
     * news: `AudioHandler` forwards every [CaptureState.Error] to the chat log (stream A8), so
     * reporting this one means an error toast on every normal disconnect.
     */
    @Test
    fun `a read error after recording was stopped is not reported`() {
        val source = FakeCaptureSource()
        val audioInput = start(source)
        waitUntil("the capture thread started") { "start" in source.events }

        audioInput.shutdown()
        Thread.sleep(50)

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

    // ------------------------------------------------------------------ shutdown

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

    /**
     * The rate is read once, at construction. The Java original's getter dereferenced the
     * `AudioRecord` field that `shutdown()` had just set to null; reading it off a released
     * recorder is the same bug with a native crash instead of a `NullPointerException`.
     */
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

    // ------------------------------------------------------------------ starting twice

    /**
     * Two capture threads on one source read the same recorder in alternation and hand the encoder
     * interleaved halves of the conversation. `AudioHandler` guards its own call with
     * `isRecording()`, but that flag is about intent and answers `false` the moment a join has
     * timed out -- which is exactly when a second thread is the most dangerous.
     */
    @Test
    fun `starting again while the capture thread is alive is refused`() {
        val source = FakeCaptureSource()
        val audioInput = start(source)
        waitUntil("the capture thread started") { "start" in source.events }

        assertThrows(IllegalStateException::class.java) { audioInput.startRecording() }

        assertThat(source.events.count { it == "start" }).isEqualTo(1)
    }

    /** Once the thread that outlived the timeout is gone, the object is usable again. */
    @Test
    fun `starting again is allowed once the timed-out thread has finished`() {
        val source = FakeCaptureSource(hangAfterStopMs = 300)
        val audioInput = start(source, joinTimeoutMs = 50)
        waitUntil("the capture thread started") { "start" in source.events }
        assertThat(audioInput.stopRecording()).isFalse()

        waitUntil("the stale capture thread finished") { source.events.count { it == "stop" } >= 2 }
        audioInput.startRecording()

        waitUntil("the second capture thread started") { source.events.count { it == "start" } == 2 }
    }

    /**
     * The dangerous half of the same rule. `stopRecording` has just answered `false`, `isRecording`
     * is already false, and the thread it gave up on is still inside a read -- so a caller that
     * trusts either of those two answers gets the second capture thread this refuses.
     */
    @Test
    fun `starting again while a timed-out capture thread is still alive is refused`() {
        val source = FakeCaptureSource(hangAfterStopMs = 1500)
        val audioInput = start(source, joinTimeoutMs = 50)
        waitUntil("the capture thread started") { "start" in source.events }
        assertThat(audioInput.stopRecording()).isFalse()
        assertThat(audioInput.isRecording()).isFalse()

        assertThrows(IllegalStateException::class.java) { audioInput.startRecording() }

        assertThat(source.events.count { it == "start" }).isEqualTo(1)
    }

    /**
     * What `AudioHandler` reads to decide whether freeing the native capture chain is safe: the
     * recorder is released either way (spec B8), the rest of the pipeline is not.
     */
    @Test
    fun `shutdown reports that the capture thread did not exit`() {
        val source = FakeCaptureSource(hangAfterStopMs = 1500)
        val audioInput = start(source, joinTimeoutMs = 50)
        waitUntil("the capture thread started") { "start" in source.events }

        assertThat(audioInput.shutdown()).isFalse()

        assertThat(source.events).contains("release")
    }
}
