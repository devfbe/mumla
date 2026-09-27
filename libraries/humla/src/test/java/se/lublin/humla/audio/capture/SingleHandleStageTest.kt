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

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The lock contract of [SingleHandleStage]: one lock across both audio streams, and the native
 * handle never leaves its owner.
 *
 * Blocking assertions are one-sided: a call that must wait gets 250 ms to prove it, a call that
 * must get through gets 5 s, so a slow machine cannot cause a failure.
 */
class SingleHandleStageTest {
    private companion object {
        const val HANDLE = 0x0A11L
        const val FRAME = 480
        /** Long enough that a scheduler hiccup cannot fake it, short enough to pay for 4+ times. */
        const val MUST_STAY_BLOCKED_MS = 250L
    }

    internal open class TestStage(
        handle: Long = HANDLE,
        private val probability: Float? = 0.5f,
        private val onCapture: () -> Unit = {},
        private val onFarEnd: () -> Unit = {},
        private val onRelease: () -> Unit = {},
    ) : SingleHandleStage(handle, "test stage"), FarEndSink {
        val captureHandles = mutableListOf<Long>()
        val farEndHandles = mutableListOf<Long>()
        val releasedHandles = mutableListOf<Long>()

        override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? {
            captureHandles += handle
            onCapture()
            return probability
        }

        override fun onFarEndFrame(handle: Long, frame: ShortArray) {
            farEndHandles += handle
            onFarEnd()
        }

        override fun onReleaseHandle(handle: Long) {
            releasedHandles += handle
            onRelease()
        }

    }

    /** A stage with no reverse stream, i.e. every stage but the APM one. */
    internal class CaptureOnlyStage : SingleHandleStage(HANDLE, "capture-only stage") {
        override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? = null
        override fun onReleaseHandle(handle: Long) = Unit
    }

    @Test
    fun `every callback gets exactly the handle the stage was built with`() {
        val stage = TestStage(handle = 0xBEEFL)

        stage.process(ShortArray(FRAME))
        stage.analyzeReverseStream(ShortArray(FRAME))
        stage.release()

        assertThat(stage.captureHandles).containsExactly(0xBEEFL)
        assertThat(stage.farEndHandles).containsExactly(0xBEEFL)
        assertThat(stage.releasedHandles).containsExactly(0xBEEFL)
    }

    @Test
    fun `construction fails when the native side handed out no handle`() {
        val failure = assertThrows(IllegalStateException::class.java) { TestStage(handle = 0L) }
        assertThat(failure).hasMessageThat().contains("test stage")
    }

    @Test
    fun `capture frames after release touch nothing and have no opinion`() {
        val stage = TestStage()
        stage.release()

        assertThat(stage.process(ShortArray(FRAME))).isNull()

        assertThat(stage.captureHandles).isEmpty()
    }

    @Test
    fun `far-end frames after release touch nothing`() {
        val stage = TestStage()
        stage.release()

        stage.analyzeReverseStream(ShortArray(FRAME))

        assertThat(stage.farEndHandles).isEmpty()
    }

    @Test
    fun `release frees exactly once`() {
        val stage = TestStage()

        stage.release()
        stage.release()

        assertThat(stage.releasedHandles).containsExactly(HANDLE)
    }

    /**
     * The field is cleared before the free, so a throwing free still leaves the stage released and
     * the freed handle is never handed to the bridge again. The throw still reaches the caller.
     */
    @Test
    fun `a release whose native free throws still leaves the stage released`() {
        val stage = TestStage(onRelease = { throw IllegalStateException("native free failed") })

        assertThrows(IllegalStateException::class.java) { stage.release() }

        assertThat(stage.process(ShortArray(FRAME))).isNull()
        assertThat(stage.captureHandles).isEmpty()
        stage.analyzeReverseStream(ShortArray(FRAME))
        assertThat(stage.farEndHandles).isEmpty()
    }

    /** A silently dropped far-end frame would cost about 21 dB of echo cancellation. */
    @Test
    fun `a stage without a far-end path refuses the reverse stream instead of dropping it`() {
        assertThrows(UnsupportedOperationException::class.java) {
            CaptureOnlyStage().analyzeReverseStream(ShortArray(FRAME))
        }
    }

    @Test
    fun `the render path waits while the capture path is inside the native call`() {
        assertWaitsForCapture("render") { it.analyzeReverseStream(ShortArray(FRAME)) }
    }

    @Test
    fun `release waits while the capture path is inside the native call`() {
        assertWaitsForCapture("release") { it.release() }
    }

    @Test
    fun `the capture path waits while the render path is inside the native call`() {
        assertWaitsForFarEnd("capture") { it.process(ShortArray(FRAME)) }
    }

    @Test
    fun `release waits while the render path is inside the native call`() {
        assertWaitsForFarEnd("release") { it.release() }
    }

    /**
     * Every public entry point, not just the interface members, must wait for the one lock, so a
     * method added later fails here until its locking is decided. Protected callbacks are excluded:
     * the lock is already held when they run.
     */
    @Test
    fun `every entry point of the stage takes the one lock`() {
        val entryPoints = (CapturePreprocessor::class.java.declaredMethods +
            FarEndSink::class.java.declaredMethods +
            SingleHandleStage::class.java.declaredMethods.filter { Modifier.isPublic(it.modifiers) })
            .filter { !it.isSynthetic && !it.isBridge && !Modifier.isStatic(it.modifiers) }
            .distinctBy { it.name to it.parameterTypes.toList() }
        assertThat(entryPoints.map { it.name })
            .containsAtLeast("process", "release", "analyzeReverseStream")

        for (entryPoint in entryPoints) {
            assertWaitsForCapture(entryPoint.name) { stage -> invoke(entryPoint, stage) }
        }
    }

    private fun invoke(entryPoint: Method, stage: TestStage) {
        val args = entryPoint.parameterTypes.map { argumentFor(entryPoint, it) }.toTypedArray()
        entryPoint.invoke(stage, *args)
    }

    private fun argumentFor(entryPoint: Method, type: Class<*>): Any = when (type) {
        ShortArray::class.java -> ShortArray(FRAME)
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Boolean::class.javaPrimitiveType -> false
        else -> throw AssertionError(
            "${entryPoint.name} takes a ${type.name}; decide whether that entry point holds the " +
                "one lock, then teach this test how to build the argument"
        )
    }

    private fun assertWaitsForCapture(what: String, call: (TestStage) -> Unit) {
        val w = Window()
        val stage = TestStage(
            onCapture = { w.record(); w.holdHere() },
            onFarEnd = { w.record() },
            onRelease = { w.record() },
        )
        assertBlockedUntilLetGo(what, stage, w, { it.process(ShortArray(FRAME)) }, call)
    }

    private fun assertWaitsForFarEnd(what: String, call: (TestStage) -> Unit) {
        val w = Window()
        val stage = TestStage(
            onFarEnd = { w.record(); w.holdHere() },
            onCapture = { w.record() },
            onRelease = { w.record() },
        )
        assertBlockedUntilLetGo(what, stage, w, { it.analyzeReverseStream(ShortArray(FRAME)) }, call)
    }

    /**
     * Holds one audio thread inside the native call and records, per callback arrival, whether the
     * window was already closed. The timing check alone could pass for an entry point blocked on
     * this fixture's latch rather than on the lock; [record] is the causal check.
     */
    private class Window {
        private val inside = CountDownLatch(1)
        private val letGo = CountDownLatch(1)
        /** Was the window already closed when this callback was entered? One entry per arrival. */
        val arrivals = CopyOnWriteArrayList<Boolean>()

        fun record() {
            arrivals += letGo.count == 0L
        }

        /** Only the first arrival holds; the caller is started only after [awaitHolder] returned. */
        fun holdHere() {
            if (inside.count > 0L) {
                inside.countDown()
                letGo.await()
            }
        }

        fun awaitHolder() = inside.await(5, SECONDS)
        fun close() = letGo.countDown()
    }

    private fun assertBlockedUntilLetGo(
        what: String,
        stage: TestStage,
        window: Window,
        hold: (TestStage) -> Unit,
        call: (TestStage) -> Unit,
    ) {
        val error = AtomicReference<Throwable>()
        val holder = thread(name = "holder") { hold(stage) }
        assertWithMessage("the holding thread never reached the native call").that(window.awaitHolder()).isTrue()

        val about = CountDownLatch(1)
        val done = CountDownLatch(1)
        val caller = thread(name = "caller") {
            about.countDown()
            try {
                call(stage)
            } catch (t: Throwable) {
                error.set(t)
            }
            done.countDown()
        }
        assertThat(about.await(5, SECONDS)).isTrue()

        assertWithMessage("$what got through while an audio thread was inside the native call")
            .that(done.await(MUST_STAY_BLOCKED_MS, MILLISECONDS)).isFalse()

        window.close()
        assertWithMessage("$what never completed after the lock was dropped")
            .that(done.await(5, SECONDS)).isTrue()
        holder.join()
        caller.join()
        error.get()?.let { fail("$what failed: $it") }

        // The first arrival is the holder; every later one belongs to $what and must have found the
        // window closed. Entry points that reach no callback are covered by the timing check above.
        assertWithMessage("the holder must have arrived while the window was open")
            .that(window.arrivals.firstOrNull()).isFalse()
        assertWithMessage("$what was inside the stage's callbacks while an audio thread held the lock")
            .that(window.arrivals.drop(1)).doesNotContain(false)
    }
}
