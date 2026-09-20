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

    }

    /** A stage with no reverse stream, i.e. every stage but the APM one. */
    private class CaptureOnlyStage : SingleHandleStage(HANDLE, "capture-only stage") {
        override fun onCaptureFrame(handle: Long, frame: ShortArray): Float? = null
        override fun onReleaseHandle(handle: Long) = Unit
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
     * place is Kotlin-side -- one handle, in one private field, of one owner.
     *
     * Three things this had to stop claiming, each of which made it weaker than it read:
     *
     * - `declaredFields` and `declaredMethods` see no **inherited** member, so a check run on one
     *   class object is a statement about that class object, not about a stage. It goes vacuous
     *   the day an intermediate class is inserted between a stage and this base -- which is why
     *   the whole chain up from each stage class is walked, and why every stage class this file
     *   builds is in the list. A stage from task 5 or 6 belongs here too, or in the same walk in
     *   its own suite.
     * - `type == Long::class.javaPrimitiveType` sees `long` and not `java.lang.Long`, so a second,
     *   boxed handle field would have left "exactly one field" green.
     * - A return type of `long` is not the only way out. `protected fun copyHandleInto(sink:
     *   LongArray)` returns `Unit` and hands the handle to every subclass all the same. So the
     *   demand is that no member **mentions** a long -- returned, taken, or inside an array --
     *   except the three callbacks, which is how the handle is meant to travel: as an argument,
     *   with the lock already held.
     *
     * What it still does not cover, because nothing in this package can: the handle arrives from
     * the subclass through the constructor, so a subclass may keep the value it passed up. See the
     * class KDoc for why creating it in the base was rejected rather than forgotten.
     */
    @Test
    fun `the native handle never escapes its owner`() {
        val hierarchy = listOf(SingleHandleStage::class.java, TestStage::class.java, CaptureOnlyStage::class.java)
            .flatMap { generateSequence(it as Class<*>) { c -> c.superclass }.takeWhile { c -> c != Any::class.java } }
            .distinct()
        assertWithMessage("the walk must reach the base class itself")
            .that(hierarchy).contains(SingleHandleStage::class.java)

        val longFields = hierarchy.flatMap { it.declaredFields.asList() }
            .filter { !it.isSynthetic && mentionsLong(it.type) }
        assertWithMessage("the handle must live in exactly one field")
            .that(longFields.map { "${it.declaringClass.simpleName}.${it.name}" }).hasSize(1)
        assertWithMessage("the one handle field must be private")
            .that(Modifier.isPrivate(longFields.single().modifiers)).isTrue()

        val handleBearing = hierarchy.flatMap { it.declaredMethods.asList() }
            .filter { !it.isSynthetic && !it.isBridge && !Modifier.isPrivate(it.modifiers) }
            .filter { m -> mentionsLong(m.returnType) || m.parameterTypes.any { mentionsLong(it) } }
        assertWithMessage("only the three callbacks, which run with the lock held, may carry the handle")
            .that(handleBearing.map { it.name }.distinct())
            .containsExactly("onCaptureFrame", "onFarEndFrame", "onReleaseHandle")
    }

    /** `long`, `java.lang.Long`, or an array of either -- an out-parameter is an escape hatch too. */
    private fun mentionsLong(type: Class<*>): Boolean = when {
        type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> true
        type.isArray -> mentionsLong(type.componentType!!)
        else -> false
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

    /**
     * Unpinned and, at a cost worth paying, unpinnable: that clearing the field and calling the
     * free are **atomic** against the other two entry points. Every observation a test can make is
     * taken through [SingleHandleStage.process] or [SingleHandleStage.analyzeReverseStream], and
     * both take the same lock, so a test thread cannot be scheduled into the gap it would have to
     * see. Only a stage that exposed its own state outside the lock could show it -- which is the
     * thing this class exists to prevent. Recorded here rather than counted as covered by the four
     * blocking tests above, which pin mutual exclusion and not atomicity.
     */
    @Test
    fun `release frees exactly once`() {
        val stage = TestStage()

        stage.release()
        stage.release()

        assertThat(stage.releasedHandles).containsExactly(HANDLE)
    }

    /**
     * The order inside [SingleHandleStage.release] -- clear the field, then free -- survives its
     * own reversal only when the free cannot fail. Reversed, a throwing free leaves the field set
     * and the stage keeps handing a freed handle to the bridge on every following frame, which is
     * the use-after-free `HandleTable::get()` does not check for.
     *
     * This also pins that [SingleHandleStage.release] is not idempotent by swallowing: the throw
     * reaches the caller, and the stage is released all the same.
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

    /**
     * A stage with no reverse stream must say so rather than swallow the reference signal. A
     * silently dropped far-end frame costs about 21 dB of echo cancellation and is invisible in
     * every test above this layer (measured in task 2, `tests/test_apm.c`).
     */
    @Test
    fun `a stage without a far-end path refuses the reverse stream instead of dropping it`() {
        assertThrows(UnsupportedOperationException::class.java) {
            CaptureOnlyStage().analyzeReverseStream(ShortArray(FRAME))
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
     * The generalisation of the four tests above: *every* entry point has to wait for the one lock,
     * not just the three that exist today. An entry point a later task adds -- a mode switch, a
     * delay hint, a reset -- fails here until someone decides what it does while an audio thread is
     * inside the native call.
     *
     * The set is the **public surface of the class**, not only the members of the two interfaces.
     * Iterating the interfaces alone is what a public `fun setStreamDelayMs(ms: Int)` added
     * straight onto [SingleHandleStage] slips past -- and that is not hypothetical: the shipped
     * `WebRtcApmApi` carries an extra `frameSize(handle)`, the APM needs `set_stream_delay_ms`, and
     * task 6 is the task that adds it. A method that belongs to no interface is exactly the one
     * nobody remembers to lock.
     *
     * Protected members are deliberately out: [onCaptureFrame], [onFarEndFrame] and
     * [onReleaseHandle] are the callbacks the lock is already held for, and calling them from here
     * would be calling them the one way no caller ever does.
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
     * The window one audio thread is held inside the native call, plus what every arrival in a
     * callback saw of it.
     *
     * The timing half alone -- "`done` did not count down within 250 ms" -- measures when a latch
     * fell, not whether the second thread was inside the native call. It reads green on an
     * overloaded machine with a broken lock, and worse, it reads green for a *correct* reason on
     * any entry point whose body ends up blocked on this fixture's own latch rather than on the
     * lock. Measured: a public, unlocked `setStreamDelayMs` routed through `onCaptureFrame`
     * survives the timing assertion for exactly that reason. [record] is the causal half -- every
     * arrival after the first one must find the window already closed -- and it kills that mutant.
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

        // The causal half. The first arrival is the holder, which is inside by definition; every
        // later one belongs to $what and must have found the window closed. An entry point that
        // reaches no callback at all leaves nothing to check here -- the timing assertion above is
        // what covers those -- so this strengthens the test rather than replacing it.
        assertWithMessage("the holder must have arrived while the window was open")
            .that(window.arrivals.firstOrNull()).isFalse()
        assertWithMessage("$what was inside the stage's callbacks while an audio thread held the lock")
            .that(window.arrivals.drop(1)).doesNotContain(false)
    }
}
