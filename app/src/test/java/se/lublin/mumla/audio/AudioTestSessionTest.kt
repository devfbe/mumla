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

package se.lublin.mumla.audio

import android.content.Context
import android.media.AudioManager
import android.media.MediaRecorder
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.AdaptiveVadTracker
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.CapturePreprocessorFactory
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.Resampler
import se.lublin.humla.audio.capture.VadConfig
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
class AudioTestSessionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val readings = CopyOnWriteArrayList<MeterReading>()
    private var session: AudioTestSession? = null

    @After
    fun stopSession() {
        session?.stop()
    }

    private fun frameAt(dbfs: Float, length: Int = 480): ShortArray {
        val amplitude = (32768.0 * 10.0.pow(dbfs / 20.0)).roundToInt().coerceIn(0, 32767)
        return ShortArray(length) { amplitude.toShort() }
    }

    private fun preprocessors(speexGain: Float = 1f, speexProbability: Int = 0) =
        CapturePreprocessorFactory(
            speexApi = { ScalingSpeexApi(speexGain, speexProbability) },
            rnnoiseApi = { AbsentRnnoiseApi() },
            apmApi = { AbsentApmApi() },
        )

    private fun session(
        source: TestCaptureSource,
        sink: TestPlaybackSink = TestPlaybackSink(),
        vad: VadConfig = VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1),
        noise: NoiseSuppressionMode = NoiseSuppressionMode.NONE,
        loopback: Boolean = false,
        effects: AndroidAudioEffects = AndroidAudioEffects(),
        echo: EchoCancellationMode = EchoCancellationMode.NONE,
        speexGain: Float = 1f,
        speexProbability: Int = 0,
        sinkFactory: TestPlaybackSink.Factory = TestPlaybackSink.Factory(sink),
        captureFactory: TestCaptureSource.Factory = TestCaptureSource.Factory(source),
        resamplerFactory: (Int, Int) -> Resampler = { _, _ -> error("no resampler expected at 48 kHz") },
    ) = AudioTestSession(
        audioManager, vad, noise, -25, echo, effects, loopback, { readings += it },
        readingIntervalFrames = 1,
        captureFactory = captureFactory,
        sinkFactory = sinkFactory,
        preprocessorFactory = preprocessors(speexGain, speexProbability),
        resamplerFactory = resamplerFactory,
    ).also { session = it }

    private fun await(timeoutMs: Long = 4000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(2)
        assertThat(condition()).isTrue()
    }

    // --- what the meter reports ----------------------------------------------------------------

    @Test
    fun `the reading carries the level, the two tracked marks and the threshold between them`() {
        val s = session(TestCaptureSource(List(20) { frameAt(-20f) }, loopLastFrame = true))
        s.start()
        await { readings.size >= 5 }
        s.stop()

        val reading = readings.last()
        assertThat(reading.levelDbfs).isWithin(0.1f).of(-20f)
        assertThat(reading.floorDbfs).isNotNull()
        assertThat(reading.speechDbfs).isNotNull()
        assertThat(reading.thresholdDbfs).isNotNull()
        // The threshold sits between the two marks, which is the whole claim the bar makes.
        assertThat(reading.thresholdDbfs!!).isGreaterThan(reading.floorDbfs!!)
        assertThat(reading.thresholdDbfs).isLessThan(reading.speechDbfs!!)
        assertThat(reading.voice).isTrue()
    }

    @Test
    fun `a fresh session reads the threshold the fixed window used to demand`() {
        val s = session(TestCaptureSource(List(2) { frameAt(-80f) }))
        s.start()
        await { readings.isNotEmpty() }
        s.stop()
        // One frame of -80 dBFS has already pulled the floor down by the 0.24 dB it may move in
        // 10 ms, which is the tracker working rather than a tolerance for noise.
        assertThat(readings.first().floorDbfs!!).isWithin(0.3f).of(AdaptiveVadTracker.DEFAULT_FLOOR_DBFS)
        assertThat(readings.first().thresholdDbfs!!).isWithin(0.3f).of(-32f)
    }

    /** The three zones the bar draws: over the threshold, held open, and shut. */
    @Test
    fun `the held zone is reported separately from the detected zone`() {
        val frames = List(3) { frameAt(-15f) } + List(6) { frameAt(-50f) }
        val s = session(
            TestCaptureSource(frames, loopLastFrame = true),
            vad = VadConfig.adaptive(holdTimeMs = 2000, onsetFrames = 1),
        )
        s.start()
        await { readings.size >= 9 }
        s.stop()

        assertThat(readings.first().voice).isTrue()
        assertThat(readings.first().holding).isFalse()
        val held = readings.first { it.voice && it.holding }
        assertThat(held.levelDbfs).isLessThan(held.thresholdDbfs!!)
    }

    @Test
    fun `too far away is reported rather than hidden`() {
        val s = session(TestCaptureSource(List(3) { frameAt(-80f) }))
        s.start()
        await { readings.isNotEmpty() }
        s.stop()
        // A fresh tracker assumes a 20 dB gap, so nothing is "too close" until it has learned.
        assertThat(readings.first().tooClose).isFalse()
    }

    /**
     * Spec B1: the preprocessor runs in front of the detector, so the number the user calibrates
     * against is the denoised one. A meter that measured the raw frame would show a level the gate
     * never sees, which is the migration this screen exists to explain.
     */
    @Test
    fun `the meter measures the frame the gate measures, after the preprocessor`() {
        val raw = session(TestCaptureSource(List(3) { frameAt(-20f) }))
        raw.start()
        await { readings.isNotEmpty() }
        raw.stop()
        val rawLevel = readings.first().levelDbfs

        readings.clear()
        // A denoiser that halves every sample is exactly -6.02 dB.
        val denoised = session(
            TestCaptureSource(List(3) { frameAt(-20f) }),
            noise = NoiseSuppressionMode.SPEEX,
            speexGain = 0.5f,
        )
        denoised.start()
        await { readings.isNotEmpty() }
        denoised.stop()
        assertThat(readings.first().levelDbfs).isWithin(0.05f).of(rawLevel - 6.02f)
    }

    @Test
    fun `the amplitude mode draws the legacy slider position as a level`() {
        val s = session(
            TestCaptureSource(List(3) { frameAt(-20f) }),
            vad = VadConfig.amplitude(0.7f, holdTimeMs = 0, onsetFrames = 1),
        )
        s.start()
        await { readings.isNotEmpty() }
        s.stop()
        // A score of 0.7 on the 1 + dBFS/96 curve is -28.8 dBFS.
        assertThat(readings.first().thresholdDbfs!!).isWithin(0.01f).of(-28.8f)
        assertThat(readings.first().floorDbfs).isNull()
        assertThat(readings.first().speechDbfs).isNull()
    }

    @Test
    fun `the probability mode draws no level threshold, because it does not use one`() {
        val s = session(
            TestCaptureSource(List(3) { frameAt(-20f) }),
            vad = VadConfig.probability(holdTimeMs = 0, onsetFrames = 1),
            noise = NoiseSuppressionMode.SPEEX,
            speexProbability = 90,
        )
        s.start()
        await { readings.isNotEmpty() }
        s.stop()
        assertThat(readings.first().thresholdDbfs).isNull()
        assertThat(readings.first().voice).isTrue()
    }

    @Test
    fun `the reading interval decides how many readings a run produces`() {
        val source = TestCaptureSource(List(40) { frameAt(-20f) })
        val s = AudioTestSession(
            audioManager, VadConfig.adaptive(), NoiseSuppressionMode.NONE, -25, EchoCancellationMode.NONE,
            AndroidAudioEffects(), false, { readings += it }, readingIntervalFrames = 10,
            captureFactory = TestCaptureSource.Factory(source),
            sinkFactory = TestPlaybackSink.Factory(TestPlaybackSink()),
            preprocessorFactory = preprocessors(),
        ) { _, _ -> error("no resampler expected") }
        session = s
        s.start()
        await { source.events.contains("start") }
        Thread.sleep(200)
        s.stop()
        assertThat(readings.size).isEqualTo(4)
    }

    @Test
    fun `recalibrating forgets what the session had learned`() {
        val frames = List(100) { frameAt(-10f) } + List(2000) { frameAt(-70f) }
        val s = session(TestCaptureSource(frames, loopLastFrame = true))
        s.start()
        // A close, loud talker leaves a gap far wider than the one a fresh tracker assumes.
        await { readings.any { it.speechDbfs!! - it.floorDbfs!! > 30f } }
        await { readings.last().levelDbfs < -60f }

        readings.clear()
        s.recalibrate()
        // The reset lands on the capture thread's next frame, so the assertion is over the
        // readings rather than over an index: two frames may already have been in flight.
        await { readings.any { (it.speechDbfs!! - it.floorDbfs!!) < AdaptiveVadTracker.DEFAULT_GAP_DB + 0.5f } }
        s.stop()
        val reset = readings.first { (it.speechDbfs!! - it.floorDbfs!!) < AdaptiveVadTracker.DEFAULT_GAP_DB + 0.5f }
        assertThat(reset.speechDbfs!! - reset.floorDbfs!!)
            .isWithin(0.5f).of(AdaptiveVadTracker.DEFAULT_GAP_DB)
    }

    // --- the hardware edges --------------------------------------------------------------------

    @Test
    fun `without loopback no playback sink is opened`() {
        val factory = TestPlaybackSink.Factory(TestPlaybackSink())
        val s = session(TestCaptureSource(emptyList()), loopback = false, sinkFactory = factory)
        s.start()
        s.stop()
        assertThat(factory.openedWith).isNull()
    }

    @Test
    fun `loopback plays the frames that would be transmitted and silence otherwise`() {
        val sink = TestPlaybackSink()
        val s = session(
            TestCaptureSource(listOf(frameAt(-6f), frameAt(-80f)), loopLastFrame = false),
            sink = sink,
            loopback = true,
            vad = VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1),
        )
        s.start()
        await { sink.written.size >= 2 }
        s.stop()
        assertThat(sink.written[0].any { it != 0.toShort() }).isTrue()
        assertThat(sink.written[1].toSet()).containsExactly(0.toShort())
        assertThat(sink.events.first()).isEqualTo("play")
    }

    @Test
    fun `loopback opens the music stream at the codec rate`() {
        val factory = TestPlaybackSink.Factory(TestPlaybackSink())
        val s = session(TestCaptureSource(emptyList()), loopback = true, sinkFactory = factory)
        s.start()
        s.stop()
        assertThat(factory.openedWith).isEqualTo(AudioManager.STREAM_MUSIC to 48000)
    }

    @Test
    fun `stop pauses before flushing and releases both the microphone and the sink`() {
        val source = TestCaptureSource(emptyList())
        val sink = TestPlaybackSink()
        val s = session(source, sink = sink, loopback = true)
        s.start()
        await { "start" in source.events }
        s.stop()
        assertThat(source.events.last()).isEqualTo("release")
        assertThat(sink.events.takeLast(4)).containsExactly("pause", "flush", "stop", "release").inOrder()
    }

    @Test
    fun `the capture request carries the settings the service would use`() {
        val factory = TestCaptureSource.Factory(TestCaptureSource(emptyList()))
        val s = session(
            TestCaptureSource(emptyList()),
            effects = AndroidAudioEffects(noiseSuppressor = true, automaticGainControl = true),
            echo = EchoCancellationMode.WEBRTC,
            captureFactory = factory,
        )
        s.start()
        s.stop()
        val request = factory.request!!
        assertThat(request.audioSource).isEqualTo(MediaRecorder.AudioSource.MIC)
        assertThat(request.targetSampleRate).isEqualTo(48000)
        assertThat(request.effects.noiseSuppressor).isTrue()
        assertThat(request.effects.automaticGainControl).isTrue()
        assertThat(request.echo).isEqualTo(EchoCancellationMode.WEBRTC)
    }

    /** Spec B6: the preview must route capture the same way the service will, or it lies. */
    @Test
    fun `an effect that needs communication mode sets and restores the audio manager mode`() {
        val s = session(
            TestCaptureSource(emptyList()),
            effects = AndroidAudioEffects(noiseSuppressor = true),
        )
        s.start()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
        s.stop()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    @Test
    fun `plain capture leaves the audio manager mode alone`() {
        val s = session(TestCaptureSource(emptyList()))
        s.start()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
        s.stop()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    @Test
    fun `a source that opened at another rate gets a resampler`() {
        val built = mutableListOf<Pair<Int, Int>>()
        val s = session(
            TestCaptureSource(List(3) { ShortArray(160) { 3000 } }, sampleRate = 16000),
            resamplerFactory = { from, to ->
                built += from to to
                object : Resampler {
                    override fun resample(input: ShortArray, inputLength: Int, output: ShortArray): Int {
                        output.fill(3000)
                        return output.size
                    }

                    override fun release() = Unit
                }
            },
        )
        s.start()
        await { readings.isNotEmpty() }
        s.stop()
        assertThat(built).containsExactly(16000 to 48000)
    }

    /**
     * A recorder that is open and a session that never started is a microphone taken for the life
     * of the process. Measured by the fake: `release` has to have been called.
     */
    @Test
    fun `a failure after the recorder is open still releases it`() {
        val source = TestCaptureSource(emptyList())
        val s = AudioTestSession(
            audioManager, VadConfig.adaptive(), NoiseSuppressionMode.NONE, -25, EchoCancellationMode.NONE,
            AndroidAudioEffects(noiseSuppressor = true), true, { readings += it }, 1,
            TestCaptureSource.Factory(source),
            { _, _ -> throw IllegalStateException("no track") },
            preprocessors(),
        ) { _, _ -> error("no resampler expected") }
        try {
            s.start()
            throw AssertionError("expected the sink failure to escape")
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().isEqualTo("no track")
        }
        assertThat(source.events).contains("release")
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    @Test
    fun `starting twice is refused rather than taking the microphone twice`() {
        val s = session(TestCaptureSource(emptyList()))
        s.start()
        try {
            s.start()
            throw AssertionError("expected a refusal")
        } catch (e: IllegalStateException) {
            assertThat(e).hasMessageThat().contains("already started")
        }
        s.stop()
    }
}
