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

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.lublin.humla.audio.capture.AndroidAudioEffects
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.SpeechCorpus
import se.lublin.humla.audio.capture.VadConfig
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The talk-over self-test engine ([DoubleTalkSelfTest]) on the phone, with a real near-end talker:
 * the phone plays a Piper voice on its speaker as the test voice while the PC speaker next to it
 * plays another Piper voice as the user. The lamp must stay dark while the voice speaks alone and
 * light up for most of the near-end speech over it.
 *
 * **Manual test, skipped unless enabled**, like `RoomAcousticsDeviceTest`: it needs the PC speaker
 * and `tools/room-test/room_test.py --selftest`, which sets [ENABLE_PROPERTY]
 * (`debug.mumla.selftest`), plays `near_en_f.wav` once on every `SELFTEST_TRIGGER` line (tag [TAG])
 * and clears the property at `SELFTEST_DONE`. The instrumentation argument `selfTest=true` works
 * too. Setup: phone next to the PC speaker, no headset, a quiet room.
 *
 * Timeline: the engine starts with the shipped settings (WEBRTC echo cancellation, RNNoise at
 * 18 dB, the app's adaptive gate); its listening phase ([LISTEN_FRAMES]) has the test voice alone;
 * then the trigger, and [TALK_SECONDS] of the PC talking over it.
 *
 * Not yet measured: the phone left the bench before the first run. [MIN_HEARD] and [MAX_FALSE_OPEN]
 * are provisional, derived from the room test (18 dB: 91-97 % of voiced near-end frames heard,
 * 0 % false open on the app's configuration); set them from the first run's logged numbers.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class DoubleTalkSelfTestDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var prepared = false
    private var savedVolume = 0
    private var savedAppOp = "default"
    private var test: DoubleTalkSelfTest? = null

    @Before
    fun setUp() {
        assumeTrue(
            "the self-test run needs the PC speaker; enable it with -e selfTest true or " +
                "`adb shell setprop $ENABLE_PROPERTY true` (tools/room-test/room_test.py --selftest does)",
            argument("selfTest", ENABLE_PROPERTY) == "true",
        )
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        savedAppOp = APP_OP_MODE.find(shell("appops get ${context.packageName} RECORD_AUDIO"))
            ?.groupValues?.get(1) ?: "default"
        // The test process has no activity; without the app op its recording would be silenced.
        shell("appops set ${context.packageName} RECORD_AUDIO allow")
        savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        audioManager.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL, audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0,
        )
        prepared = true
    }

    @After
    fun restore() {
        test?.stop()
        if (!prepared) return
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0)
        shell("appops set ${context.packageName} RECORD_AUDIO $savedAppOp")
        log("SELFTEST_DONE")
    }

    @Test
    fun theLampHearsTheNearEndAndIgnoresTheVoiceAlone() {
        val readings = CopyOnWriteArrayList<SelfTestReading>()
        val modeBefore = audioManager.mode
        val selfTest = DoubleTalkSelfTest(
            audioManager,
            VadConfig.adaptive(onsetFrames = APP_ONSET_FRAMES),
            NoiseSuppressionMode.RNNOISE,
            PipelineSettings().speexNoiseSuppressDb,
            EchoCancellationMode.WEBRTC,
            AndroidAudioEffects(),
            PipelineSettings.DEFAULT_RNNOISE_ATTENUATION_LIMIT_DB,
            testVoice(),
        ) { readings += it }
        test = selfTest
        selfTest.start()
        awaitPhase(readings, SelfTestPhase.TALK)
        val listening = readings.last { it.phase == SelfTestPhase.LISTEN }
        val talkFrom = readings.size
        log("SELFTEST_TRIGGER near end")
        Thread.sleep(TALK_SECONDS * MS_PER_SECOND)
        selfTest.stop()
        test = null
        val talking = readings.drop(talkFrom)
        val lampDuringTalk = talking.count { it.meter.voice }.toFloat() / talking.size
        log(
            String.format(
                Locale.ROOT,
                "SELFTEST voice alone: false open %s%%; talk-over: heard %s%%, lamp on %.0f%% of %d readings; " +
                    "mode before %d, after %d",
                listening.falseOpenPercent, talking.last().heardPercent, 100 * lampDuringTalk, talking.size,
                modeBefore, audioManager.mode,
            ),
        )
        assertWithMessage("audio mode restored").that(audioManager.mode).isEqualTo(modeBefore)
        assertWithMessage("gate opened on the voice alone (%)").that(listening.falseOpenPercent ?: PERCENT)
            .isAtMost(MAX_FALSE_OPEN)
        assertWithMessage("near end heard while talking over the voice (%)").that(talking.last().heardPercent ?: 0)
            .isAtLeast(MIN_HEARD)
    }

    private fun awaitPhase(readings: List<SelfTestReading>, phase: SelfTestPhase) {
        val deadline = System.nanoTime() + PHASE_TIMEOUT_MS * NANOS_PER_MS
        while (readings.lastOrNull()?.phase != phase) {
            check(System.nanoTime() < deadline) { "the self-test never reached $phase" }
            Thread.sleep(POLL_MS)
        }
    }

    /** The far-end Piper voice at an active level of [VOICE_DBFS], as the app's voice is mastered. */
    private fun testVoice(): ShortArray {
        val clip = SpeechCorpus.load(SpeechCorpus.Clip.FAR_EN_M)
        val frame = AudioHandler.FRAME_SIZE
        val levels = DoubleArray(clip.size / frame) { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += clip[i].toDouble() * clip[i]
            sum / frame
        }
        val floor = levels.max() / ACTIVE_RANGE
        val rms = sqrt(levels.filter { it > floor }.average())
        val gain = 10.0.pow(VOICE_DBFS / 20.0) / rms
        return ShortArray(clip.size) {
            (clip[it] * gain * FULL_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    private fun argument(name: String, property: String): String =
        InstrumentationRegistry.getArguments().getString(name) ?: shell("getprop $property").trim()

    private fun shell(command: String): String {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { String(it.readBytes()) }
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println(line)
    }

    private companion object {
        const val TAG = "SelfTest"
        const val ENABLE_PROPERTY = "debug.mumla.selftest"
        const val APP_ONSET_FRAMES = 2
        const val TALK_SECONDS = 14L
        const val MS_PER_SECOND = 1000L
        const val NANOS_PER_MS = 1_000_000L
        const val PHASE_TIMEOUT_MS = 30_000L
        const val POLL_MS = 50L
        const val PERCENT = 100
        const val FULL_SCALE = 32768.0
        const val ACTIVE_RANGE = 100.0 // 20 dB, as a power ratio
        const val VOICE_DBFS = -18.0

        /** Bounds around the SM-S938B's numbers; see the class comment. */
        const val MAX_FALSE_OPEN = 10
        const val MIN_HEARD = 70

        val APP_OP_MODE = Regex("""(?m)^RECORD_AUDIO: (\w+)""")
    }
}
