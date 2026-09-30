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

package se.lublin.humla.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.CapturePreprocessorFactory
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.fakes.FakeRnnoiseApi
import se.lublin.humla.audio.capture.fakes.FakeSpeexPreprocessApi
import se.lublin.humla.audio.capture.fakes.FakeWebRtcApmApi
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.testutil.FakeCommunicationDevices
import se.lublin.humla.testutil.TestCaptureSource
import se.lublin.humla.testutil.awaitUntil
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The double-talk self-test engine: its route and the restore, the far end fed to the canceller
 * before each write, the lamp following the user's gate, and the two counts of its result.
 */
@RunWith(RobolectricTestRunner::class)
class DoubleTalkSelfTestTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val devices = FakeCommunicationDevices().apply {
        available[EARPIECE] = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        available[SPEAKER] = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }
    private val readings = CopyOnWriteArrayList<SelfTestReading>()
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val apm = FakeWebRtcApmApi(levelDbfs = -20f, onRender = { events += "reference" })
    private val rnnoise = FakeRnnoiseApi(probability = 0.9f)
    private val sink = PacedSink(events)
    private var test: DoubleTalkSelfTest? = null

    @After
    fun stopTest() {
        test?.stop()
    }

    /** A track whose write takes a millisecond, so the playback thread does not spin; it keeps no audio. */
    private class PacedSink(private val events: MutableList<String>) : PcmPlaybackSink {
        val writes = AtomicInteger()
        val calls: MutableList<String> = CopyOnWriteArrayList()
        var openedWith: Pair<Int, Int>? = null

        override fun play() {
            calls += "play"
        }

        override fun write(buffer: ShortArray, length: Int): Int {
            if (writes.incrementAndGet() <= RECORDED_WRITES) events += "write"
            Thread.sleep(1)
            return length
        }

        override fun pause() {
            calls += "pause"
        }

        override fun flush() {
            calls += "flush"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun release() {
            calls += "release"
        }
    }

    @Suppress("LongParameterList") // Every setting a test varies.
    private fun selfTest(
        mic: List<ShortArray>,
        echo: EchoCancellationMode = EchoCancellationMode.WEBRTC,
        noise: NoiseSuppressionMode = NoiseSuppressionMode.RNNOISE,
        listenFrames: Int = 150,
        limitDb: Float = 18f,
        sinkFactory: PcmPlaybackSinkFactory = PcmPlaybackSinkFactory { stream, rate ->
            sink.openedWith = stream to rate
            sink
        },
        source: TestCaptureSource = TestCaptureSource(mic, loopLastFrame = true),
    ) = DoubleTalkSelfTest(
        audioManager, devices, VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1), noise, -25, echo,
        AndroidAudioEffects(), limitDb, CLIP, { readings += it },
        TestCaptureSource.Factory(source), sinkFactory,
        CapturePreprocessorFactory({ FakeSpeexPreprocessApi() }, { rnnoise }, { apm }),
        { _, _ -> error("no resampler expected at 48 kHz") },
        readingIntervalFrames = 1,
        listenFrames = listenFrames,
    ).also { test = it }

    @Test
    fun `the test plays on the speaker in communication mode and puts the route back`() {
        devices.select(EARPIECE)
        audioManager.mode = AudioManager.MODE_NORMAL
        val s = selfTest(listOf(frameAt(-20f)))

        s.start()
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
        assertThat(devices.selectedId).isEqualTo(SPEAKER)
        s.stop()

        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
        assertThat(devices.selectedId).isEqualTo(EARPIECE)
    }

    @Test
    fun `a route the platform chose itself is handed back to it`() {
        val s = selfTest(listOf(frameAt(-20f)))

        s.start()
        s.stop()

        assertThat(devices.selectedId).isNull()
        assertThat(devices.clearCalls).isEqualTo(1)
    }

    @Test
    fun `the voice plays on the voice-call stream at 48 kHz, like a remote user`() {
        val s = selfTest(listOf(frameAt(-20f)))
        s.start()
        awaitUntil { sink.writes.get() > 3 }
        s.stop()

        assertThat(sink.openedWith).isEqualTo(AudioManager.STREAM_VOICE_CALL to 48000)
        assertThat(sink.calls.first()).isEqualTo("play")
        assertThat(sink.calls.takeLast(4)).containsExactly("pause", "flush", "stop", "release").inOrder()
    }

    /** As `AudioOutput` does it: a late reference would misalign the echo estimate. */
    @Test
    fun `every played frame reaches the canceller before its write`() {
        val s = selfTest(listOf(frameAt(-20f)))
        s.start()
        awaitUntil { sink.writes.get() > RECORDED_WRITES }
        s.stop()

        val recorded = synchronized(events) { events.filter { it == "reference" || it == "write" }.toList() }
            .take(2 * RECORDED_WRITES)
        assertThat(recorded).isEqualTo(List(RECORDED_WRITES) { listOf("reference", "write") }.flatten())
        assertThat(apm.renderFrames.first()).isEqualTo(CLIP.copyOf(480))
    }

    @Test
    fun `without echo cancellation the test still runs and says so`() {
        val s = selfTest(listOf(frameAt(-20f)), echo = EchoCancellationMode.NONE)
        s.start()
        awaitUntil { readings.size > 3 }
        s.stop()

        assertThat(s.echoCancelled).isFalse()
        assertThat(apm.createdWith).isNull()
    }

    @Test
    fun `the lamp follows the user's gate`() {
        val loud = selfTest(listOf(frameAt(-10f)))
        loud.start()
        awaitUntil { readings.size > 20 }
        loud.stop()
        assertThat(readings.last().meter.voice).isTrue()

        readings.clear()
        val quiet = selfTest(listOf(frameAt(-90f)))
        quiet.start()
        awaitUntil { readings.size > 20 }
        quiet.stop()
        assertThat(readings.last().meter.voice).isFalse()
    }

    /**
     * The whole clip is voiced, so every frame counts as "the voice plays". A loud microphone opens
     * both gates: in the listening phase that is a false opening, afterwards it is the user heard.
     */
    @Test
    fun `the listening phase counts false openings and the talking phase counts the user heard`() {
        val s = selfTest(listOf(frameAt(-10f)), listenFrames = 160)
        s.start()
        awaitUntil { readings.any { it.phase == SelfTestPhase.TALK && it.heardPercent != null } }
        s.stop()

        val listening = readings.last { it.phase == SelfTestPhase.LISTEN }
        assertThat(listening.voicePlaying).isTrue()
        assertThat(listening.falseOpenPercent).isEqualTo(100)
        assertThat(listening.heardPercent).isNull()
        assertThat(readings.last().heardPercent).isEqualTo(100)
    }

    @Test
    fun `a silent user never opens the gate, so nothing opens falsely and nobody is heard`() {
        val s = selfTest(listOf(frameAt(-90f)), listenFrames = 160)
        s.start()
        awaitUntil { readings.any { it.phase == SelfTestPhase.TALK } && readings.size > 250 }
        s.stop()

        assertThat(readings.last { it.phase == SelfTestPhase.LISTEN }.falseOpenPercent).isEqualTo(0)
        // The reference gate never heard the user, so there is nothing to put a share on.
        assertThat(readings.last().heardPercent).isNull()
    }

    @Test
    fun `too few frames give no percentage rather than a noisy one`() {
        val s = selfTest(listOf(frameAt(-10f)), listenFrames = 100_000)
        s.start()
        awaitUntil { readings.size > 20 }
        s.stop()

        assertThat(readings.first().falseOpenPercent).isNull()
    }

    @Test
    fun `a new strength reaches rnnoise at once and starts the measurement over`() {
        val s = selfTest(listOf(frameAt(-10f)), listenFrames = 160)
        s.start()
        awaitUntil { readings.any { it.phase == SelfTestPhase.TALK } }
        assertThat(s.rnnoiseLimitDb).isEqualTo(18f)

        s.setAttenuationLimitDb(Float.POSITIVE_INFINITY)
        assertThat(s.rnnoiseLimitDb).isEqualTo(Float.POSITIVE_INFINITY)
        val before = readings.size
        awaitUntil { readings.size > before + 5 }
        s.stop()

        assertThat(readings.drop(before + 2).first().phase).isEqualTo(SelfTestPhase.LISTEN)
        assertThat(rnnoise.created).isEqualTo(1)
    }

    @Test
    fun `a speaker that cannot be opened releases the microphone and restores the route`() {
        devices.select(EARPIECE)
        val source = TestCaptureSource(emptyList())
        val s = selfTest(
            emptyList(),
            sinkFactory = { _, _ -> throw AudioInitializationException("no track") },
            source = source,
        )

        assertThrows(AudioInitializationException::class.java) { s.start() }

        assertThat(source.events).contains("release")
        assertThat(apm.destroyed).isEqualTo(1)
        assertThat(rnnoise.destroyed).isEqualTo(1)
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
        assertThat(devices.selectedId).isEqualTo(EARPIECE)
    }

    @Test
    fun `starting twice is refused`() {
        val s = selfTest(listOf(frameAt(-20f)))
        s.start()
        assertThat(assertThrows(IllegalStateException::class.java) { s.start() }).hasMessageThat()
            .contains("already started")
    }

    @Test
    fun `the voice counts as playing only while it speaks and for its echo tail`() {
        val voice = ShortArray(480 * 200)
        // Frames 10..19 voiced, the rest digital silence.
        for (i in 10 * 480 until 20 * 480) voice[i] = if (i % 2 == 0) 8000 else -8000
        val active = DoubleTalkSelfTest.activeFrames(voice)

        assertThat(active[9]).isFalse()
        assertThat(active[10]).isTrue()
        assertThat(active[19 + 50]).isTrue()
        assertThat(active[19 + 51]).isFalse()
    }

    private companion object {
        const val EARPIECE = 1
        const val SPEAKER = 2
        const val RECORDED_WRITES = 20

        /** Two seconds of a loud, never silent test voice. */
        val CLIP = ShortArray(2 * 48000) { if (it % 2 == 0) 6000 else -6000 }
    }
}
