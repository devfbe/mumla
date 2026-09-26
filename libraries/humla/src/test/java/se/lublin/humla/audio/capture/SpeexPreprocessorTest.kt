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
import se.lublin.humla.audio.capture.fakes.FakeSpeexPreprocessApi
import se.lublin.humla.audio.capture.fakes.SpeexPreprocessorRequests as R

/**
 * Many `speex_preprocess_ctl` calls can look right and do nothing, so most of this file is about
 * which control calls the library actually answers.
 */
class SpeexPreprocessorTest {
    private companion object {
        const val FRAME = 480
    }

    private val api = FakeSpeexPreprocessApi()

    // ------------------------------------------------------------------ construction

    @Test
    fun `creates the state for 10 ms frames at 48 kHz`() {
        SpeexPreprocessor(api)

        assertThat(api.createdWith).isEqualTo(FRAME to 48000)
    }

    @Test
    fun `carries a non-default frame size and sample rate through to speex`() {
        SpeexPreprocessor(api, frameSize = 160, sampleRate = 16000)

        assertThat(api.createdWith).isEqualTo(160 to 16000)
    }

    @Test
    fun `construction fails loudly when speex cannot allocate`() {
        api.failCreate = true

        val failure = assertThrows(IllegalStateException::class.java) { SpeexPreprocessor(api) }

        assertThat(failure).hasMessageThat().contains("speex")
    }

    // ------------------------------------------------------------------ the control calls

    @Test
    fun `enables denoise, never agc or dereverb`() {
        SpeexPreprocessor(api)

        assertThat(api.setCalls).contains(R.SET_DENOISE to 1)
        // SET_AGC and SET_AGC_TARGET are compiled out of fixed-point libspeexdsp and answer -1;
        // SET_DEREVERB is answered but never read by preprocess.c.
        assertThat(api.attemptedRequests)
            .containsNoneOf(R.SET_AGC, R.SET_AGC_TARGET, R.SET_DEREVERB)
    }

    /**
     * `GET_PROB_START`/`SET_PROB_START` only tune speex's own VAD hysteresis, which affects just the
     * return value of `speex_preprocess_run`. The stage reads `GET_PROB` directly instead, so
     * neither request (nor `SET_VAD`) belongs here.
     */
    @Test
    fun `B9 is satisfied by reading the probability, not by setting a threshold on it`() {
        val stage = SpeexPreprocessor(api)
        api.probability = 42

        val probability = stage.process(ShortArray(FRAME))

        assertThat(probability).isEqualTo(0.42f)
        assertThat(api.getRequests).containsExactly(R.GET_PROB)
        assertWithMessage("neither the legacy request nor its correction belongs in this stage")
            .that(api.attemptedRequests)
            .containsNoneOf(R.GET_PROB_START, R.SET_PROB_START, R.SET_VAD)
    }

    /**
     * `PP(ctlInt)` refuses requests outside its allow list with -1, indistinguishable from a request
     * speex does not implement, and the stage ignores the status. `SET_PROB_CONTINUE` is not on it.
     */
    @Test
    fun `every control request the stage issues is one the bridge lets through`() {
        val stage = SpeexPreprocessor(api)
        stage.process(ShortArray(FRAME))

        assertWithMessage("a request off the allow list is refused with -1 before it reaches speex")
            .that(api.attemptedRequests.filterNot { it in R.ALLOWED }).isEmpty()
        // The whole set: any request added to the stage shows up here.
        assertThat(api.attemptedRequests).containsExactly(
            R.SET_DENOISE, R.SET_NOISE_SUPPRESS, R.GET_PROB,
        ).inOrder()
    }

    // ------------------------------------------------------------------ noise suppression

    @Test
    fun `applies each supported noise suppression level in dB`() {
        for (db in SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB) {
            val fresh = FakeSpeexPreprocessApi()

            val stage = SpeexPreprocessor(fresh, noiseSuppressDb = db)

            assertWithMessage("$db dB").that(fresh.setCalls).contains(R.SET_NOISE_SUPPRESS to db)
            assertWithMessage("$db dB").that(stage.noiseSuppressDb).isEqualTo(db)
        }
        assertThat(SpeexPreprocessor.SUPPORTED_NOISE_SUPPRESS_DB)
            .containsExactly(-15, -25, -35).inOrder()
        assertWithMessage(
            "the default suppression depth is a decision, not a free choice out of the supported " +
                "set: pinning it with isIn() leaves -15 and -35 green"
        ).that(SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB).isEqualTo(-25)
    }

    @Test
    fun `the default level is the one that is applied when none is asked for`() {
        SpeexPreprocessor(api)

        assertThat(api.setCalls)
            .contains(R.SET_NOISE_SUPPRESS to SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB)
    }

    /**
     * Refused before the native state is created: the constructor throws, so nobody could release a
     * state created first.
     */
    @Test
    fun `rejects an unsupported noise suppression level without creating a state`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            SpeexPreprocessor(api, noiseSuppressDb = -20)
        }

        assertThat(failure).hasMessageThat().contains("-20")
        assertThat(api.created).isEqualTo(0)
        assertThat(api.createdWith).isNull()
    }

    // ------------------------------------------------------------------ the frame path

    @Test
    fun `process runs speex on the frame and reports its probability as a fraction`() {
        val halving = FakeSpeexPreprocessApi(
            probability = 73,
            onRun = { f -> for (i in f.indices) f[i] = (f[i] / 2).toShort() },
        )
        val stage = SpeexPreprocessor(halving)
        val frame = ShortArray(FRAME) { 1000 }

        val probability = stage.process(frame)

        assertThat(frame[0]).isEqualTo(500.toShort())
        assertThat(probability).isEqualTo(0.73f)
        assertThat(halving.getRequests).containsExactly(R.GET_PROB)
    }

    @Test
    fun `a probability outside speex's own range is clamped rather than propagated`() {
        for ((answer, expected) in listOf(-1 to 0f, 0 to 0f, 50 to 0.5f, 100 to 1f, 101 to 1f)) {
            val fresh = FakeSpeexPreprocessApi(probability = answer)
            val stage = SpeexPreprocessor(fresh)

            assertWithMessage("GET_PROB answered $answer")
                .that(stage.process(ShortArray(FRAME))).isEqualTo(expected)
        }
    }

    /**
     * The bridge refuses a frame shorter than the state's frame size with -1. Reporting that as
     * voice would be wrong, and throwing would kill the capture thread once per frame.
     */
    @Test
    fun `a frame too short for the state is dropped rather than reported as voice`() {
        api.probability = 100
        val stage = SpeexPreprocessor(api)

        val probability = stage.process(ShortArray(441))

        assertThat(probability).isNull()
        assertThat(api.getRequests).isEmpty()
        assertThat(stage.rejectedFrames).isEqualTo(1)
    }

    @Test
    fun `an accepted frame is not counted as rejected`() {
        val stage = SpeexPreprocessor(api)

        stage.process(ShortArray(FRAME))

        assertThat(stage.rejectedFrames).isEqualTo(0)
    }

    /**
     * A refused `GET_PROB` leaves `value[0]` at 0 ("certainly not speech"), which would mute the
     * user; null means no opinion.
     */
    @Test
    fun `a refused probability read is no opinion rather than certain silence`() {
        api.refuse += R.GET_PROB
        val stage = SpeexPreprocessor(api)

        assertThat(stage.process(ShortArray(FRAME))).isNull()
    }

    // ------------------------------------------------------------------ life cycle

    @Test
    fun `release destroys the state exactly once`() {
        val stage = SpeexPreprocessor(api)

        stage.release()
        stage.release()

        assertThat(api.destroyed).isEqualTo(1)
    }

    /** A capture thread that outlived its join timeout must not reach speex on a freed state. */
    @Test
    fun `process after release touches nothing and returns no probability`() {
        val stage = SpeexPreprocessor(api)
        stage.release()
        val runsBefore = api.runs
        val requestsBefore = api.attemptedRequests.size

        assertThat(stage.process(ShortArray(FRAME))).isNull()

        assertThat(api.runs).isEqualTo(runsBefore)
        assertThat(api.attemptedRequests).hasSize(requestsBefore)
    }

    /** A far-end frame swallowed here would silently cost about 21 dB of echo cancellation. */
    @Test
    fun `the stage refuses the reverse stream instead of dropping it`() {
        val stage = SpeexPreprocessor(api)

        assertThrows(UnsupportedOperationException::class.java) {
            stage.analyzeReverseStream(ShortArray(FRAME))
        }
    }

    // ------------------------------------------------------------------ handle ownership

    /**
     * The handle lives in exactly one private field of the base, and no member mentions a long
     * except the three callbacks, which run with the lock held.
     */
    @Test
    fun `the native handle never escapes the stage`() {
        val hierarchy = generateSequence<Class<*>>(SpeexPreprocessor::class.java) { c -> c.superclass }
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
