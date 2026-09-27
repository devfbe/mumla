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
import se.lublin.humla.audio.capture.fakes.FakeRnnoiseApi
import se.lublin.humla.audio.capture.fakes.FakeSpeexPreprocessApi
import se.lublin.humla.audio.capture.fakes.FakeWebRtcApmApi
import se.lublin.humla.audio.capture.fakes.SpeexPreprocessorRequests as R

/**
 * Composition rule: `[WebRTC APM] -> [Speex | RNNoise]`, and the probability is the last non-null
 * one in the chain. The order matters: see `ChainedPreprocessor`.
 */
class CapturePreprocessorFactoryTest {
    private companion object {
        const val FRAME = 480
    }

    private val order = mutableListOf<String>()
    private val speex = FakeSpeexPreprocessApi(probability = 40, onRun = { order += "speex" })
    private val rnnoise = FakeRnnoiseApi(probability = 0.95f, onProcess = { order += "rnnoise" })
    private val apm = FakeWebRtcApmApi(levelDbfs = -35f, onCapture = { order += "apm" })
    private val logs = mutableListOf<String>()
    private val factory = CapturePreprocessorFactory({ speex }, { rnnoise }, { apm }, { logs += it })

    /**
     * Off must be the [NoopPreprocessor] object itself: a stage with neutral parameters would still
     * hold native state and take a lock per frame, which the frame contents cannot reveal.
     */
    @Test
    fun `none and none is the no-op stage itself, with no far-end sink`() {
        val chain = factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.NONE)

        assertThat(chain.preprocessor).isSameInstanceAs(NoopPreprocessor)
        assertThat(chain.farEndSink).isNull()
    }

    /** The fakes record the attempt before their failure check, so 0/null means "never asked". */
    @Test
    fun `an off chain creates no native state at all`() {
        factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.NONE)

        assertThat(rnnoise.createAttempts).isEqualTo(0)
        assertThat(speex.createdWith).isNull()
        assertThat(apm.createdWith).isNull()
    }

    @Test
    fun `no echo cancellation adds no webrtc stage`() {
        val chain = factory.create(NoiseSuppressionMode.SPEEX, EchoCancellationMode.NONE)

        chain.preprocessor.process(ShortArray(FRAME))

        assertThat(order).containsExactly("speex")
        assertThat(chain.farEndSink).isNull()
        assertThat(apm.createdWith).isNull()
    }

    @Test
    fun `none noise suppression without echo cancellation is the no-op stage itself`() {
        val chain = factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.NONE)

        assertThat(chain.preprocessor).isSameInstanceAs(NoopPreprocessor)
    }

    @Test
    fun `webrtc echo runs before rnnoise and the probability comes from rnnoise`() {
        val chain = factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC)

        val probability = chain.preprocessor.process(ShortArray(FRAME))

        assertThat(order).containsExactly("apm", "rnnoise").inOrder()
        assertThat(probability).isEqualTo(0.95f)
        assertThat(chain.farEndSink).isNotNull()
    }

    @Test
    fun `webrtc echo runs before speex and the probability comes from speex`() {
        val chain = factory.create(NoiseSuppressionMode.SPEEX, EchoCancellationMode.WEBRTC)

        val probability = chain.preprocessor.process(ShortArray(FRAME))

        assertThat(order).containsExactly("apm", "speex").inOrder()
        assertThat(probability).isEqualTo(0.40f)
    }

    /**
     * With no noise suppressor the only opinion is the APM's output level (-35 dBFS in the fake,
     * 0.4608 of the -45/-23.3 window), not a speech model.
     */
    @Test
    fun `webrtc echo alone provides a level-based probability and a far-end sink`() {
        val chain = factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC)

        assertThat(chain.preprocessor.process(ShortArray(FRAME))).isWithin(0.0005f).of(0.4608f)
        assertThat(chain.farEndSink).isNotNull()
    }

    /**
     * The sink is handed to the playback thread while the same instance runs on the capture
     * thread: one object, one lock. A wrapper would be a second route to the same handle.
     */
    @Test
    fun `the far-end sink is the stage that is in the chain`() {
        val chain = factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC)

        assertThat(chain.farEndSink).isSameInstanceAs(chain.preprocessor)
    }

    /**
     * Carried out of the factory because a long far-end frame is silently truncated rather than
     * refused, so a chunker sized from a constant could fail without any counter moving.
     */
    @Test
    fun `the chain carries the far-end frame size the apm demands`() {
        assertThat(factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC).farEndFrameSize)
            .isEqualTo(FRAME)
        assertThat(factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.NONE).farEndFrameSize)
            .isEqualTo(0)
    }

    /** A chain of one is that one stage; wrapping it would cost an indirection on every frame. */
    @Test
    fun `a single stage is handed back unwrapped`() {
        val chain = factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.NONE)

        assertThat(chain.preprocessor).isInstanceOf(RnnoisePreprocessor::class.java)
        assertThat(chain.farEndSink).isNull()
    }

    /**
     * Written out rather than compared against [WebRtcApmConfig.FOR_ECHO_CANCELLATION]: comparing
     * against the constant would put the value under test on both sides of the assertion.
     */
    @Test
    fun `the apm is built for echo cancellation at 48 kHz`() {
        factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC)

        assertThat(apm.createdWith).isEqualTo(
            48000 to WebRtcApmConfig(
                echoCancellation = true,
                noiseSuppression = false,
                gainControl = true,
                highPass = true,
            )
        )
    }

    @Test
    fun `speex gets the requested suppression depth`() {
        factory.create(NoiseSuppressionMode.SPEEX, EchoCancellationMode.NONE, speexNoiseSuppressDb = -35)

        assertThat(speex.setCalls).contains(R.SET_NOISE_SUPPRESS to -35)
    }

    @Test
    fun `speex gets the default suppression depth when none is asked for`() {
        factory.create(NoiseSuppressionMode.SPEEX, EchoCancellationMode.NONE)

        assertThat(speex.setCalls)
            .contains(R.SET_NOISE_SUPPRESS to SpeexPreprocessor.DEFAULT_NOISE_SUPPRESS_DB)
    }

    /** `RnnoisePreprocessor` alone holds about 1.4 MB of native model state. */
    @Test
    fun `releasing the chain releases every stage it built`() {
        val chain = factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC)

        chain.preprocessor.release()

        assertThat(rnnoise.destroyed).isEqualTo(1)
        assertThat(apm.destroyed).isEqualTo(1)
    }

    @Test
    fun `a stage whose native state fails is skipped and logged, the rest keeps working`() {
        rnnoise.failCreate = true

        val chain = factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC)
        val probability = chain.preprocessor.process(ShortArray(FRAME))

        assertThat(order).containsExactly("apm")
        assertThat(probability).isWithin(0.0005f).of(0.4608f)
        assertThat(logs).hasSize(1)
        assertThat(logs.single()).contains("RNNoise")
    }

    /**
     * The native objects load their library in the object initialiser, so a missing `.so` surfaces
     * as `ExceptionInInitializerError` first and `NoClassDefFoundError` afterwards, never as an
     * `Exception`. One failed stage must not take down the rest of the pipeline.
     */
    @Test
    fun `a stage whose native library fails to load is skipped and logged`() {
        val broken = CapturePreprocessorFactory(
            { speex },
            { throw ExceptionInInitializerError(UnsatisfiedLinkError("dlopen failed: libhumla_native.so not found")) },
            { apm },
        ) { logs += it }

        val chain = broken.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC)
        val probability = chain.preprocessor.process(ShortArray(FRAME))

        assertThat(order).containsExactly("apm")
        assertThat(probability).isWithin(0.0005f).of(0.4608f)
        assertThat(logs).hasSize(1)
        assertThat(logs.single()).contains("RNNoise")
    }

    @Test
    fun `a stage whose native class is already missing is skipped and logged`() {
        val broken = CapturePreprocessorFactory(
            { speex },
            { rnnoise },
            { throw NoClassDefFoundError("se/lublin/humla/audio/native/WebRtcApmNative") },
        ) { logs += it }

        val chain = broken.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC)
        val probability = chain.preprocessor.process(ShortArray(FRAME))

        assertThat(order).containsExactly("rnnoise")
        assertThat(probability).isEqualTo(0.95f)
        assertWithMessage("a missing APM means there is nothing to feed the far end to")
            .that(chain.farEndSink).isNull()
        assertThat(logs).hasSize(1)
        assertThat(logs.single()).contains("WebRTC APM")
    }

    /** A speex stage that fails to load must cost the noise suppression and nothing else. */
    @Test
    fun `a speex stage whose native library fails to load is skipped and logged`() {
        val broken = CapturePreprocessorFactory(
            { throw ExceptionInInitializerError(UnsatisfiedLinkError("dlopen failed: libhumla_native.so not found")) },
            { rnnoise },
            { apm },
        ) { logs += it }

        val chain = broken.create(NoiseSuppressionMode.SPEEX, EchoCancellationMode.WEBRTC)
        val probability = chain.preprocessor.process(ShortArray(FRAME))

        assertThat(order).containsExactly("apm")
        assertThat(probability).isWithin(0.0005f).of(0.4608f)
        assertThat(logs).hasSize(1)
        assertWithMessage("the log line is what tells the user which suppressor is not running")
            .that(logs.single()).contains("Speex")
    }

    @Test
    fun `a speex stage whose native state fails is skipped and logged`() {
        speex.failCreate = true

        val chain = factory.create(NoiseSuppressionMode.SPEEX, EchoCancellationMode.WEBRTC)
        val probability = chain.preprocessor.process(ShortArray(FRAME))

        assertThat(order).containsExactly("apm")
        assertThat(probability).isWithin(0.0005f).of(0.4608f)
        assertThat(logs).hasSize(1)
        assertThat(logs.single()).contains("Speex")
    }

    @Test
    fun `every stage failing leaves the no-op stage itself`() {
        rnnoise.failCreate = true
        apm.failCreate = true

        val chain = factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC)

        assertThat(chain.preprocessor).isSameInstanceAs(NoopPreprocessor)
        assertThat(chain.farEndSink).isNull()
        assertThat(logs).hasSize(2)
    }

    /**
     * An unsupported speex depth is a programmer error and must reach the caller rather than be
     * logged as a missing library; that keeps the catch narrow.
     */
    @Test
    fun `an illegal argument is not swallowed as a missing library`() {
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(NoiseSuppressionMode.SPEEX, EchoCancellationMode.NONE, speexNoiseSuppressDb = -20)
        }

        assertThat(logs).isEmpty()
    }

    /** The APM built before the throw is unreachable from anywhere else, so `create` must free it. */
    @Test
    fun `a throwing stage releases the stages already built`() {
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(
                NoiseSuppressionMode.SPEEX,
                EchoCancellationMode.WEBRTC,
                speexNoiseSuppressDb = -20,
            )
        }

        assertWithMessage("the apm built before the throw is unreachable, so nothing else can free it")
            .that(apm.destroyed).isEqualTo(1)
        assertThat(logs).isEmpty()
    }

    /** Every value of both enums must be handled; a new mode fails here until it is decided. */
    @Test
    fun `every mode combination produces a chain`() {
        for (noise in NoiseSuppressionMode.entries) {
            for (echo in EchoCancellationMode.entries) {
                val chain = CapturePreprocessorFactory(
                    { FakeSpeexPreprocessApi() }, { FakeRnnoiseApi() }, { FakeWebRtcApmApi() },
                ) { logs += it }.create(noise, echo)

                val probability = chain.preprocessor.process(ShortArray(FRAME))
                assertWithMessage("%s + %s gave a probability outside [0, 1]: %s", noise, echo, probability)
                    .that(probability == null || probability in 0f..1f).isTrue()
                assertWithMessage("%s + %s only has a far-end sink with the webrtc canceller", noise, echo)
                    .that(chain.farEndSink != null).isEqualTo(echo == EchoCancellationMode.WEBRTC)
            }
        }
        assertThat(logs).isEmpty()
    }
}
