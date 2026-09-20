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
import org.junit.Test
import java.lang.reflect.Modifier
import se.lublin.humla.audio.capture.fakes.FakeRnnoiseApi

/** Spec B4: RNNoise as one capture stage -- 480-sample frames at 48 kHz, its model's probability. */
class RnnoisePreprocessorTest {
    private companion object {
        const val FRAME = 480
    }

    private val api = FakeRnnoiseApi()

    // ------------------------------------------------------------------ construction

    @Test
    fun `construction creates exactly one denoiser`() {
        RnnoisePreprocessor(api)

        assertThat(api.created).isEqualTo(1)
    }

    @Test
    fun `construction fails loudly when rnnoise cannot allocate`() {
        api.failCreate = true

        val failure = assertThrows(IllegalStateException::class.java) { RnnoisePreprocessor(api) }

        assertThat(failure).hasMessageThat().contains("rnnoise")
    }

    // ------------------------------------------------------------------ the frame path

    @Test
    fun `process denoises in place and reports the model probability`() {
        val api = FakeRnnoiseApi(probability = 0.87f, onProcess = { it.fill(7) })
        val frame = ShortArray(FRAME) { 1000 }

        val probability = RnnoisePreprocessor(api).process(frame)

        assertThat(probability).isEqualTo(0.87f)
        assertThat(frame[FRAME - 1]).isEqualTo(7.toShort())
        assertThat(api.processedLengths).containsExactly(FRAME)
    }

    /**
     * The upper end only. `rnnoise_process_frame` is documented to answer `[0, 1]`, so a value
     * above 1 is a broken build rather than an input -- but task 7 compares this number against a
     * threshold, and a 1.4 that survives is a frame nothing can ever gate.
     *
     * The lower end is deliberately **not** clamped, and that asymmetry is the point: -1 is the
     * bridge's refusal sentinel, so clamping it to 0 would turn "rnnoise never saw this frame"
     * into "certainly not speech" and mute the user. See the next test.
     */
    @Test
    fun `a probability above one is clamped rather than propagated`() {
        assertThat(RnnoisePreprocessor(FakeRnnoiseApi(probability = 1.4f)).process(ShortArray(FRAME)))
            .isEqualTo(1f)
    }

    /**
     * The bridge refuses a frame shorter than 480 samples with -1 rather than letting rnnoise read
     * past the array, so -1 is reachable from a wrong-sized capture buffer and not only from a
     * broken build.
     *
     * Spec §4.1, "do not throw from the capture thread": the plan's own listing wanted an
     * `IllegalArgumentException` here. That is an exception once per 10 ms frame on the audio
     * thread, and an uncaught one kills the capture thread -- the user goes silent with no
     * warning, which is the complaint this project started from. Refuse and report.
     */
    @Test
    fun `a frame the bridge refuses is reported rather than thrown`() {
        val api = FakeRnnoiseApi(probability = 0.9f)
        val stage = RnnoisePreprocessor(api)

        val probability = stage.process(ShortArray(441))

        assertThat(probability).isNull()
        assertThat(stage.rejectedFrames).isEqualTo(1)
        assertThat(api.processedLengths).isEmpty()
    }

    /**
     * Same guard, other half of its input space. `p < 0` misses a NaN -- every comparison against
     * a NaN is false -- and a NaN probability reaching task 7's threshold compares false against
     * both the start and the stop threshold, i.e. it reads as permanent silence. One inverted
     * condition covers both, which is why there is one guard here and not two.
     */
    @Test
    fun `a not-a-number probability is refused rather than passed on`() {
        val stage = RnnoisePreprocessor(FakeRnnoiseApi(probability = Float.NaN))

        assertThat(stage.process(ShortArray(FRAME))).isNull()
        assertThat(stage.rejectedFrames).isEqualTo(1)
    }

    @Test
    fun `an accepted frame is not counted as rejected`() {
        val stage = RnnoisePreprocessor(api)

        stage.process(ShortArray(FRAME))

        assertThat(stage.rejectedFrames).isEqualTo(0)
    }

    @Test
    fun `a longer frame is accepted, because the bridge only reads the first 480 samples`() {
        val stage = RnnoisePreprocessor(FakeRnnoiseApi(probability = 0.5f))

        assertThat(stage.process(ShortArray(960))).isEqualTo(0.5f)
        assertThat(stage.rejectedFrames).isEqualTo(0)
    }

    // ------------------------------------------------------------------ life cycle

    @Test
    fun `release destroys the denoiser exactly once`() {
        val stage = RnnoisePreprocessor(api)

        stage.release()
        stage.release()

        assertThat(api.destroyed).isEqualTo(1)
    }

    /** A capture thread that outlived its join timeout must not reach rnnoise on a freed state. */
    @Test
    fun `process after release touches nothing and returns no probability`() {
        val api = FakeRnnoiseApi(probability = 0.9f)
        val stage = RnnoisePreprocessor(api)
        stage.release()

        assertThat(stage.process(ShortArray(FRAME))).isNull()

        assertThat(api.processedLengths).isEmpty()
        assertThat(api.destroyed).isEqualTo(1)
    }

    /**
     * RNNoise has no reverse stream, so the stage must refuse one rather than swallow it: a
     * far-end frame that lands here instead of on the echo canceller is 21 dB of cancellation
     * nobody notices is missing (task 2, `tests/test_apm.c`).
     *
     * Both halves are asserted, because they fail differently: declaring [FarEndSink] on this
     * stage would make `CapturePreprocessorFactory` hand out a far-end sink whose every frame
     * throws on the playback thread.
     */
    @Test
    fun `the stage is not a far-end sink and refuses the reverse stream`() {
        val stage = RnnoisePreprocessor(api)

        assertThat(stage).isNotInstanceOf(FarEndSink::class.java)
        assertThrows(UnsupportedOperationException::class.java) {
            stage.analyzeReverseStream(ShortArray(FRAME))
        }
    }

    // ------------------------------------------------------------------ handle ownership

    /**
     * The walk `SingleHandleStageTest` asks every stage to repeat in its own suite: the handle
     * lives in exactly one private field of the base, and no member of this stage's class chain
     * mentions a long except the three callbacks, which are called with the lock already held.
     *
     * It carries more here than for the speex stage: `HandleTable::get()` dereferences without
     * validating, so an RNNoise handle handed to the APM bridge is a segmentation fault with no
     * Java stack trace, and this package now holds both.
     */
    @Test
    fun `the native handle never escapes the stage`() {
        val hierarchy = generateSequence<Class<*>>(RnnoisePreprocessor::class.java) { it.superclass }
            .takeWhile { it != Any::class.java }
            .toList()
        assertWithMessage("the walk must reach the base class")
            .that(hierarchy).contains(SingleHandleStage::class.java)

        val longFields = hierarchy.flatMap { it.declaredFields.asList() }
            .filter { !it.isSynthetic && mentionsLong(it.type) }
        assertWithMessage("the handle must live in exactly one field")
            .that(longFields.map { "${it.declaringClass.simpleName}.${it.name}" }).hasSize(1)
        assertWithMessage("the one handle field must be the base class's private one")
            .that(longFields.single().declaringClass).isEqualTo(SingleHandleStage::class.java)
        assertThat(Modifier.isPrivate(longFields.single().modifiers)).isTrue()

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
}
