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
 * Spec B2's composition rule: `[WebRTC APM] -> [Speex | RNNoise]`, and the probability is the last
 * non-null one in the chain.
 *
 * The order is semantics, not taste -- `ChainedPreprocessor`'s KDoc has the long version, and
 * task 2 measured it at 21.7 dB between a converged and an unconverged AEC3. What this file pins
 * is that the factory produces it.
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

    // ------------------------------------------------------------------ off is off

    /**
     * Spec §4.1, "pin off is off by identity". `NoopPreprocessor` is proven to run no code on the
     * frame; the observable that matters here is that the factory hands back *that object*, not a
     * stage built with neutral parameters that happens to leave the frame alone. A stage with
     * neutral parameters still holds native state, still takes a lock on every frame and still has
     * to be released -- and an assertion on the frame's contents cannot tell the two apart.
     */
    @Test
    fun `none and none is the no-op stage itself, with no far-end sink`() {
        val chain = factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.NONE)

        assertThat(chain.preprocessor).isSameInstanceAs(NoopPreprocessor)
        assertThat(chain.farEndSink).isNull()
    }

    /**
     * The stronger half of the pair above, and the three assertions are deliberately the same
     * strength. Each fake records the **attempt**, before its own failure check, so `0`/`null`
     * here means "the factory never asked", not "it asked and the fake said no" -- which is the
     * difference a `created` counter incremented after the check cannot express. Measured: a
     * factory that builds an RNNoise stage for NONE and throws it away keeps
     * `none and none is the no-op stage itself` green and dies **only** here.
     */
    @Test
    fun `an off chain creates no native state at all`() {
        factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.NONE)

        assertThat(rnnoise.createAttempts).isEqualTo(0)
        assertThat(speex.createdWith).isNull()
        assertThat(apm.createdWith).isNull()
    }

    /** Android's own canceller is attached to the AudioRecord session (spec B6), not to the chain. */
    @Test
    fun `android echo cancellation adds no webrtc stage`() {
        val chain = factory.create(NoiseSuppressionMode.SPEEX, EchoCancellationMode.ANDROID)

        chain.preprocessor.process(ShortArray(FRAME))

        assertThat(order).containsExactly("speex")
        assertThat(chain.farEndSink).isNull()
        assertThat(apm.createdWith).isNull()
    }

    @Test
    fun `none noise suppression with android echo is the no-op stage itself`() {
        val chain = factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.ANDROID)

        assertThat(chain.preprocessor).isSameInstanceAs(NoopPreprocessor)
    }

    // ------------------------------------------------------------------ the composition rule

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
     * The corner the composition rule leaves behind, pinned so task 7 meets it as a fact rather
     * than as a surprise: with no noise suppressor the only opinion in the chain is the APM's, and
     * the APM's number is an output **level** (-35 dBFS is halfway between -50 and -20), not a
     * speech model.
     */
    @Test
    fun `webrtc echo alone provides a level-based probability and a far-end sink`() {
        val chain = factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC)

        assertThat(chain.preprocessor.process(ShortArray(FRAME))).isEqualTo(0.5f)
        assertThat(chain.farEndSink).isNotNull()
    }

    /**
     * The far-end sink is the APM stage itself, not a second object wrapping it. It matters
     * because the sink is handed to the *playback* thread while the same instance runs on the
     * capture thread: one object, one lock (spec §4.1). A separate adapter would be a second
     * route to the same handle, which is exactly what `SingleHandleStage` exists to prevent.
     */
    @Test
    fun `the far-end sink is the stage that is in the chain`() {
        val chain = factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC)

        assertThat(chain.farEndSink).isSameInstanceAs(chain.preprocessor)
    }

    /** A chain of one is that one stage; wrapping it would cost an indirection on every frame. */
    @Test
    fun `a single stage is handed back unwrapped`() {
        val chain = factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.NONE)

        assertThat(chain.preprocessor).isInstanceOf(RnnoisePreprocessor::class.java)
        assertThat(chain.farEndSink).isNull()
    }

    /**
     * The configuration the APM is built with, pinned in one place because it is a decision and
     * not an implementation detail -- see [WebRtcApmConfig.FOR_ECHO_CANCELLATION], which carries
     * the decision and its two effects (AGC2 is the only automatic gain control inside the capture
     * chain today, and the level [LevelToProbability] reads is measured after NS and AGC2, so
     * turning NS off moved it).
     *
     * **Written out rather than compared against the constant**, and that is the whole point of
     * the test. Asserting `isEqualTo(WebRtcApmConfig.FOR_ECHO_CANCELLATION)` puts the value being
     * tested on both sides: flipping `noiseSuppression` in the constant then changes what the APM
     * is built with *and* what this expects, and the test stays green. Measured twice -- that
     * exact mutation survived a sweep in the tautological form, and the decision to turn NS off
     * turned this test red in this form, before the production line was touched. A decision
     * belongs in a literal here even though it duplicates four fields.
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

    /**
     * Releasing the chain releases every stage in it. Without this, a mode switch leaks one native
     * state per switch -- and `RnnoisePreprocessor` holds about 1.4 MB of model state.
     */
    @Test
    fun `releasing the chain releases every stage it built`() {
        val chain = factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC)

        chain.preprocessor.release()

        assertThat(rnnoise.destroyed).isEqualTo(1)
        assertThat(apm.destroyed).isEqualTo(1)
    }

    // ------------------------------------------------------------------ a stage that will not come up

    @Test
    fun `a stage whose native state fails is skipped and logged, the rest keeps working`() {
        rnnoise.failCreate = true

        val chain = factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC)
        val probability = chain.preprocessor.process(ShortArray(FRAME))

        assertThat(order).containsExactly("apm")
        assertThat(probability).isEqualTo(0.5f)
        assertThat(logs).hasSize(1)
        assertThat(logs.single()).contains("RNNoise")
    }

    /**
     * `RnnoiseNative` and `WebRtcApmNative` call `System.loadLibrary` in the Kotlin object
     * initialiser, so a missing `.so` surfaces as `ExceptionInInitializerError` on the first touch
     * and as `NoClassDefFoundError` on every later one -- neither of them an
     * `UnsatisfiedLinkError`, and neither an `Exception`. Spec §0.3: a load failure of one library
     * never takes the rest of the pipeline with it.
     */
    @Test
    fun `a stage whose native library fails to load is skipped and logged`() {
        val broken = CapturePreprocessorFactory(
            { speex },
            { throw ExceptionInInitializerError(UnsatisfiedLinkError("dlopen failed: libhumlarnnoise.so not found")) },
            { apm },
        ) { logs += it }

        val chain = broken.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC)
        val probability = chain.preprocessor.process(ShortArray(FRAME))

        assertThat(order).containsExactly("apm")
        assertThat(probability).isEqualTo(0.5f)
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
     * The one failure that is **not** caught: an unsupported speex suppression depth is a
     * programmer error (task 12 falls back to the default for anything it cannot read out of the
     * preference), so it reaches the caller instead of being logged and swallowed as a missing
     * library. That is what keeps the catch narrow enough to mean something.
     */
    @Test
    fun `an illegal argument is not swallowed as a missing library`() {
        assertThrows(IllegalArgumentException::class.java) {
            factory.create(NoiseSuppressionMode.SPEEX, EchoCancellationMode.NONE, speexNoiseSuppressDb = -20)
        }

        assertThat(logs).isEmpty()
    }

    /**
     * Enumerated from the production file rather than from what looked interesting (§4.04): the
     * factory branches on the noise mode, the echo mode and the number of stages that came up, so
     * every value of both enums has to be written by some test here. A mode a later task adds
     * fails this until someone decides what the factory does with it.
     */
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
