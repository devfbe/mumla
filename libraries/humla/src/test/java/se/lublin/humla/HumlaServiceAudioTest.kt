/*
 * Copyright (C) 2026 The Mumla authors
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

package se.lublin.humla

import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.capture.VadConfigBundle
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.session.AudioController
import se.lublin.humla.testutil.HumlaServiceHarness
import se.lublin.humla.testutil.awaitUntil
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Spec A2 and A8: the audio pipeline is built and torn down on `humla-audio-control`, never on the
 * main thread, and everything it cannot do reaches the chat log instead of a log file nobody reads.
 *
 * The teardown is the reason this matters on a phone: `AudioHandler.shutdown()` joins the capture
 * and playback threads, and the capture thread can be a full frame away from noticing. On the main
 * looper that is an ANR; the same call on the control thread is invisible.
 */
@RunWith(RobolectricTestRunner::class)
class HumlaServiceAudioTest {
    private val harnesses = mutableListOf<HumlaServiceHarness>()

    @After
    fun tearDown() {
        harnesses.forEach { it.destroy() }
    }

    private fun start(): HumlaServiceHarness = HumlaServiceHarness().also { harnesses += it }

    private fun audioUp(h: HumlaServiceHarness, count: Int = 1) =
        awaitUntil(description = "audio pipeline $count") {
            h.mainLooper.idle()
            h.audioFactory.created.size == count
        }

    // ---------------------------------------------------------------- the pipeline's lifecycle

    @Test
    fun audioIsBuiltOnTheControlThreadWithTheSessionUser() {
        val h = start()
        h.connectAndSynchronize()

        audioUp(h)
        assertThat(h.audioFactory.createThreads.single()).isEqualTo(AudioController.THREAD_NAME)
        assertThat(h.audioFactory.sessionParams[0].self.getName()).isEqualTo("me")
        assertThat(h.audioFactory.sessionParams[0].maxBandwidth).isEqualTo(72_000)
        assertThat(h.service.getCurrentBandwidth()).isEqualTo(12_345)
    }

    /** Spec section 6 regression test: "no main-thread join in disconnect". */
    @Test
    fun disconnectDoesNotBlockTheMainThreadWhileAudioTearsDown() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)
        val audio = h.audioFactory.created[0]
        val gate = CountDownLatch(1)
        audio.shutdownGate = gate

        val started = System.nanoTime()
        h.service.disconnect()
        h.mainLooper.idle()
        val blockedMillis = (System.nanoTime() - started) / 1_000_000

        awaitUntil(description = "shutdown started") { audio.shutdownThread != null }
        assertThat(audio.shutdownThread).isEqualTo(AudioController.THREAD_NAME)
        assertThat(audio.shutdownCalls.get()).isEqualTo(0) // still inside shutdown()

        // The main looper keeps running while the audio thread is stuck in the join.
        val probeRan = AtomicBoolean(false)
        Handler(Looper.getMainLooper()).post { probeRan.set(true) }
        h.mainLooper.idle()
        assertThat(probeRan.get()).isTrue()
        assertThat(blockedMillis).isLessThan(200)

        gate.countDown()
        awaitUntil(description = "shutdown finished") { audio.shutdownCalls.get() == 1 }
    }

    @Test
    fun aLostConnectionTearsThePipelineDown() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)

        h.failConnection(
            0,
            se.lublin.humla.util.HumlaException(
                "gone",
                se.lublin.humla.util.HumlaException.HumlaDisconnectReason.CONNECTION_ERROR,
            ),
        )

        awaitUntil(description = "pipeline stopped") { h.audioFactory.created[0].shutdownCalls.get() == 1 }
        assertThat(h.service.getCurrentBandwidth()).isEqualTo(-1)
    }

    // ---------------------------------------------------------------- spec A8: problems are visible

    @Test
    fun audioCreationFailureIsLoggedAsAWarning() {
        val h = start()
        h.audioFactory.failWith = AudioInitializationException("no microphone")

        h.connectAndSynchronize()
        awaitUntil(description = "the failure reaches the chat log") {
            h.mainLooper.idle()
            h.warnings.contains("no microphone")
        }

        // A microphone that cannot open is not a reason to drop the session.
        assertThat(h.service.getConnectionState()).isEqualTo(HumlaService.ConnectionState.CONNECTED)
    }

    @Test
    fun anAudioWarningReachesTheChatLog() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)

        h.audioFactory.created[0].warningListener!!.invoke("microphone silenced by the system")
        awaitUntil(description = "the warning reaches the chat log") {
            h.mainLooper.idle()
            h.warnings.contains("microphone silenced by the system")
        }
    }

    @Test
    fun aConnectionWarningReachesTheChatLog() {
        val h = start()
        h.connectAndSynchronize()
        awaitUntil(description = "udp transport") { h.mainLooper.idle(); h.transports.udps.isNotEmpty() }

        h.transports.udps[0].simulateError(IOException("network unreachable"))
        awaitUntil(description = "the warning reaches the chat log") {
            h.mainLooper.idle()
            h.warnings.contains(h.service.getString(R.string.udp_warning_thread_failed))
        }
    }

    // ---------------------------------------------------------------- the settings that rebuild

    @Test
    fun changingAnAudioExtraWhileConnectedRebuildsThePipeline() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)

        h.service.configureExtras(
            Bundle().apply {
                putInt(HumlaService.EXTRAS_AUDIO_SOURCE, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            },
        )

        audioUp(h, count = 2)
        assertThat(h.audioFactory.configs[1].audioSource)
            .isEqualTo(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        awaitUntil(description = "the old pipeline is gone") {
            h.audioFactory.created[0].shutdownCalls.get() == 1
        }
    }

    /**
     * The rebuild is decided by the *value*, not by the key: re-writing a setting the pipeline
     * already has costs a measured 110 ms with the microphone dead in the middle of it. This
     * replaces `HumlaService.requiresAudioRebuild`, which answered by key and so rebuilt for a
     * write that changed nothing.
     */
    @Test
    fun anExtraWrittenWithTheSameValueDoesNotRebuildThePipeline() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)
        val sourceInUse = h.audioFactory.configs[0].audioSource

        h.service.configureExtras(Bundle().apply { putInt(HumlaService.EXTRAS_AUDIO_SOURCE, sourceInUse) })
        h.mainLooper.idle()
        awaitUntil(description = "the reconfigure was processed") { h.service.getCurrentBandwidth() == 12_345 }

        assertThat(h.audioFactory.created).hasSize(1)
        assertThat(h.audioFactory.created[0].shutdownCalls.get()).isEqualTo(0)
    }

    /**
     * The two extras that write into objects a rebuild keeps - the detection threshold and the
     * whole VAD configuration - reach a live [se.lublin.humla.audio.inputmode.ActivityInputMode]
     * and must not tear the capture chain down. Dragging a slider used to rebuild per step.
     */
    @Test
    fun aLiveExtraDoesNotRebuildThePipeline() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)

        h.service.configureExtras(Bundle().apply { putFloat(HumlaService.EXTRAS_DETECTION_THRESHOLD, 0.25f) })
        h.service.configureExtras(
            Bundle().apply {
                putBundle(
                    HumlaService.EXTRAS_VAD_CONFIG,
                    VadConfigBundle.toBundle(VadConfig.amplitude(0.8f, 120L)),
                )
            },
        )
        h.mainLooper.idle()
        awaitUntil(description = "the reconfigure was processed") { h.service.getCurrentBandwidth() == 12_345 }

        assertThat(h.audioFactory.created).hasSize(1)
        assertThat(h.audioFactory.created[0].shutdownCalls.get()).isEqualTo(0)
    }

    /**
     * Spec A7, and the repair of an old defect A9a pinned: `EXTRAS_HALF_DUPLEX` used to read
     * `EXTRAS_TRANSMIT_MODE` out of **its own bundle**, which answers 0 (voice activity) when the
     * bundle does not carry it - so a settings write that changed only half duplex always resolved
     * to false. The rule now reads the mode that is in force.
     */
    @Test
    fun halfDuplexOnlyAppliesToPushToTalk() {
        val h = start()

        h.service.configureExtras(
            Bundle().apply {
                putBoolean(HumlaService.EXTRAS_HALF_DUPLEX, true)
                putInt(HumlaService.EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_VOICE_ACTIVITY)
            },
        )
        assertThat(h.service.getAudioConfigForTest().halfDuplex).isFalse()

        // Switching the transmit mode alone re-evaluates it; the caller does not resend the flag.
        h.service.configureExtras(
            Bundle().apply { putInt(HumlaService.EXTRAS_TRANSMIT_MODE, Constants.TRANSMIT_PUSH_TO_TALK) },
        )
        assertThat(h.service.getAudioConfigForTest().halfDuplex).isTrue()

        // And a half-duplex write that carries no mode no longer resolves to false by accident.
        h.service.configureExtras(Bundle().apply { putBoolean(HumlaService.EXTRAS_HALF_DUPLEX, true) })
        assertThat(h.service.getAudioConfigForTest().halfDuplex).isTrue()
    }

    // ---------------------------------------------------------------- voice targets

    /**
     * The target reaches the running pipeline **and** the session behind it, so the next rebuild
     * starts out targeting it instead of transmitting to the channel until something repeats the
     * call. Setting one while disconnected used to be a NullPointerException (A9a pinned it); it
     * is now a no-op, and a connect clears the target anyway - the whisper slots go with it.
     */
    @Test
    fun theVoiceTargetReachesTheRunningPipelineAndSurvivesARebuild() {
        val h = start()
        h.connectAndSynchronize()
        audioUp(h)

        h.service.setVoiceTargetId(0x1F)

        assertThat(h.service.getVoiceTargetId()).isEqualTo(0x1F.toByte())
        awaitUntil(description = "the target reaches the pipeline") {
            h.audioFactory.created[0].targetIds.contains(0x1F.toByte())
        }

        h.service.configureExtras(
            Bundle().apply {
                putInt(HumlaService.EXTRAS_AUDIO_SOURCE, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            },
        )

        audioUp(h, count = 2)
        assertThat(h.audioFactory.sessionParams[1].targetId).isEqualTo(0x1F.toByte())
    }

    /** Setting one while there is no pipeline is remembered and no longer a crash. */
    @Test
    fun aVoiceTargetSetWhileDisconnectedIsHarmless() {
        val h = start()

        h.service.setVoiceTargetId(3)

        assertThat(h.service.getVoiceTargetId()).isEqualTo(3.toByte())
        assertThat(h.service.getVoiceTargetMode())
            .isEqualTo(se.lublin.humla.util.VoiceTargetMode.WHISPER)
    }

    /** The five-bit guard, repaired: `> 0` let a negative byte through, `!= 0` does not. */
    @Test
    fun aVoiceTargetIdThatDoesNotFitInFiveBitsIsRefused() {
        val h = start()

        for (id in listOf(0x20, 0x80, 0xFF)) {
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                h.service.setVoiceTargetId(id.toByte())
            }
        }
        assertThat(h.service.getVoiceTargetId()).isEqualTo(0.toByte())
    }
}
