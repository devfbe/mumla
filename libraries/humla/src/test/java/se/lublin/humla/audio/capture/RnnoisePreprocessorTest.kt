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

/** RNNoise as a capture stage: 480-sample frames at 48 kHz, reporting the model's probability. */
class RnnoisePreprocessorTest {
    private companion object {
        const val FRAME = 480
    }

    private val api = FakeRnnoiseApi()

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
     * Only the upper end is clamped: -1 is the bridge's refusal sentinel and must not become 0
     * ("certainly not speech"), which would mute the user.
     */
    @Test
    fun `a probability above one is clamped rather than propagated`() {
        assertThat(RnnoisePreprocessor(FakeRnnoiseApi(probability = 1.4f)).process(ShortArray(FRAME)))
            .isEqualTo(1f)
    }

    /**
     * The bridge refuses a frame shorter than 480 samples with -1. Throwing instead would kill the
     * capture thread once per frame, so the frame is refused and counted.
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

    /** `p < 0` misses NaN, which would compare false against both thresholds and read as silence. */
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
     * A far-end frame swallowed here would silently cost about 21 dB of echo cancellation. Declaring
     * [FarEndSink] would make the factory hand out a sink that throws on the playback thread.
     */
    @Test
    fun `the stage is not a far-end sink and refuses the reverse stream`() {
        val stage = RnnoisePreprocessor(api)

        assertThat(stage).isNotInstanceOf(FarEndSink::class.java)
        assertThrows(UnsupportedOperationException::class.java) {
            stage.analyzeReverseStream(ShortArray(FRAME))
        }
    }

    /**
     * The handle lives in exactly one private field of the base, and no member mentions a long
     * except the three callbacks, which run with the lock held.
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

    /** `long`, `java.lang.Long`, or an array of either (an out-parameter is an escape too). */
    private fun mentionsLong(type: Class<*>): Boolean = when {
        type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> true
        type.isArray -> mentionsLong(type.componentType!!)
        else -> false
    }
}
