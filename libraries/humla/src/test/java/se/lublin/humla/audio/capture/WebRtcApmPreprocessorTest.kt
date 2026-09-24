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

/** The WebRTC APM as a capture stage that also takes the playback reverse stream. */
class WebRtcApmPreprocessorTest {
    private companion object {
        const val FRAME = 480

        /** Ten 10 ms ticks: long enough that a one-tick offset cannot land on the same answer. */
        const val TICKS = 10

        /**
         * The fake reassembles the flat create parameters into a [WebRtcApmConfig], so a swapped
         * pair of booleans is only caught where the two differ. Together these two configs differ
         * in every one of the six pairs.
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
     * `humla_apm_create` answers 0 for rates other than 8/16/32/48 kHz. The factory turns this
     * [IllegalStateException] into a skipped stage and a log line.
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
        // Proves the counter counts; two tests below assert it stays zero after a refused frame.
        assertThat(api.levelReads).isEqualTo(1)
    }

    /**
     * After a failed `processCapture` the level is stale (or -100 for a released handle), so it
     * must not even be read; transmission is gated on it.
     */
    @Test
    fun `a native error is no opinion rather than a stale level`() {
        val api = FakeWebRtcApmApi(levelDbfs = -20f, captureError = -3)
        val stage = WebRtcApmPreprocessor(api, ONE)

        assertThat(stage.process(ShortArray(FRAME))).isNull()

        assertThat(api.levelReads).isEqualTo(0)
        assertThat(stage.rejectedFrames).isEqualTo(1)
    }

    /** The bridge refuses a frame shorter than 10 ms with -8. */
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

    /**
     * The bridge refuses a short far-end frame but silently truncates a long one, so the chunker
     * must be sized from the stage: at 16 kHz a far-end frame is 160 samples, not 480.
     */
    @Test
    fun `the far-end frame size is the apm's own, not the chain's frame size`() {
        assertThat(WebRtcApmPreprocessor(api, ONE).farEndFrameSize).isEqualTo(FRAME)
        assertThat(WebRtcApmPreprocessor(api, ONE, sampleRate = 16000).farEndFrameSize).isEqualTo(160)
    }

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
     * `analyzeReverseStream` returns nothing, so a refused reference frame (which costs about
     * 21 dB of echo cancellation) is only observable through this counter.
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
     * This is a level, not a speech model: with noise suppression NONE and echo cancellation WEBRTC
     * it is the only opinion in the chain, so thresholds tuned against RNNoise mean something else.
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

    // ------------------------------------------------------------- the two streams, in sequence

    /**
     * The only test that drives both entry points alternately: the stage must forward tick n's
     * far-end frame before tick n's capture frame, without buffering. Feeding the reference late
     * silently ruins echo cancellation. Driven through a real [FarEndFrameChunker], as in production.
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
     * `HandleTable::get()` does not validate the handle type, so handing another stage's handle to
     * the APM bridge would segfault; this stage has two audio threads that could leak it.
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

    /** `long`, `java.lang.Long`, or an array of either (an out-parameter is an escape too). */
    private fun mentionsLong(type: Class<*>): Boolean = when {
        type == Long::class.javaPrimitiveType || type == java.lang.Long::class.java -> true
        type.isArray -> mentionsLong(type.componentType!!)
        else -> false
    }
}
