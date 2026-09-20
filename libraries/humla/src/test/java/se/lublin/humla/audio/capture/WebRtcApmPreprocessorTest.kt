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

        /** Ten 10 ms ticks: long enough that a one-tick offset cannot land on the same answer. */
        const val TICKS = 10

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
            gainControl = true,
            highPass = false,
        )
        val OTHER = WebRtcApmConfig(
            echoCancellation = true,
            noiseSuppression = true,
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
        // -35 dBFS is 10 dB above the -45 floor, i.e. 10/21.7 of the adopted -45/-23.3 window.
        assertThat(probability).isWithin(0.0005f).of(0.4608f)
        assertThat(api.capturedLengths).containsExactly(FRAME)
        // The counting direction of `levelReads`. Two tests below assert it is *zero* after a
        // refused frame -- and a counter that never counted would pass both of them, which is the
        // fake reporting a property it cannot see. One read per accepted frame, exactly.
        assertThat(api.levelReads).isEqualTo(1)
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
    fun `level to probability is linear between -45 and -23_3 dBFS and clamped outside`() {
        assertThat(LevelToProbability.fromDbfs(-100f)).isEqualTo(0f)
        assertThat(LevelToProbability.fromDbfs(-45f)).isEqualTo(0f)
        assertThat(LevelToProbability.fromDbfs(-34.15f)).isWithin(0.0005f).of(0.5f)
        assertThat(LevelToProbability.fromDbfs(-23.3f)).isEqualTo(1f)
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

    // ------------------------------------------------------------- the two streams, in sequence

    /**
     * The one relation the rest of this file cannot see. Every other test drives one stream at a
     * time, so the *content* of each direction is pinned and the **order between them** is not --
     * and that order is the third of the three ways task 2 measured the reference signal being
     * ruined ("fed 200 ms late", -0.63 dB residual against -22.32 dB, `tests/test_apm.c`). All
     * three are silent: every call returns 0.
     *
     * What is in scope here and what is not. Who calls whom per tick is the *caller's* -- the
     * playback thread pushes, the capture thread processes, and wiring them is task 10's. What
     * this pins is that the stage adds nothing of its own in between: given the calls in the
     * right order, the bridge sees them in the same order, in the same tick, with the far-end
     * frame of tick n and not of tick n-1. A stage that buffered one far-end frame and forwarded
     * it on the next tick would satisfy every other test in this file.
     *
     * Driven through a real [FarEndFrameChunker] rather than by calling the sink directly,
     * because the chunker is what stands between the playback buffer and this frame in
     * production, and an exact-multiple push is the case where it is closest to being skippable.
     *
     * **What it is worth, and what it is not.** Two mutations of `onFarEndFrame`, measured:
     *
     * | mutation                                                  | tests red |
     * |-----------------------------------------------------------|-----------|
     * | defer the frame one tick through a copy                    | 4 -- this test, `far-end frames are forwarded to the reverse stream`, `a far-end frame the apm refuses is counted rather than dropped in silence`, `CaptureThreadAllocationTest` |
     * | forward the previous tick's samples in this tick's slot, allocation-free | 2 -- this test and `far-end frames are forwarded to the reverse stream` |
     *
     * So this is **not** the unique killer of either, and saying otherwise would be the thing
     * §4.04 warns about. The reason is structural: this stage cannot get the *relation* wrong
     * without also getting the far-end *content* wrong, and the content is already pinned. What
     * this adds is that it is the only test in the module that drives both entry points
     * **alternately**, so a later change that batches, queues or re-orders the two streams has
     * something to break. The relation that is genuinely unpinned -- who calls whom per tick --
     * belongs to whoever wires the playback thread to the capture thread, i.e. task 10, and it is
     * in the ledger under that owner.
     */
    @Test
    fun `each tick reaches the bridge as its far-end frame and then its near-end frame`() {
        val order = mutableListOf<String>()
        val api = FakeWebRtcApmApi(
            onCapture = { order += "capture" },
            onRender = { order += "render" },
        )
        val stage = WebRtcApmPreprocessor(api, WebRtcApmConfig.FOR_ECHO_CANCELLATION)
        val chunker = FarEndFrameChunker(FRAME, stage)

        repeat(TICKS) { tick ->
            chunker.push(ShortArray(FRAME) { tick.toShort() }, FRAME)
            stage.process(ShortArray(FRAME))
        }

        assertWithMessage("every tick is one far-end frame and then the near-end frame carrying its echo")
            .that(order)
            .containsExactlyElementsIn(List(TICKS) { listOf("render", "capture") }.flatten())
            .inOrder()
        assertWithMessage("tick n's reference frame, not tick n-1's: AEC3 absorbs one frame of slack and no more")
            .that(api.renderFrames.map { it[0].toInt() })
            .containsExactlyElementsIn(List(TICKS) { it })
            .inOrder()
    }

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
