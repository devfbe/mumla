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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.fakes.FakeResampler
import se.lublin.humla.audio.capture.fakes.FakeRnnoiseApi
import se.lublin.humla.audio.capture.fakes.FakeWebRtcApmApi
import se.lublin.humla.audio.inputmode.ContinuousInputMode
import se.lublin.humla.util.HumlaLogger

/**
 * The wiring's guarantees: the preprocessed frame reaches the consumer, the mixed playback buffer
 * reaches the canceller in frames of the APM's own length before the matching capture frame, and a
 * chain that cannot be built takes neither capture nor playback down. The seam lives here because
 * `AudioHandler` cannot be instantiated on the host.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureWiringTest {
    private companion object {
        const val FRAME = 480
    }

    private val warnings = mutableListOf<String>()
    private val logger = object : HumlaLogger {
        override fun logInfo(message: String) = Unit
        override fun logWarning(message: String) { warnings += message }
        override fun logError(message: String) = Unit
    }

    private fun factory(rnnoise: () -> se.lublin.humla.audio.native.RnnoiseApi) =
        CapturePreprocessorFactory(rnnoiseApi = rnnoise, log = { warnings += it })

    private fun apmFactory(apm: FakeWebRtcApmApi) = CapturePreprocessorFactory(
        rnnoiseApi = { FakeRnnoiseApi() }, apmApi = { apm }, log = { warnings += it },
    )

    private fun wire(
        noise: NoiseSuppressionMode,
        echo: EchoCancellationMode,
        factory: CapturePreprocessorFactory,
    ) = CaptureWiring.wire(48000, ContinuousInputMode(), 1f, noise, echo, logger = logger, factory = factory)

    /** The default: one RNNoise stage and its probability on every frame. */
    @Test
    fun `the default chain denoises every frame with rnnoise`() {
        val api = FakeRnnoiseApi(probability = 0.9f, onProcess = { it.fill(11) })
        val pipeline = CaptureWiring.wire(
            48000, ContinuousInputMode(), 1f, NoiseSuppressionMode.RNNOISE,
            EchoCancellationMode.NONE, logger = logger, factory = factory { api },
        ).pipeline

        val frame = pipeline.process(ShortArray(FRAME) { 1000 }, FRAME)

        assertThat(frame.samples.toSet()).containsExactly(11.toShort())
        assertThat(frame.length).isEqualTo(FRAME)
        assertThat(frame.probability).isEqualTo(0.9f)
        assertThat(warnings).isEmpty()
    }

    /**
     * A missing `.so` is a skipped stage, not a dead microphone. The library loads in the object
     * initialiser, so it fails as an `ExceptionInInitializerError`, not an `Exception`.
     */
    @Test
    fun `a chain that cannot be built leaves capture running and says so`() {
        val pipeline = CaptureWiring.wire(
            48000, ContinuousInputMode(), 1f, NoiseSuppressionMode.RNNOISE,
            EchoCancellationMode.NONE, logger = logger, factory = factory { throw ExceptionInInitializerError(UnsatisfiedLinkError("libhumlarnnoise.so")) },
        ).pipeline

        val frame = pipeline.process(ShortArray(FRAME) { 1234 }, FRAME)

        assertThat(frame.samples.toSet()).containsExactly(1234.toShort())
        assertThat(frame.length).isEqualTo(FRAME)
        assertThat(frame.transmit).isTrue()
        assertThat(warnings.any { it.contains("rnnoise") }).isTrue()
    }

    /** No stage, and no warning about a stage nobody asked for. */
    @Test
    fun `no noise suppression builds no stage and warns about nothing`() {
        val pipeline = CaptureWiring.wire(
            48000, ContinuousInputMode(), 1f, NoiseSuppressionMode.NONE,
            EchoCancellationMode.NONE, logger = logger, factory = factory { throw ExceptionInInitializerError(UnsatisfiedLinkError("libhumlarnnoise.so")) },
        ).pipeline

        assertThat(pipeline.process(ShortArray(FRAME) { 7 }, FRAME).probability).isNull()
        assertThat(warnings).isEmpty()
    }

    // ------------------------------------------------------------------ the far-end reference

    /** The APM must be in the chain and the playback path must have a far-end tap; either alone is useless. */
    @Test
    fun `the webrtc canceller gets both a capture stage and a far-end tap`() {
        val apm = FakeWebRtcApmApi()

        val wiring = wire(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC, apmFactory(apm))
        wiring.pipeline.process(ShortArray(FRAME) { 1000 }, FRAME)

        assertThat(apm.createdWith).isEqualTo(48000 to WebRtcApmConfig.FOR_ECHO_CANCELLATION)
        assertThat(apm.capturedLengths).containsExactly(FRAME)
        assertThat(wiring.farEnd).isNotNull()
        assertThat(warnings).isEmpty()
    }

    /**
     * The sink sees the APM's frame, not `AudioOutput`'s playback buffer. A long frame would be
     * silently truncated by the bridge, losing about 21 dB of cancellation.
     */
    @Test
    fun `the mixed playback buffer arrives as frames of the apm's own length`() {
        val apm = FakeWebRtcApmApi()
        val wiring = wire(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC, apmFactory(apm))

        val mix = ShortArray(1000) { it.toShort() }
        wiring.farEnd!!.push(mix, mix.size)

        assertThat(apm.renderFrames.map { it.size }).containsExactly(FRAME, FRAME).inOrder()
        assertThat(apm.renderFrames[0][0]).isEqualTo(0.toShort())
        assertThat(apm.renderFrames[1][0]).isEqualTo(480.toShort())
        assertWithMessage("the 40 samples left over must wait, not be padded into a third frame")
            .that(apm.renderFrames).hasSize(2)
    }

    /**
     * Far-end frame first, then the near-end frame carrying its echo. The threads are independent,
     * so this only pins that the wiring adds no reordering between the two calls.
     */
    @Test
    fun `each tick feeds the reference before the capture frame`() {
        val calls = mutableListOf<String>()
        val apm = FakeWebRtcApmApi(onCapture = { calls += "capture" }, onRender = { calls += "render" })
        val wiring = wire(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC, apmFactory(apm))

        repeat(3) {
            wiring.farEnd!!.push(ShortArray(FRAME) { 7 }, FRAME)
            wiring.pipeline.process(ShortArray(FRAME) { 3 }, FRAME)
        }

        assertThat(calls)
            .containsExactly("render", "capture", "render", "capture", "render", "capture")
            .inOrder()
    }

    @Test
    fun `no canceller builds no apm and no tap`() {
        val apm = FakeWebRtcApmApi()

        val wiring = wire(NoiseSuppressionMode.NONE, EchoCancellationMode.NONE, apmFactory(apm))

        assertThat(apm.createdWith).isNull()
        assertThat(wiring.farEnd).isNull()
        assertThat(warnings).isEmpty()
    }

    /**
     * `farEnd` is null rather than a throwing tap, so `AudioOutput`'s null check is the entire
     * playback-side fallback.
     */
    @Test
    fun `an apm that cannot be built leaves capture and playback running and says so`() {
        val apm = FakeWebRtcApmApi().apply { failCreate = true }

        val wiring = wire(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC, apmFactory(apm))
        val frame = wiring.pipeline.process(ShortArray(FRAME) { 1234 }, FRAME)

        assertThat(wiring.farEnd).isNull()
        assertThat(frame.samples.toSet()).containsExactly(1234.toShort())
        assertThat(frame.transmit).isTrue()
        assertThat(warnings.any { it.contains("echo cancellation (webrtc)") }).isTrue()
    }

    /** The same fallback when the `.so` is missing from the device. */
    @Test
    fun `a missing apm library is a skipped stage rather than a dead microphone`() {
        val wiring = CaptureWiring.wire(
            48000, ContinuousInputMode(), 1f, NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC,
            logger = logger,
            factory = CapturePreprocessorFactory(
                apmApi = { throw ExceptionInInitializerError(UnsatisfiedLinkError("libhumlaapm.so")) },
                log = { warnings += it },
            ),
        )

        assertThat(wiring.farEnd).isNull()
        assertThat(wiring.pipeline.process(ShortArray(FRAME) { 5 }, FRAME).length).isEqualTo(FRAME)
        assertThat(warnings.any { it.contains("echo cancellation (webrtc)") }).isTrue()
    }

    // ------------------------------------------------------------------ the resampler

    @Test
    fun `capture at 48 kHz needs no resampler`() {
        val built = mutableListOf<Pair<Int, Int>>()

        CaptureWiring.wire(
            48000, ContinuousInputMode(), 1f, NoiseSuppressionMode.NONE, EchoCancellationMode.NONE,
            logger = logger, factory = factory { FakeRnnoiseApi() },
        ) { from, to -> built += from to to; FakeResampler(1) }

        assertThat(built).isEmpty()
    }

    /** Exactly one resampler may exist, or the frame would be converted twice. */
    @Test
    fun `capture below 48 kHz gets a resampler up to the codec rate`() {
        val built = mutableListOf<Pair<Int, Int>>()
        val pipeline = CaptureWiring.wire(
            16000, ContinuousInputMode(), 1f, NoiseSuppressionMode.NONE, EchoCancellationMode.NONE,
            logger = logger, factory = factory { FakeRnnoiseApi() },
        ) { from, to -> built += from to to; FakeResampler(3) }.pipeline

        val frame = pipeline.process(ShortArray(160) { 5 }, 160)

        assertThat(built).containsExactly(16000 to 48000)
        assertThat(frame.length).isEqualTo(FRAME)
        assertThat(frame.samples.toSet()).containsExactly(5.toShort())
    }
}
