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

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
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
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.Correlation
import se.lublin.humla.audio.native.WebRtcApmNative
import se.lublin.humla.audio.routing.AndroidCommunicationDevices
import se.lublin.humla.audio.routing.AudioRouter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A real room: the phone plays a far-end talker on its speaker the way the app plays a remote user,
 * while a PC speaker next to it plays the near-end talker (the person in the room), and every
 * candidate capture configuration records both. Each recording then runs, offline, through the
 * app's own WEBRTC chain (AEC3 + AGC2 + high-pass, then RNNoise, then the adaptive VAD), fed the
 * far-end reference in the same order the app feeds it. Answers: which capture source and audio
 * mode lets the near end break in on speakerphone without the far end hearing its own echo?
 *
 * **Manual test, skipped unless enabled.** It needs the PC speaker and the host script
 * `tools/room-test/room_test.py`, so it is compiled with every build but runs only when the
 * instrumentation argument `roomTest` is `true`, or, because Studio's run configurations cannot
 * pass arguments through its MCP, when the system property [ENABLE_PROPERTY] is `true`
 * (`adb shell setprop debug.mumla.roomtest true`; the host script sets and clears it).
 * [ONLY_PROPERTY] (or the argument `roomTestOnly`) optionally limits the run to configurations
 * whose name contains one of its comma-separated parts.
 *
 * Procedure:
 * 1. Lay the phone next to the PC speaker, no headset connected, a quiet room.
 * 2. Start `tools/room-test/room_test.py` on the PC. It builds the near-end file (the Piper clip
 *    [NEAR_CLIP] twice, the second copy [NEAR_B_OFFSET_SECONDS] after the first), sets the
 *    property and watches logcat.
 * 3. Start this test from Studio. For every configuration it opens the recorder and the track,
 *    records a second, then logs `ROOM_TRIGGER <configuration>` (tag [TAG]); the script starts
 *    the near-end file on the PC's analog output on each trigger and stops at `ROOM_DONE`.
 * 4. The test finds where the near end landed in each recording with a matched filter on its
 *    first copy, so logcat's and PipeWire's latency do not matter; the second copy is exactly
 *    [NEAR_B_OFFSET_SECONDS] later on the PC's clock.
 * 5. After `ROOM_DONE` the test waits until the script has pulled the recordings and set
 *    [PULLED_PROPERTY]: Studio uninstalls the test APK, and its files directory, when the run ends.
 * The script's `--gain-db` makes the near-end talker quieter without touching the PC's volume.
 *
 * Timeline of one configuration, from the start of recording (10 ms frames):
 * - 0-1 s: recording only; then the trigger.
 * - near end alone: the first copy (12 s), the phone plays silence (still feeding it as reference,
 *   as the app does while nobody talks);
 * - echo only: the phone plays [FAR_CLIP] from [FAR_START_FRAME] on (AEC3 converges during its
 *   first 2 s, which the statistics skip);
 * - double talk: the second copy, while the far end goes on until [FAR_END_FRAME].
 *
 * Configurations: capture source (VOICE_COMMUNICATION, MIC, UNPROCESSED, VOICE_RECOGNITION) x
 * audio mode (MODE_IN_COMMUNICATION with the speaker selected as communication device and a
 * voice-communication track, as the router does; MODE_NORMAL with a media track, which plays on the
 * speaker without routing) x the platform's AEC and NS effects (default: none created, as the app
 * does with WEBRTC; on: created and enabled; off, for VOICE_COMMUNICATION only: created and
 * disabled). Every recording is written as a WAV to
 * `/sdcard/Android/data/se.lublin.humla.test/files/room`, every number to logcat (tag [TAG]) and
 * to `results.csv` there. Sets the voice-call and music volumes to maximum and the test package's
 * RECORD_AUDIO app op to allow, and restores both.
 *
 * Measured on an SM-S938B (2026-09-30), phone next to a PC speaker at 95 %, near end at the raw
 * microphone (UNPROCESSED, communication mode) -36 dBFS against an echo of -35 dBFS; `--gain-db`
 * -12 and -20 for a quieter talker. "DT" is the gate on voiced near-end frames in double talk, "FO"
 * the gate opening on echo alone:
 * - VOICE_COMMUNICATION and MIC in communication mode are gated by the platform: 97-100 % of the
 *   echo-only samples are exact zeros (also with the AEC effect disabled, so the gate is not the
 *   effect), FO 0 %, residual about -100 dBFS. In double talk the platform lets the louder near
 *   end through, but zeroes 17 / 30 / 50 % of the double-talk samples (near alone: 12 / 10 / 26 %).
 *   RNNoise without a limit (the chain shipped until then) DT 94 / 60 / 17 %, near end alone
 *   98 / 97 / 21 %; limited to 18 dB DT 97 / 91 / 80 %, near alone 98 / 99 / 96 %, FO still 0 %.
 *   This is why 18 dB is the shipped default ("shipped" below; "no limit" is the old chain).
 * - Retention (the same words in double talk against alone, [NearEndRetention]), the recordings
 *   replayed offline on an x86-64 build of the same native code: 18 dB kept the near end at -5 / -13
 *   / -4 dB, RNNoise costing the mid talker 8 dB of it (AGC2 3, the platform 1-2, AEC3 under 1).
 *   Since then "shipped" eases RNNoise while the user talks over the far end ([DoubleTalkRelief]):
 *   -4 / -2 / +14 dB, echo alone unchanged; "limit18" is the chain without the relief.
 * - VOICE_RECOGNITION and UNPROCESSED hear everything (no zeros); AEC3 converges (ERLE 32-38 dB),
 *   echo delay as AEC3 sees it about 290 ms. No limit DT 44-48 / 42-43 / 33-53 %, FO 0-15 %;
 *   limited to 18 dB DT 84-87 / 51-53 / 37 %, FO 0-12 %, residual -66 to -87 dBFS.
 * - Normal mode: the media stream at maximum puts the echo about 20 dB higher (-12 to -16 dBFS);
 *   no source is gated; no limit FO 3-32 %, DT 8-44 %.
 * - The platform AEC and NS effects change none of this materially.
 *
 * Step-by-step instructions, the full table and how to read `results.csv` are in
 * `docs/audio-testing.md`.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class RoomAcousticsDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val devices =
        AndroidCommunicationDevices(audioManager, Handler(Looper.getMainLooper())) { Log.w(TAG, "route refused", it) }
    private val outDir by lazy { File(context.getExternalFilesDir(null), "room").apply { mkdirs() } }

    private var prepared = false
    private var savedMode = 0
    private var savedDevice: AudioDeviceInfo? = null
    private var savedVoiceVolume = 0
    private var savedMusicVolume = 0
    private var savedAppOp = "default"

    @Before
    fun setUp() {
        assumeTrue(
            "the room test needs the PC speaker; enable it with -e roomTest true or " +
                "`adb shell setprop $ENABLE_PROPERTY true` (tools/room-test/room_test.py does)",
            argument("roomTest", ENABLE_PROPERTY) == "true",
        )
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        savedAppOp = APP_OP_MODE.find(shell("appops get ${context.packageName} RECORD_AUDIO"))
            ?.groupValues?.get(1) ?: "default"
        shell("appops set ${context.packageName} RECORD_AUDIO allow")
        savedMode = audioManager.mode
        savedDevice = audioManager.communicationDevice
        savedVoiceVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        prepared = true
        val available = audioManager.availableCommunicationDevices.map { it.type }
        log("mode $savedMode, route ${savedDevice?.type}, devices $available, app op was $savedAppOp")
        assumeTrue(
            "a headset is connected; the room test measures the phone's own speaker",
            available.none { it in AudioRouter.WIRED || it in AudioRouter.BLUETOOTH },
        )
        for (stream in listOf(AudioManager.STREAM_VOICE_CALL, AudioManager.STREAM_MUSIC)) {
            audioManager.setStreamVolume(stream, audioManager.getStreamMaxVolume(stream), 0)
        }
    }

    @After
    fun restore() {
        if (!prepared) return
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVoiceVolume, 0)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
        val device = savedDevice
        if (device == null) audioManager.clearCommunicationDevice() else audioManager.setCommunicationDevice(device)
        audioManager.mode = savedMode
        shell("appops set ${context.packageName} RECORD_AUDIO $savedAppOp")
        log("restored: mode ${audioManager.mode}, route ${audioManager.communicationDevice?.type}")
    }

    @Test
    fun captureConfigurationsInARealRoom() {
        val only = argument("roomTestOnly", ONLY_PROPERTY).split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val configs = CONFIGS.filter { c -> only.isEmpty() || only.any { c.name.contains(it) } }
        val far = farSchedule()
        writeWav("far-reference", far)
        val near = Near(SpeechCorpus.load(NEAR_CLIP))
        val rows = ArrayList<Row>()
        log("ROOM_BEGIN ${configs.size} configurations: ${configs.joinToString { it.name }}")
        try {
            for (config in configs) {
                val capture = capture(config, far) ?: continue
                writeWav(config.fileName, capture.pcm)
                val row = analyze(config, capture, far, near, rows)
                rows += row
                row.report()
            }
            summarize(rows)
        } finally {
            log("ROOM_DONE")
            awaitPull()
        }
        assertWithMessage("configurations in which the PC's near end was found")
            .that(rows.count { it.raw.nearFound }).isAtLeast(1)
        // PROVISIONAL until the next room run: on the app's configuration the double-talk relief
        // kept the near end at -4 / -2 / +14 dB (loud / -12 / -20 dB talker, the 2026-09-30
        // recordings replayed offline) against -5 / -13 / -4 dB without it, with echo alone still
        // never transmitted.
        for (row in rows.filter { it.config.name == APP_CONFIG && it.raw.nearFound }) {
            val shipped = row.chains.getValue("shipped")
            val before = row.chains.getValue("limit18")
            assertWithMessage("app configuration: double-talk retention (dB), relief over none")
                .that(shipped.retention.retentionDb - before.retention.retentionDb).isAtLeast(0.0)
            assertWithMessage("app configuration: double-talk retention (dB)")
                .that(shipped.retention.retentionDb).isAtLeast(MIN_APP_RETENTION_DB)
            assertWithMessage("app configuration: echo alone transmitted, relief over none")
                .that(shipped.echoFalseOpen - before.echoFalseOpen).isAtMost(MAX_APP_FALSE_OPEN_RISE)
        }
    }

    /**
     * Studio uninstalls the test APK after the run, and its files directory with it: gives the host
     * script up to [PULL_TIMEOUT_MS] to pull the recordings, until it sets [PULLED_PROPERTY].
     */
    private fun awaitPull() {
        if (shell("getprop $ENABLE_PROPERTY").trim() != "true") return
        val deadline = System.nanoTime() + PULL_TIMEOUT_MS * NANOS_PER_MS
        while (shell("getprop $PULLED_PROPERTY").trim() != "true" && System.nanoTime() < deadline) {
            Thread.sleep(POLL_MS * 25)
        }
    }

    /** One capture configuration; see the class comment. */
    private class Config(val source: Int, val sourceName: String, val communication: Boolean, val effects: Effects) {
        val name = "$sourceName/${if (communication) "COMM" else "NORMAL"}/${effects.name.lowercase(Locale.ROOT)}"
        val fileName = name.replace('/', '-')
    }

    private enum class Effects { DEFAULT, ON, OFF }

    /** The raw recording, and after which far-end frame each of its frames was read. */
    private class Capture(
        val pcm: ShortArray,
        /** Per recorded frame: the far-end frames handed to the canceller (and the track) when it was read. */
        val refPushed: IntArray,
        val routedType: Int?,
        val effects: String,
    )

    /** The near-end clip at 48 kHz and which of its frames are voiced (within 10 dB of its active level). */
    private class Near(val clip: FloatArray) {
        val voiced: BooleanArray = run {
            val levels = DoubleArray(clip.size / FRAME) { DoubleTalkRig.meanPower(clip, it, it + 1) }
            val floor = levels.max() / ACTIVE_RANGE
            val active = levels.filter { it > floor }.average()
            BooleanArray(levels.size) { levels[it] > active / VOICED_RANGE }
        }
        val template = DoubleArray(TEMPLATE_SAMPLES) { clip[it].toDouble() }
    }

    // ---- recording ----------------------------------------------------------------------------

    private fun route(config: Config) {
        if (config.communication) {
            devices.setCommunicationMode(true)
            val speaker = devices.available().firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            check(speaker != null && devices.select(speaker.id)) { "cannot select the speaker" }
            val deadline = System.nanoTime() + ROUTE_TIMEOUT_NANOS
            while (audioManager.communicationDevice?.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER &&
                System.nanoTime() < deadline
            ) {
                Thread.sleep(POLL_MS)
            }
        } else {
            audioManager.clearCommunicationDevice()
            devices.setCommunicationMode(false)
        }
        Thread.sleep(SETTLE_MS) // the HAL switches a little after the route reports
    }

    @SuppressLint("MissingPermission") // granted in setUp
    private fun openRecord(source: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                .setBufferSizeInBytes(max(min, RATE / 2 * 2))
                .build()
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "source $source", e)
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "source $source", e)
            null
        }
        if (record != null && record.state != AudioRecord.STATE_INITIALIZED) record.release()
        return record?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
    }

    /** The platform effects for [config]; describes each as `name:enabled-by-default>now`. */
    private fun effects(config: Config, session: Int): Pair<List<AudioEffect>, String> {
        if (config.effects == Effects.DEFAULT) return emptyList<AudioEffect>() to "none created"
        val created = listOfNotNull(
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session) else null,
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session) else null,
        )
        val described = created.joinToString(" ") { effect ->
            val before = effect.enabled
            effect.enabled = config.effects == Effects.ON
            "${effect.javaClass.simpleName}:$before>${effect.enabled}"
        }
        return created to described.ifEmpty { "none available" }
    }

    private fun capture(config: Config, far: ShortArray): Capture? {
        route(config)
        val record = openRecord(config.source) ?: run {
            log("${config.name}: this phone cannot record from ${config.sourceName}, skipped")
            return null
        }
        val (effects, described) = effects(config, record.audioSessionId)
        val stream = if (config.communication) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioOutput.playbackAttributes(stream))
            .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
            .setBufferSizeInBytes(AudioOutput.playbackBuffer(min).trackBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        try {
            return record(config, record, track, far).let {
                Capture(it.pcm, it.refPushed, it.routedType, described)
            }
        } finally {
            track.release()
            effects.forEach { it.release() }
            record.release()
        }
    }

    /**
     * Records [FRAMES] frames while a writer thread plays [far] frame by frame, handing each frame to
     * the reference counter before the blocking write, as `AudioOutput` feeds AEC3.
     */
    private fun record(config: Config, record: AudioRecord, track: AudioTrack, far: ShortArray): Capture {
        val pcm = ShortArray(FRAMES * FRAME)
        val refPushed = IntArray(FRAMES)
        val pushed = AtomicInteger(0)
        val done = AtomicBoolean(false)
        val writer = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frame = ShortArray(FRAME)
            track.play()
            var i = 0
            while (!done.get()) {
                frame.fill(0)
                if ((i + 1) * FRAME <= far.size) System.arraycopy(far, i * FRAME, frame, 0, FRAME)
                pushed.set(++i)
                track.write(frame, 0, FRAME)
            }
            track.stop()
        }
        var routedType: Int? = null
        val deadline = System.nanoTime() + FRAMES * NANOS_PER_FRAME + CAPTURE_SLACK_NANOS
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        record.startRecording()
        try {
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "${config.name} did not start" }
            writer.start()
            for (f in 0 until FRAMES) {
                var got = 0
                while (got < FRAME) {
                    val n = record.read(pcm, f * FRAME + got, FRAME - got)
                    check(n >= 0) { "AudioRecord.read returned $n" }
                    check(System.nanoTime() < deadline) { "${config.name}: the capture stalled at frame $f" }
                    got += n
                }
                refPushed[f] = pushed.get()
                if (f == TRIGGER_FRAME) log("ROOM_TRIGGER ${config.name}")
                if (f == FAR_START_FRAME + FRAMES_PER_SECOND) routedType = track.routedDevice?.type
            }
        } finally {
            done.set(true)
            writer.join()
            record.stop()
        }
        return Capture(pcm, refPushed, routedType, "")
    }

    // ---- analysis -----------------------------------------------------------------------------

    /** What the microphone delivered, before any processing. */
    @Suppress("LongParameterList") // a plain record of the numbers the table reports
    private class Raw(
        val nearFound: Boolean,
        /** Where the first near-end copy starts after the trigger frame, ms (acoustic path included). */
        val nearOffsetMs: Double,
        val nearPeakDb: Double,
        /** Near end at the microphone, voiced frames of the near-alone phase, dBFS. */
        val nearDbfs: Float,
        /** Echo at the microphone, converged echo-only window, dBFS. */
        val echoDbfs: Float,
        /** Share of exact-zero samples: near alone, echo only, double talk. */
        val zeros: Triple<Double, Double, Double>,
        /** From handing a far-end frame to the canceller to reading its echo, ms; NaN if not found. */
        val echoDelayMs: Double,
        val echoPeakDb: Double,
        /** AEC3 alone (no AGC2, no RNNoise): echo return loss enhancement, first 1 s of far end / converged, dB. */
        val erleFirstDb: Float,
        val erleDb: Float,
    )

    /** One chain on one recording. */
    @Suppress("LongParameterList") // a plain record of the numbers the table reports
    private class ChainResult(
        val nearAloneGate: Double,
        val doubleTalkGate: Double,
        val echoFalseOpen: Double,
        val echoFalseOpenConverging: Double,
        val residualDbfs: Float,
        val residualP95Dbfs: Float,
        /** The same words in double talk against alone, through this chain ([NearEndRetention]). */
        val retention: NearEndRetention.Result,
    )

    private inner class Row(
        val config: Config,
        val capture: Capture,
        val raw: Raw,
        val chains: Map<String, ChainResult>,
    ) {
        fun report() {
            log(
                String.format(
                    Locale.ROOT,
                    "%-32s raw | routed %s, effects %s | near at %+6.0f ms (peak %4.1f dB%s), %6.1f dBFS | " +
                        "echo %6.1f dBFS, delay %5.0f ms (peak %4.1f dB) | " +
                        "zeros near %3.0f%% echo %3.0f%% DT %3.0f%% | AEC3 ERLE first s %5.1f, converged %5.1f dB",
                    config.name, capture.routedType, capture.effects, raw.nearOffsetMs, raw.nearPeakDb,
                    if (raw.nearFound) "" else ", NOT FOUND", raw.nearDbfs, raw.echoDbfs, raw.echoDelayMs,
                    raw.echoPeakDb, 100 * raw.zeros.first, 100 * raw.zeros.second, 100 * raw.zeros.third,
                    raw.erleFirstDb, raw.erleDb,
                ),
            )
            for ((chain, r) in chains) {
                log(
                    String.format(
                        Locale.ROOT,
                        "%-32s %-7s | near alone %5.1f%% | DT gate %5.1f%% | echo FO %5.1f%% (0-2 s %5.1f%%) | " +
                            "residual %6.1f dBFS, p95 %6.1f | DT retained %+5.1f dB (median %+5.1f), " +
                            "dropped %3.0f%%, %4.1f/s",
                        config.name, chain, 100 * r.nearAloneGate, 100 * r.doubleTalkGate, 100 * r.echoFalseOpen,
                        100 * r.echoFalseOpenConverging, r.residualDbfs, r.residualP95Dbfs,
                        r.retention.retentionDb, r.retention.medianBlockDb, 100 * r.retention.droppedShare,
                        r.retention.dropoutsPerSecond,
                    ),
                )
            }
        }
    }

    /** Frame windows of one recording, from where its near end was found. */
    private class Windows(nearStartSample: Int, near: Near) {
        private val nearBStart = nearStartSample + NEAR_B_OFFSET_SECONDS * RATE
        private val clipFrames = near.voiced.size
        private val nearAStartFrame = nearStartSample / FRAME
        private val nearBStartFrame = nearBStart / FRAME
        val nearAlone: List<Int> = voicedFrames(nearStartSample, near)
        val doubleTalk: List<Int> = voicedFrames(nearBStart, near).filter { it < FAR_END_FRAME }
        val nearAloneSpan = nearAStartFrame until minOf(nearAStartFrame + clipFrames, FAR_START_FRAME)
        val echoOnly = (FAR_START_FRAME + CONVERGE_FRAMES) until minOf(nearBStartFrame, FAR_END_FRAME)
        val converging = FAR_START_FRAME until FAR_START_FRAME + CONVERGE_FRAMES
        val doubleTalkSpan = nearBStartFrame until minOf(nearBStartFrame + clipFrames, FAR_END_FRAME)

        /**
         * Clip frames voiced in both copies, the first copy before the far end starts and the second
         * before it ends: where each starts in the recording, second copy and first.
         */
        val sameWords: Pair<IntArray, IntArray> = run {
            val k = near.voiced.indices.filter {
                near.voiced[it] && nearStartSample + (it + 1) * FRAME <= FAR_START_FRAME * FRAME &&
                    nearBStart + (it + 1) * FRAME <= FAR_END_FRAME * FRAME
            }
            IntArray(k.size) { nearBStart + k[it] * FRAME } to IntArray(k.size) { nearStartSample + k[it] * FRAME }
        }

        private fun voicedFrames(start: Int, near: Near): List<Int> = (0 until FRAMES).filter { f ->
            val k = (f * FRAME - start).floorDiv(FRAME)
            k in near.voiced.indices && near.voiced[k]
        }
    }

    private fun analyze(config: Config, capture: Capture, far: ShortArray, near: Near, done: List<Row>): Row {
        val x = DoubleArray(capture.pcm.size) { capture.pcm[it] / FULL_SCALE }
        val searchFrom = TRIGGER_FRAME * FRAME
        val (lag, peakDb) = matchedFilter(x, searchFrom, NEAR_SEARCH_SAMPLES, near.template)
        val found = peakDb >= MIN_NEAR_PEAK_DB && lag < MAX_NEAR_OFFSET_SAMPLES
        // A gated or deaf source may hide the near end; take the others' offset then.
        val offset = if (found || done.none { it.raw.nearFound }) {
            lag
        } else {
            done.filter { it.raw.nearFound }.map { (it.raw.nearOffsetMs * RATE / MS_PER_SECOND).roundToInt() }
                .sorted().let { it[it.size / 2] }
        }
        val w = Windows(searchFrom + offset, near)
        val (echoLag, echoPeakDb) = echoDelay(x, far)
        val echoZeros = zeros(capture.pcm, w.echoOnly)
        // A gated window (mostly digital zeros) correlates with anything; its peak means nothing.
        val echoDelayMs = if (echoPeakDb >= MIN_ECHO_PEAK_DB && echoZeros < MAX_ECHO_ZEROS) {
            val refFrame = ECHO_TEMPLATE_FROM / FRAME
            val pushedAt = capture.refPushed.indexOfFirst { it > refFrame }
            ((ECHO_TEMPLATE_FROM + echoLag).toDouble() / FRAME - pushedAt) * FRAME_MS
        } else {
            Double.NaN
        }
        val aec = aecOnly(capture, far)
        val inPower = DoubleArray(FRAMES) { framePower(capture.pcm, it) }
        val raw = Raw(
            nearFound = found,
            nearOffsetMs = offset * MS_PER_SECOND / RATE,
            nearPeakDb = peakDb,
            nearDbfs = dbfs(w.nearAlone.map { inPower[it] }.average()),
            echoDbfs = dbfs(w.echoOnly.map { inPower[it] }.average()),
            zeros = Triple(
                zeros(capture.pcm, w.nearAloneSpan),
                echoZeros,
                zeros(capture.pcm, w.doubleTalkSpan),
            ),
            echoDelayMs = echoDelayMs,
            echoPeakDb = echoPeakDb,
            erleFirstDb = erle(inPower, aec.power, w.converging.first until w.converging.first + FRAMES_PER_SECOND),
            erleDb = erle(inPower, aec.power, w.echoOnly),
        )
        // The replay runs faster than real time; the relief's far-end hold follows its clock.
        val factory = CapturePreprocessorFactory(log = { Log.w(TAG, it) }, clock = DoubleTalkRig.clock)
        val chains = linkedMapOf<String, () -> CaptureChain>(
            "shipped" to { factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC) },
            "limit18" to { DoubleTalkRig.limitedChain(RnnoisePreprocessor.ATTENUATION_LIMIT_DB, agcAfter = false) },
            "nolimit" to { DoubleTalkRig.limitedChain(Float.POSITIVE_INFINITY, agcAfter = false) },
            "apm" to { factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC) },
        ).mapValues { (_, build) -> measure(replay(build(), capture, far), w) }
        return Row(config, capture, raw, chains)
    }

    private fun measure(run: DoubleTalkRig.Run, w: Windows): ChainResult {
        val levels = w.echoOnly.map { dbfs(run.power[it]) }.sorted()
        return ChainResult(
            nearAloneGate = share(w.nearAlone) { run.transmit[it] },
            doubleTalkGate = share(w.doubleTalk) { run.transmit[it] },
            echoFalseOpen = share(w.echoOnly.toList()) { run.transmit[it] },
            echoFalseOpenConverging = share(w.converging.toList()) { run.transmit[it] },
            residualDbfs = dbfs(w.echoOnly.map { run.power[it] }.average()),
            residualP95Dbfs = levels.getOrElse((levels.size * P95).toInt()) { Float.NaN },
            retention = NearEndRetention.measure(
                run.samples, run.samples, w.sameWords.first, w.sameWords.second, FRAME,
            ),
        )
    }

    /** AEC3 with nothing else (the high-pass stays), for its echo return loss enhancement. */
    private fun aecOnly(capture: Capture, far: ShortArray): DoubleTalkRig.Run {
        val apm = WebRtcApmPreprocessor(
            WebRtcApmNative,
            WebRtcApmConfig(echoCancellation = true, noiseSuppression = false, gainControl = false),
        )
        return replay(CaptureChain(apm, apm, apm.farEndFrameSize), capture, far)
    }

    /**
     * Runs [chain] over the recording as `AudioOutput` and `CapturePipeline` would have: before each
     * captured frame, the far-end frames the writer had handed over by the time it was read.
     */
    private fun replay(chain: CaptureChain, capture: Capture, far: ShortArray): DoubleTalkRig.Run {
        val chunker = chain.farEndSink?.let { FarEndFrameChunker(chain.farEndFrameSize, it) }
        val clock = DoubleTalkRig.clock
        clock.now = 0L
        val vad = VoiceActivityDetector(VadConfig.adaptive(onsetFrames = APP_ONSET_FRAMES), clock = clock)
        val run = DoubleTalkRig.Run(FRAMES)
        val frame = ShortArray(FRAME)
        val reference = ShortArray(FRAME)
        var pushed = 0
        fun pushReference() {
            reference.fill(0)
            if ((pushed + 1) * FRAME <= far.size) System.arraycopy(far, pushed * FRAME, reference, 0, FRAME)
            chunker?.push(reference, FRAME)
            pushed++
        }
        try {
            for (f in 0 until FRAMES) {
                while (pushed < capture.refPushed[f]) pushReference()
                System.arraycopy(capture.pcm, f * FRAME, frame, 0, FRAME)
                val probability = chain.preprocessor.process(frame)
                System.arraycopy(frame, 0, run.samples, f * FRAME, FRAME)
                run.power[f] = framePower(frame, 0)
                run.transmit[f] = vad.isVoice(frame, FRAME, probability)
                clock.now += FRAME_MS * NANOS_PER_MS
            }
        } finally {
            chain.preprocessor.release()
        }
        return run
    }

    /**
     * Where the far end's echo lies relative to the reference, in samples (negative when the
     * recording runs ahead of the writer): a 2 s matched filter from [ECHO_TEMPLATE_FROM] on.
     */
    private fun echoDelay(x: DoubleArray, far: ShortArray): Pair<Int, Double> {
        val template = DoubleArray(ECHO_TEMPLATE_SAMPLES) { far[ECHO_TEMPLATE_FROM + it] / FULL_SCALE }
        val (lag, db) = matchedFilter(x, ECHO_TEMPLATE_FROM - ECHO_SEARCH_BACK, ECHO_SEARCH_SAMPLES, template)
        return lag - ECHO_SEARCH_BACK to db
    }

    /**
     * The lag (from [from]) of [template] in [x] within [span] samples, and the peak over the RMS of
     * the correlation away from it, dB (0 when the window is digital silence).
     */
    private fun matchedFilter(x: DoubleArray, from: Int, span: Int, template: DoubleArray): Pair<Int, Double> {
        val window = x.copyOfRange(from, minOf(x.size, from + span + template.size))
        val c = Correlation.crossCorrelate(window, template)
        var peak = 0
        for (lag in c.indices) if (abs(c[lag]) > abs(c[peak])) peak = lag
        val guard = RATE / 20
        val noise = c.indices.filter { abs(it - peak) > guard }.map { c[it] * c[it] }.average()
        val db = if (noise > 0) 20 * log10(abs(c[peak]) / sqrt(noise)) else 0.0
        return peak to db
    }

    private fun erle(input: DoubleArray, output: DoubleArray, frames: IntProgression): Float {
        val i = frames.map { input[it] }.average()
        val o = frames.map { output[it] }.average()
        // A gated source delivers no echo to cancel: no enhancement to speak of.
        return if (i < SILENT_POWER) Float.NaN else DoubleTalkRig.db(i / max(o, TINY))
    }

    private fun zeros(pcm: ShortArray, frames: IntRange): Double {
        if (frames.isEmpty()) return Double.NaN
        var n = 0
        for (i in frames.first * FRAME until (frames.last + 1) * FRAME) if (pcm[i].toInt() == 0) n++
        return n.toDouble() / ((frames.last + 1 - frames.first) * FRAME)
    }

    private fun share(frames: List<Int>, open: (Int) -> Boolean): Double =
        if (frames.isEmpty()) Double.NaN else frames.count(open).toDouble() / frames.size

    // ---- output -------------------------------------------------------------------------------

    private fun summarize(rows: List<Row>) {
        log(
            "summary: config | zeros near/echo/DT | echo in, delay, ERLE | " +
                "chain: near alone, DT gate, echo FO, resid, DT retained dB, dropped",
        )
        val csv = StringBuilder(
            "config,routed,effects,near_found,near_offset_ms,near_dbfs,echo_dbfs,zeros_near,zeros_echo,zeros_dt," +
                "echo_delay_ms,erle_first_db,erle_db,chain,near_alone_gate,dt_gate,echo_fo,echo_fo_converging," +
                "residual_dbfs,residual_p95_dbfs,dt_retention_db,dt_retention_median_db,dt_dropped," +
                "dt_dropouts_per_s\n",
        )
        for (row in rows) {
            val r = row.raw
            for ((chain, c) in row.chains) {
                log(
                    String.format(
                        Locale.ROOT,
                        "%-32s | %3.0f/%3.0f/%3.0f%% | %6.1f dBFS %5.0f ms %5.1f dB | " +
                            "%-7s %5.1f%% %5.1f%% %5.1f%% %6.1f %+5.1f %3.0f%%",
                        row.config.name, 100 * r.zeros.first, 100 * r.zeros.second, 100 * r.zeros.third,
                        r.echoDbfs, r.echoDelayMs, r.erleDb, chain, 100 * c.nearAloneGate, 100 * c.doubleTalkGate,
                        100 * c.echoFalseOpen, c.residualDbfs, c.retention.retentionDb, 100 * c.retention.droppedShare,
                    ),
                )
                csv.append(
                    listOf(
                        row.config.name, row.capture.routedType, row.capture.effects.replace(',', ' '), r.nearFound,
                        r.nearOffsetMs, r.nearDbfs, r.echoDbfs, r.zeros.first, r.zeros.second, r.zeros.third,
                        r.echoDelayMs, r.erleFirstDb, r.erleDb, chain, c.nearAloneGate, c.doubleTalkGate,
                        c.echoFalseOpen, c.echoFalseOpenConverging, c.residualDbfs, c.residualP95Dbfs,
                        c.retention.retentionDb, c.retention.medianBlockDb, c.retention.droppedShare,
                        c.retention.dropoutsPerSecond,
                    ).joinToString(",", postfix = "\n"),
                )
            }
        }
        File(outDir, "results.csv").writeText(csv.toString())
    }

    /** The far end: [FAR_CLIP] at [FAR_DBFS] active level, looped from [FAR_START_FRAME] to [FAR_END_FRAME]. */
    private fun farSchedule(): ShortArray {
        val clip = SpeechCorpus.load(FAR_CLIP)
        val levels = DoubleArray(clip.size / FRAME) { DoubleTalkRig.meanPower(clip, it, it + 1) }
        val floor = levels.max() / ACTIVE_RANGE
        val rms = sqrt(levels.filter { it > floor }.average()) / FULL_SCALE
        val gain = 10.0.pow(FAR_DBFS / 20.0) / rms
        val out = ShortArray(FRAMES * FRAME)
        for (i in FAR_START_FRAME * FRAME until FAR_END_FRAME * FRAME) {
            val v = clip[(i - FAR_START_FRAME * FRAME) % clip.size] * gain * FULL_SCALE
            out[i] = v.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun framePower(pcm: ShortArray, frame: Int): Double {
        var sum = 0.0
        for (i in frame * FRAME until (frame + 1) * FRAME) sum += pcm[i].toDouble() * pcm[i]
        return sum / FRAME
    }

    private fun dbfs(power: Double): Float = DoubleTalkRig.dbfs(power)

    /** The instrumentation argument [name], else the system property [property], else empty. */
    private fun argument(name: String, property: String): String =
        InstrumentationRegistry.getArguments().getString(name) ?: shell("getprop $property").trim()

    /** Runs [command] as the shell user and returns its output once it has finished. */
    private fun shell(command: String): String {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { String(it.readBytes()) }
    }

    private fun writeWav(name: String, pcm: ShortArray) {
        RandomAccessFile(File(outDir, "$name.wav"), "rw").use { f ->
            f.setLength(0)
            val bytes = ByteBuffer.allocate(WAV_HEADER + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bytes.put("RIFF".toByteArray()).putInt(WAV_HEADER - 8 + pcm.size * 2).put("WAVE".toByteArray())
            bytes.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(RATE).putInt(RATE * 2)
                .putShort(2).putShort(16)
            bytes.put("data".toByteArray()).putInt(pcm.size * 2)
            for (s in pcm) bytes.putShort(s)
            f.write(bytes.array())
        }
    }

    private fun format(channel: Int) = AudioFormat.Builder()
        .setSampleRate(RATE)
        .setChannelMask(channel)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .build()

    private fun log(line: String) {
        Log.i(TAG, line)
        println(line)
    }

    private companion object {
        const val TAG = "RoomTest"

        /** The configuration the app records with. */
        const val APP_CONFIG = "VOICE_COMMUNICATION/COMM/default"

        /** Provisional bounds, see [captureConfigurationsInARealRoom]. */
        const val MIN_APP_RETENTION_DB = -8.0
        const val MAX_APP_FALSE_OPEN_RISE = 0.01
        const val ENABLE_PROPERTY = "debug.mumla.roomtest"
        const val ONLY_PROPERTY = "debug.mumla.roomtest.only"
        const val PULLED_PROPERTY = "debug.mumla.roomtest.pulled"
        const val PULL_TIMEOUT_MS = 120_000L
        const val MAX_ECHO_ZEROS = 0.5

        const val RATE = 48_000
        const val FRAME = 480
        const val FRAME_MS = 10
        const val FRAMES_PER_SECOND = 100
        const val FULL_SCALE = 32768.0
        const val TINY = 1e-3

        /** Mean frame power (in 16-bit units squared) below which a window counts as digital silence: -100 dBFS. */
        const val SILENT_POWER = 1e-1
        const val MS_PER_SECOND = 1000.0
        const val NANOS_PER_MS = 1_000_000L
        const val NANOS_PER_FRAME = FRAME_MS * NANOS_PER_MS
        const val CAPTURE_SLACK_NANOS = 10_000 * NANOS_PER_MS
        const val ROUTE_TIMEOUT_NANOS = 2_000 * NANOS_PER_MS
        const val POLL_MS = 20L
        const val SETTLE_MS = 500L
        const val WAV_HEADER = 44
        const val APP_ONSET_FRAMES = 2
        const val P95 = 0.95
        const val ACTIVE_RANGE = 100.0 // 20 dB, as a power ratio
        const val VOICED_RANGE = 10.0 // 10 dB, as a power ratio
        const val FAR_DBFS = -18.0

        val FAR_CLIP = SpeechCorpus.Clip.FAR_DE_M
        val NEAR_CLIP = SpeechCorpus.Clip.NEAR_EN_F

        /** The host script's file: the near-end clip, and the same clip again this many seconds later. */
        const val NEAR_B_OFFSET_SECONDS = 26

        const val TRIGGER_FRAME = FRAMES_PER_SECOND
        const val FAR_START_FRAME = 1450
        const val FAR_END_FRAME = 4150
        const val FRAMES = 4200
        const val CONVERGE_FRAMES = 2 * FRAMES_PER_SECOND

        /** The near end must start within 1.5 s of the trigger, so that it ends before the far end starts. */
        const val MAX_NEAR_OFFSET_SAMPLES = RATE * 3 / 2
        const val NEAR_SEARCH_SAMPLES = 2 * RATE
        const val TEMPLATE_SAMPLES = 6 * RATE
        const val ECHO_TEMPLATE_FROM = (FAR_START_FRAME + FRAMES_PER_SECOND) * FRAME
        const val ECHO_TEMPLATE_SAMPLES = 2 * RATE
        const val ECHO_SEARCH_BACK = RATE / 2
        const val ECHO_SEARCH_SAMPLES = RATE * 3 / 2

        /**
         * Matched-filter peak over the rest of the correlation that counts as found. Speech has
         * higher side lobes than a sweep; checked against the recordings' logged peaks.
         */
        const val MIN_NEAR_PEAK_DB = 25.0
        const val MIN_ECHO_PEAK_DB = 20.0

        val APP_OP_MODE = Regex("""(?m)^RECORD_AUDIO: (\w+)""")

        val SOURCES = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.MIC to "MIC",
        )

        /** Communication mode first, the app's configuration (VOICE_COMMUNICATION, no effects) at the top. */
        val CONFIGS: List<Config> = listOf(true, false).flatMap { communication ->
            listOf(Effects.DEFAULT, Effects.ON, Effects.OFF).flatMap { effects ->
                SOURCES.filter { effects != Effects.OFF || it.first == MediaRecorder.AudioSource.VOICE_COMMUNICATION }
                    .map { (source, name) -> Config(source, name, communication, effects) }
            }
        }
    }
}
