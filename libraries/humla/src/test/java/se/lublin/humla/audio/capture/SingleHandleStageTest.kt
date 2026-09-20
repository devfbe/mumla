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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The lock contract of [SingleHandleStage], which is what spec §4.1 "One lock across both audio
 * streams" and "Native handles are not interchangeable" come down to in Kotlin.
 *
 * The blocking assertions are one-sided on purpose: a call that is *not* supposed to get through
 * is given 250 ms to prove it, and a call that *is* supposed to get through is given 5 s. Nothing
 * here fails because a machine was slow; it fails only when a call that should have waited did not.
 */
class SingleHandleStageTest {
    private companion object {
        const val HANDLE = 0x0A11L
        const val FRAME = 480
        /** Long enough that a scheduler hiccup cannot fake it, short enough to pay for 4+ times. */
        const val MUST_STAY_BLOCKED_MS = 250L
    }

    private open class TestStage(
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

        override fun analyzeReverseStream(frame: ShortArray) = farEnd(frame)
    }

    /** A stage with no reverse stream, i.e. every stage but the APM one. */
    private class CaptureOnlyStage : SingleHandleStage(HANDLE, "capture-only stage") {
        override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? = null
        override fun onReleaseHandle(handle: Long) = Unit
        fun feedFarEnd(frame: ShortArray) = farEnd(frame)
    }

    // ---------------------------------------------------------------- handle ownership

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

    /**
     * Spec §4.1: a handle may only be passed back to the bridge that issued it, and the checkable
     * place is Kotlin-side -- one handle, in one private field, of one owner. This pins the *set*:
     * not "the field is private today" but "there is no way at all to get the value out of the
     * base class except as a callback argument". A `protected val handle` or a `fun handle()`
     * added by a later task fails here, because that is the moment a second copy can start living
     * in a subclass and be handed to the wrong bridge.
     */
    @Test
    fun `the native handle never escapes its owner`() {
        val longFields = SingleHandleStage::class.java.declaredFields
            .filter { !it.isSynthetic && it.type == Long::class.javaPrimitiveType }
        assertWithMessage("the handle must live in exactly one field")
            .that(longFields.map { it.name }).hasSize(1)
        assertWithMessage("the one handle field must be private")
            .that(Modifier.isPrivate(longFields.single().modifiers)).isTrue()

        val escapeHatches = SingleHandleStage::class.java.declaredMethods
            .filter { !it.isSynthetic && !it.isBridge && !Modifier.isPrivate(it.modifiers) }
            .filter { it.returnType == Long::class.javaPrimitiveType || it.returnType == java.lang.Long::class.java }
        assertWithMessage("no member of SingleHandleStage may hand the handle out")
            .that(escapeHatches.map { it.name }).isEmpty()
    }

    @Test
    fun `construction fails when the native side handed out no handle`() {
        val failure = assertThrows(IllegalStateException::class.java) { TestStage(handle = 0L) }
        assertThat(failure).hasMessageThat().contains("test stage")
    }

    // ---------------------------------------------------------------- life cycle

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
     * A stage with no reverse stream must say so rather than swallow the reference signal. A
     * silently dropped far-end frame costs about 21 dB of echo cancellation and is invisible in
     * every test above this layer (measured in task 2, `tests/test_apm.c`).
     */
    @Test
    fun `a stage without a far-end path refuses the reverse stream instead of dropping it`() {
        assertThrows(UnsupportedOperationException::class.java) {
            CaptureOnlyStage().feedFarEnd(ShortArray(FRAME))
        }
    }

    // ---------------------------------------------------------------- one lock, both streams

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
     * The generalisation of the four tests above: *every* entry point of the two stage interfaces
     * has to wait for the one lock, not just the three that exist today. An entry point a later
     * task adds -- a mode switch, a delay hint, a reset -- fails here until someone decides what it
     * does while an audio thread is inside the native call.
     */
    @Test
    fun `every entry point of the stage interfaces takes the one lock`() {
        val entryPoints = (CapturePreprocessor::class.java.declaredMethods +
            FarEndSink::class.java.declaredMethods)
            .filter { !it.isSynthetic && !it.isBridge }
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
        val inside = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val stage = TestStage(onCapture = { inside.countDown(); letGo.await() })
        assertBlockedUntilLetGo(what, stage, inside, letGo, { it.process(ShortArray(FRAME)) }, call)
    }

    private fun assertWaitsForFarEnd(what: String, call: (TestStage) -> Unit) {
        val inside = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val stage = TestStage(onFarEnd = { inside.countDown(); letGo.await() })
        assertBlockedUntilLetGo(what, stage, inside, letGo, { it.analyzeReverseStream(ShortArray(FRAME)) }, call)
    }

    private fun assertBlockedUntilLetGo(
        what: String,
        stage: TestStage,
        inside: CountDownLatch,
        letGo: CountDownLatch,
        hold: (TestStage) -> Unit,
        call: (TestStage) -> Unit,
    ) {
        val error = AtomicReference<Throwable>()
        val holder = thread(name = "holder") { hold(stage) }
        assertWithMessage("the holding thread never reached the native call").that(inside.await(5, SECONDS)).isTrue()

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

        letGo.countDown()
        assertWithMessage("$what never completed after the lock was dropped")
            .that(done.await(5, SECONDS)).isTrue()
        holder.join()
        caller.join()
        error.get()?.let { fail("$what failed: $it") }
    }
}
