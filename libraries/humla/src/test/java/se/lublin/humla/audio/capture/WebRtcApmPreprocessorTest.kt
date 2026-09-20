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
import se.lublin.humla.audio.capture.fakes.FakeWebRtcApmApi

/** Spec B3: the WebRTC APM as one capture stage, with the playback thread's reverse stream. */
class WebRtcApmPreprocessorTest {
    private companion object {
        const val FRAME = 480

        /**
         * Two configs, not one, and these two rather than any two.
         *
         * `FakeWebRtcApmApi` reassembles the six flat parameters of `humla_apm_create` back into a
         * [WebRtcApmConfig], so comparing one config catches a swapped pair of booleans **only
         * where the two differ**. Over four booleans there are six pairs and no single config can
         * separate all of them. These two do: for every pair, at least one of them holds different
         * values -- (ec,ns) and (ec,hp) and (ns,agc) and (agc,hp) in [ONE], (ec,agc) and (ns,hp) in
         * [OTHER]. That is 4.04's "2^k inputs, not k mutations" applied to a positional argument
         * list instead of to a compound condition.
         */
        val ONE = WebRtcApmConfig(
            echoCancellation = true,
            noiseSuppression = false,
            noiseSuppressionLevel = 1,
            gainControl = true,
            highPass = false,
        )
        val OTHER = WebRtcApmConfig(
            echoCancellation = true,
            noiseSuppression = true,
            noiseSuppressionLevel = 3,
            gainControl = false,
            highPass = false,
        )
    }

    private val api = FakeWebRtcApmApi()

    // ------------------------------------------------------------------ construction

    @Test
    fun `creates the apm at 48 kHz with exactly the config it was given`() {
        for (config in listOf(ONE, OTHER)) {
            val api = FakeWebRtcApmApi()

            WebRtcApmPreprocessor(api, config)

            assertWithMessage("config %s did not reach humla_apm_create unchanged", config)
                .that(api.createdWith).isEqualTo(48000 to config)
        }
    }

    @Test
    fun `carries a non-default sample rate through to the apm`() {
        WebRtcApmPreprocessor(api, ONE, sampleRate = 16000)

        assertThat(api.createdWith).isEqualTo(16000 to ONE)
    }

    /**
     * The real reason a stage fails to come up: `humla_apm_create` answers 0 for any rate that is
     * not 8, 16, 32 or 48 kHz. `CapturePreprocessorFactory` turns this into a skipped stage and a
     * log line rather than a dead pipeline, so it has to be an [IllegalStateException] and not a
     * crash.
     */
    @Test
    fun `construction fails loudly when the apm cannot be created`() {
        val failure = assertThrows(IllegalStateException::class.java) {
            WebRtcApmPreprocessor(api, ONE, sampleRate = 44100)
        }

        assertThat(failure).hasMessageThat().contains("44100")
    }

    // ------------------------------------------------------------------ the capture path

    @Test
    fun `capture frames go through the apm and the probability follows the output level`() {
        val api = FakeWebRtcApmApi(levelDbfs = -35f, onCapture = { it.fill(3) })
        val frame = ShortArray(FRAME) { 500 }

        val probability = WebRtcApmPreprocessor(api, ONE).process(frame)

        assertThat(frame[0]).isEqualTo(3.toShort())
        // -35 dBFS is halfway between -50 (0.0) and -20 (1.0).
        assertThat(probability).isEqualTo(0.5f)
        assertThat(api.capturedLengths).containsExactly(FRAME)
    }

    /**
     * A failed `processCapture` leaves `lastCaptureLevelDbfs` reporting the level of the *previous*
     * frame -- or -100 for a released handle, which is "certainly silent". Task 7 gates
     * transmission on this number, so reporting either would be a stage stating an opinion about a
     * frame the APM never processed. The level must not even be read.
     */
    @Test
    fun `a native error is no opinion rather than a stale level`() {
        val api = FakeWebRtcApmApi(levelDbfs = -20f, captureError = -3)
        val stage = WebRtcApmPreprocessor(api, ONE)

        assertThat(stage.process(ShortArray(FRAME))).isNull()

        assertThat(api.levelReads).isEqualTo(0)
        assertThat(stage.rejectedFrames).isEqualTo(1)
    }

    /** The bridge refuses a frame shorter than 10 ms with -8; the same rule as any other error. */
    @Test
    fun `a frame the bridge refuses is reported rather than thrown`() {
        val api = FakeWebRtcApmApi(levelDbfs = -20f)
        val stage = WebRtcApmPreprocessor(api, ONE)

        assertThat(stage.process(ShortArray(441))).isNull()

        assertThat(api.capturedLengths).isEmpty()
        assertThat(stage.rejectedFrames).isEqualTo(1)
    }

    @Test
    fun `an accepted frame is not counted as rejected`() {
        val stage = WebRtcApmPreprocessor(api, ONE)

        stage.process(ShortArray(FRAME))

        assertThat(stage.rejectedFrames).isEqualTo(0)
    }

    // ------------------------------------------------------------------ the reverse stream

    @Test
    fun `the stage is a far-end sink`() {
        assertThat(WebRtcApmPreprocessor(api, ONE)).isInstanceOf(FarEndSink::class.java)
    }

    @Test
    fun `far-end frames are forwarded to the reverse stream`() {
        val stage = WebRtcApmPreprocessor(api, ONE)

        stage.analyzeReverseStream(ShortArray(FRAME) { 9 })

        assertThat(api.renderFrames).hasSize(1)
        assertThat(api.renderFrames[0][0]).isEqualTo(9.toShort())
        assertThat(stage.rejectedFarEndFrames).isEqualTo(0)
    }

    /**
     * The one failure in this class with no return value to carry it. `analyzeReverseStream`
     * answers nothing, so a reference frame the APM refuses -- a chunker built for the wrong frame
     * size is the way that happens -- is invisible everywhere above this layer while costing about
     * 21 dB of echo cancellation (task 2, `tests/test_apm.c`). The counter is the observable for
     * that; whoever owns the chain reports it through `captureState.Error` (spec §4).
     */
    @Test
    fun `a far-end frame the apm refuses is counted rather than dropped in silence`() {
        val stage = WebRtcApmPreprocessor(api, ONE)

        stage.analyzeReverseStream(ShortArray(441))

        assertThat(api.renderFrames).isEmpty()
        assertThat(stage.rejectedFarEndFrames).isEqualTo(1)
        assertWithMessage("a refused reference frame is not a refused capture frame")
            .that(stage.rejectedFrames).isEqualTo(0)
    }

    @Test
    fun `far-end frames after release are dropped instead of touching freed state`() {
        val stage = WebRtcApmPreprocessor(api, ONE)
        stage.release()

        stage.analyzeReverseStream(ShortArray(FRAME))

        assertThat(api.renderFrames).isEmpty()
        assertThat(api.destroyed).isEqualTo(1)
        assertWithMessage("a released stage has nothing to report, it is simply gone")
            .that(stage.rejectedFarEndFrames).isEqualTo(0)
    }

    // ------------------------------------------------------------------ life cycle

    @Test
    fun `release destroys the apm exactly once`() {
        val stage = WebRtcApmPreprocessor(api, ONE)

        stage.release()
        stage.release()

        assertThat(api.destroyed).isEqualTo(1)
    }

    @Test
    fun `process after release touches nothing and returns no probability`() {
        val api = FakeWebRtcApmApi(levelDbfs = -20f)
        val stage = WebRtcApmPreprocessor(api, ONE)
        stage.release()

        assertThat(stage.process(ShortArray(FRAME))).isNull()

        assertThat(api.capturedLengths).isEmpty()
        assertThat(api.levelReads).isEqualTo(0)
    }

    // ------------------------------------------------------------------ level to probability

    /**
     * The corner spec §4.1 wants written down rather than fixed here: this number is a **level**,
     * not a speech model. With noise suppression NONE and echo cancellation WEBRTC it is the only
     * opinion in the chain, so task 7 receives a loudness threshold wearing a voice probability's
     * type, and a threshold tuned against RNNoise does not mean the same thing here.
     */
    @Test
    fun `level to probability is linear between -50 and -20 dBFS and clamped outside`() {
        assertThat(LevelToProbability.fromDbfs(-100f)).isEqualTo(0f)
        assertThat(LevelToProbability.fromDbfs(-50f)).isEqualTo(0f)
        assertThat(LevelToProbability.fromDbfs(-35f)).isEqualTo(0.5f)
        assertThat(LevelToProbability.fromDbfs(-20f)).isEqualTo(1f)
        assertThat(LevelToProbability.fromDbfs(0f)).isEqualTo(1f)
    }

    @Test
    fun `level to probability answers a probability for every level, including nonsense ones`() {
        for (dbfs in -200..60) {
            val p = LevelToProbability.fromDbfs(dbfs.toFloat())
            assertWithMessage("%s dBFS", dbfs).that(p).isAtLeast(0f)
            assertWithMessage("%s dBFS", dbfs).that(p).isAtMost(1f)
        }
    }

    // ------------------------------------------------------------------ handle ownership

    /**
     * The walk `SingleHandleStageTest` asks every stage to repeat in its own suite. This is the
     * stage it was written for: `HandleTable::get()` dereferences without validating, so handing
     * the RNNoise handle to the APM bridge is a segmentation fault with no Java stack trace, and
     * this stage has two audio threads to hand it from.
     */
    @Test
    fun `the native handle never escapes the stage`() {
        val hierarchy = generateSequence<Class<*>>(WebRtcApmPreprocessor::class.java) { it.superclass }
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
