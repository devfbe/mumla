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

package se.lublin.humla.audio.routing

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.lublin.humla.audio.AudioOutput
import se.lublin.humla.audio.Correlation
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real acoustics on the phone: a probe (a 1 s log sweep, 200 Hz to 8 kHz) played on the voice-call
 * path the app uses (`USAGE_VOICE_COMMUNICATION` in `MODE_IN_COMMUNICATION`, routed with
 * [AndroidCommunicationDevices] or the app's [AudioRouter]) and recorded back from several capture
 * sources. A matched filter finds it. A source hears it when all of these hold (see [isHeard]):
 * - the peak stands [MIN_PEAK_DB] above the rest of the correlation (a gated or noise-only
 *   recording reaches 18-28 dB on this phone, a heard sweep 40-60 dB);
 * - it lies where the sweep can be: 0 to [MAX_LATENCY_MS] after it was played;
 * - its loop gain is [OVER_SILENCE_DB] above the silence run's, taken no lower than
 *   [SILENCE_FLOOR_DB] (a gated source returns exact zeros for silence, which alone would put any
 *   non-zero sample hundreds of dB "above silence").
 *
 * The asserted source is UNPROCESSED where the phone records from it (the raw microphone, so the
 * test measures the route, not the platform's voice processing); elsewhere any source that hears
 * it. UNPROCESSED is tried whatever `PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` says: the SM-S938B
 * does not report it, yet records from it (the raw microphone, 18 dB louder on the speaker than
 * VOICE_RECOGNITION); a phone that cannot open it skips it. The app's own source,
 * VOICE_COMMUNICATION, is only logged: the platform may gate it.
 *
 * Every recording is also written as a WAV to the test APK's external files directory
 * (`/sdcard/Android/data/se.lublin.humla.test/files/loopback`), and every number goes to logcat
 * (tag [TAG]). Needs a phone without a headset, and a quiet room. Sets the test package's
 * RECORD_AUDIO app op to allow and restores it afterwards.
 *
 * Measured on an SM-S938B (2026-09-30, voice-call volume at maximum):
 * - Earpiece, routed directly or by [AudioRouter] with the earpiece saved and no headset: the
 *   router reports the earpiece, the track plays there, and the probe comes back at -36 dB loop
 *   gain (UNPROCESSED), 43-48 dB above silence. Audible.
 * - Speaker: -14 dB loop gain (UNPROCESSED). But VOICE_COMMUNICATION and MIC, which in
 *   communication mode take the platform's voice path, deliver 94-100 % digital zeros while the
 *   speaker plays: the platform gates the microphone during far-end playback (half duplex). On
 *   the earpiece the same sources are gated on 57-71 % of the sweep.
 * - Quiet input on VOICE_COMMUNICATION and MIC comes back as exact zeros, and the first second of
 *   every capture is zeros.
 * - Presented-to-captured latency: 150 ms on VOICE_COMMUNICATION and MIC, 93-109 ms on
 *   UNPROCESSED, VOICE_RECOGNITION and CAMCORDER.
 */
@RunWith(AndroidJUnit4::class)
class AcousticLoopbackDeviceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val devices = AndroidCommunicationDevices(audioManager, main) { Log.w(TAG, "route refused", it) }

    private var savedMode = 0
    private var savedDevice: AudioDeviceInfo? = null
    private var savedVolume = 0
    private var savedAppOp: String? = null
    private val unprocessedSupported =
        audioManager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    @Before
    fun setUp() {
        // Not GrantPermissionRule: with Robolectric on this classpath (humla's test fixtures) its
        // service loader picks Robolectric's granter, which fails on a device. The app op lets the
        // test process, which has no activity, record without being silenced as a background app.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val appOp = shell("appops get ${context.packageName} RECORD_AUDIO")
        savedAppOp = APP_OP_MODE.find(appOp)?.groupValues?.get(1) ?: "default"
        shell("appops set ${context.packageName} RECORD_AUDIO allow")
        savedMode = audioManager.mode
        savedDevice = audioManager.communicationDevice
        savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        val available = audioManager.availableCommunicationDevices.map { it.type }
        log(
            "mode $savedMode, route ${savedDevice?.type}, voice-call volume $savedVolume, devices $available, " +
                "RECORD_AUDIO app op was $savedAppOp, UNPROCESSED supported $unprocessedSupported",
        )
        assumeTrue(
            "a headset is connected; these tests measure the phone's own speaker and earpiece",
            available.none { it in AudioRouter.WIRED || it in AudioRouter.BLUETOOTH },
        )
        audioManager.setStreamVolume(
            AudioManager.STREAM_VOICE_CALL, audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL), 0,
        )
    }

    @After
    fun restore() {
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, savedVolume, 0)
        val device = savedDevice
        if (device == null) audioManager.clearCommunicationDevice() else audioManager.setCommunicationDevice(device)
        audioManager.mode = savedMode
        savedAppOp?.let { shell("appops set ${context.packageName} RECORD_AUDIO $it") }
        log(
            "restored: mode ${audioManager.mode}, route ${audioManager.communicationDevice?.type}, " +
                "RECORD_AUDIO app op ${shell("appops get ${context.packageName} RECORD_AUDIO").trim()}",
        )
    }

    @Test
    fun theProbeIsHeardOnTheSpeaker() {
        routeAndProbe(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "speaker")
    }

    @Test
    fun theProbeIsHeardOnTheEarpiece() {
        routeAndProbe(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "earpiece")
    }

    /**
     * The owner's question: with the earpiece saved and no headset, does the app route to the
     * earpiece and is it audible there? Goes through [AudioRouter] as a session does
     * (`preferred` set, then [AudioRouter.engage]), not through `AudioManager` directly.
     */
    @Test
    fun aSavedEarpieceWithoutAHeadsetIsWhereTheRouterPlays() {
        val routes = ArrayList<Int?>()
        lateinit var router: AudioRouter
        onMain {
            router = AudioRouter(devices, object : AudioRouter.Listener {
                override fun onRouteChanged(type: Int?) {
                    routes += type
                }

                override fun onRouteRefused() {
                    routes += REFUSED
                }
            })
            router.preferred = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            router.engage()
        }
        try {
            waitForRoute(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
            var reported: List<Int?> = emptyList()
            var active: CommunicationDevice? = null
            onMain {
                reported = routes.toList()
                active = router.activeDevice()
            }
            log("router: routes reported $reported, active $active")
            val earpiece = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            assertWithMessage("mode while engaged").that(audioManager.mode)
                .isEqualTo(AudioManager.MODE_IN_COMMUNICATION)
            assertWithMessage("routes the router reported").that(reported).doesNotContain(REFUSED)
            assertWithMessage("the route the router reported last").that(reported.lastOrNull()).isEqualTo(earpiece)
            assertWithMessage("the router's active device").that(active?.type).isEqualTo(earpiece)
            assertWithMessage("communication device").that(audioManager.communicationDevice?.type).isEqualTo(earpiece)
            probeOnCurrentRoute(earpiece, "router-earpiece")
        } finally {
            onMain {
                router.disengage()
                router.release()
            }
        }
    }

    private fun routeAndProbe(type: Int, label: String) {
        devices.setCommunicationMode(true)
        val device = devices.available().firstOrNull { it.type == type }
        assumeTrue("no device of type $type", device != null)
        assertWithMessage("select %s", label).that(devices.select(device!!.id)).isTrue()
        waitForRoute(type)
        assertWithMessage("communication device").that(audioManager.communicationDevice?.type).isEqualTo(type)
        probeOnCurrentRoute(type, label)
    }

    /** Silence, then the probe, recorded as the app records and as the raw microphone; see [isHeard]. */
    private fun probeOnCurrentRoute(type: Int, label: String) {
        val results = SOURCES.mapNotNull { (source, sourceName) ->
            // The app's source must record; another one this phone cannot open is skipped.
            val optional = source != MediaRecorder.AudioSource.VOICE_COMMUNICATION
            val silent = loopback(FloatArray(probe.size), source, "$label-$sourceName-silence")
                ?: return@mapNotNull skipped(label, sourceName, optional)
            val heard = loopback(probe, source, "$label-$sourceName")
                ?: return@mapNotNull skipped(label, sourceName, optional)
            val overSilence = heard.loopGainDb - max(silent.loopGainDb, SILENCE_FLOOR_DB)
            log(
                String.format(
                    Locale.ROOT,
                    "%s, %-19s: heard %s | routed to %s | loop gain %6.1f dB (silence %6.1f dB, over it %5.1f) | " +
                        "peak %5.1f dB over correlation noise, at %6.1f ms | level %6.1f dBFS (silence %6.1f) | " +
                        "digital zeros while the sweep plays %3.0f%% (silence %3.0f%%) | latency: " +
                        "presented-to-captured %.1f ms, write-to-read %.1f ms",
                    label, sourceName, isHeard(heard, silent), heard.routedType, heard.loopGainDb, silent.loopGainDb,
                    overSilence, heard.peakDb, heard.latency.peakAtMs,
                    heard.levelDbfs, silent.levelDbfs, 100 * heard.zerosDuringSweep, 100 * silent.zerosDuringSweep,
                    heard.latency.acousticMs, heard.latency.appRoundTripMs,
                ),
            )
            assertWithMessage("%s, %s: the track's routed device", label, sourceName)
                .that(heard.routedType).isEqualTo(type)
            Triple(sourceName, heard, silent)
        }
        val asserted = results.firstOrNull { it.first == "UNPROCESSED" }
            ?: results.firstOrNull { isHeard(it.second, it.third) }
            ?: results.first()
        val (sourceName, heard, silent) = asserted
        assertWithMessage("%s, %s: matched-filter peak over correlation noise (dB)", label, sourceName)
            .that(heard.peakDb).isAtLeast(MIN_PEAK_DB)
        assertWithMessage("%s, %s: where the peak lies after the sweep was played (ms)", label, sourceName)
            .that(heard.latency.peakAtMs).isIn(Range.closed(0.0, MAX_LATENCY_MS))
        assertWithMessage("%s, %s: loop gain over silence's (floored at %s dB)", label, sourceName, SILENCE_FLOOR_DB)
            .that(heard.loopGainDb - max(silent.loopGainDb, SILENCE_FLOOR_DB)).isAtLeast(OVER_SILENCE_DB)
        // Both clocks are only there when the track reported a timestamp; peakAtMs bounds it otherwise.
        if (!heard.latency.acousticMs.isNaN()) {
            assertWithMessage("%s, %s: presented-to-captured latency (ms)", label, sourceName)
                .that(heard.latency.acousticMs).isIn(Range.closed(0.0, MAX_LATENCY_MS))
        }
    }

    private fun skipped(label: String, sourceName: String, optional: Boolean): Nothing? {
        check(optional) { "$label: the app's capture source $sourceName cannot record" }
        log("$label, $sourceName: this phone cannot record from it, skipped")
        return null
    }

    /** See the class comment. */
    private fun isHeard(heard: Loopback, silent: Loopback): Boolean =
        heard.peakDb >= MIN_PEAK_DB && heard.latency.peakAtMs in 0.0..MAX_LATENCY_MS &&
            heard.loopGainDb - max(silent.loopGainDb, SILENCE_FLOOR_DB) >= OVER_SILENCE_DB

    private class Latency(
        /** From the sweep's first sample leaving the speaker to it being captured (both timestamps). */
        val acousticMs: Double,
        /** From the sweep's first sample being written to it being read back, by the clock. */
        val appRoundTripMs: Double,
        /**
         * Where the peak lies, ms after recorded sample [RECORD_LEAD] + [PREROLL]: the track
         * starts only once [RECORD_LEAD] samples were read, so the sweep cannot be recorded before
         * that sample, and for a heard sweep this is the output-to-input latency (plus the
         * track's start-up).
         */
        val peakAtMs: Double,
    )

    private class Loopback(
        val routedType: Int?,
        /**
         * The matched filter's peak over the sweep's own energy, dB: the level at which the sweep
         * came back relative to how it was played (with silence played, whatever correlates best).
         */
        val loopGainDb: Double,
        /** Matched-filter peak over the RMS of the correlation away from it, dB (99 for digital silence). */
        val peakDb: Double,
        val levelDbfs: Double,
        /** Share of exact zeros where the sweep plays (what the platform gated to digital silence). */
        val zerosDuringSweep: Double,
        val latency: Latency,
    )

    /** What [capture] recorded, and the clocks of both streams. */
    private class Capture(
        val recorded: ShortArray,
        val routedType: Int?,
        /** The track's presentation timestamp, or null if it had none. */
        val trackStamp: AudioTimestamp?,
        val recordStamp: AudioTimestamp,
        val writeStart: Long,
        val readStart: Long,
    )

    /**
     * Plays [signal] on the voice-call path while recording from [source]; see [Loopback]. Null
     * when this phone cannot record from [source].
     */
    private fun loopback(signal: FloatArray, source: Int, name: String): Loopback? {
        val capture = capture(signal, source) ?: return null
        writeWav(name, capture.recorded)
        return analyze(capture)
    }

    @SuppressLint("MissingPermission") // granted in setUp
    private fun capture(signal: FloatArray, source: Int): Capture? {
        val minRecord = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO))
                .setBufferSizeInBytes(max(minRecord, RATE / 5 * 2))
                .build()
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "source $source", e)
            return null
        }
        val minTrack = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioOutput.playbackAttributes(AudioManager.STREAM_VOICE_CALL))
            .setAudioFormat(format(AudioFormat.CHANNEL_OUT_MONO))
            .setBufferSizeInBytes(max(minTrack, RATE / 10 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val pcm = ShortArray(signal.size) { (signal[it] * FULL_SCALE).roundToInt().coerceIn(-32768, 32767).toShort() }
        val recorded = ShortArray(RECORD_LEAD + signal.size)
        var routedType: Int? = null
        val trackStamp = AudioTimestamp()
        val recordStamp = AudioTimestamp()
        var trackStampValid = false
        var writeStart = 0L
        val readStart = System.nanoTime()
        val deadline = readStart + (recorded.size.toLong() * NANOS_PER_SECOND / RATE) + CAPTURE_SLACK_NANOS
        try {
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "source $source did not start" }
            var got = 0
            val chunk = RATE / 100
            // Capture first (this phone delivers about a second of zeros after it starts), then the
            // probe, written from its own thread.
            while (got < RECORD_LEAD) got += read(record, recorded, got, chunk, deadline)
            val writer = Thread {
                track.play()
                writeStart = System.nanoTime()
                track.write(pcm, 0, pcm.size)
            }
            writer.start()
            while (got < recorded.size) {
                got += read(record, recorded, got, minOf(chunk, recorded.size - got), deadline)
                if (routedType == null && got > RECORD_LEAD + RATE / 2) routedType = track.routedDevice?.type
                if (!trackStampValid && got > RECORD_LEAD + RATE) trackStampValid = track.getTimestamp(trackStamp)
            }
            writer.join()
            record.getTimestamp(recordStamp, AudioTimestamp.TIMEBASE_MONOTONIC)
        } finally {
            track.stop()
            track.release()
            record.stop()
            record.release()
        }
        val presentation = trackStamp.takeIf { trackStampValid }
        return Capture(recorded, routedType, presentation, recordStamp, writeStart, readStart)
    }

    /** One blocking read; fails on an error code or when the capture runs past [deadline]. */
    private fun read(record: AudioRecord, into: ShortArray, at: Int, size: Int, deadline: Long): Int {
        val n = record.read(into, at, size)
        check(n >= 0) { "AudioRecord.read returned $n" }
        check(System.nanoTime() < deadline) { "the capture stalled at sample $at of ${into.size}" }
        return n
    }

    private fun analyze(capture: Capture): Loopback {
        val recorded = capture.recorded
        val x = DoubleArray(recorded.size) { recorded[it] / FULL_SCALE }
        val correlation = Correlation.crossCorrelate(x, sweep)
        var peakLag = 0
        for (lag in correlation.indices) if (abs(correlation[lag]) > abs(correlation[peakLag])) peakLag = lag
        val peak = abs(correlation[peakLag])
        val guard = RATE / 20
        var noise = 0.0
        var count = 0
        for (lag in correlation.indices) {
            if (abs(lag - peakLag) > guard) {
                noise += correlation[lag] * correlation[lag]
                count++
            }
        }
        val peakDb = if (noise > 0) 20 * log10(peak / sqrt(noise / count)) else if (peak > 0) NO_NOISE_DB else 0.0
        // Where the sweep plays: its first sample leaves about 150 ms after being written.
        val sweepAt = RECORD_LEAD + PREROLL + RATE * 3 / 20
        val zeros = (sweepAt until minOf(recorded.size, sweepAt + sweep.size)).count { recorded[it].toInt() == 0 }
        // The sweep's first sample is sample PREROLL of the track; peakLag is where it was recorded.
        val presented = capture.trackStamp?.let {
            it.nanoTime + (PREROLL - it.framePosition) * NANOS_PER_SECOND / RATE
        }
        val stamp = capture.recordStamp
        val captured = stamp.nanoTime + (peakLag - stamp.framePosition) * NANOS_PER_SECOND / RATE
        val written = capture.writeStart + PREROLL * NANOS_PER_SECOND / RATE
        val read = capture.readStart + peakLag * NANOS_PER_SECOND / RATE
        return Loopback(
            routedType = capture.routedType,
            loopGainDb = 20 * log10(max(peak, TINY) / sweepEnergy),
            peakDb = peakDb,
            levelDbfs = 10 * log10(max(power(x, 0, x.size), TINY)),
            zerosDuringSweep = zeros.toDouble() / sweep.size,
            latency = Latency(
                acousticMs = presented?.let { (captured - it) / NANOS_PER_MS } ?: Double.NaN,
                appRoundTripMs = (read - written) / NANOS_PER_MS,
                peakAtMs = (peakLag - RECORD_LEAD - PREROLL) * MS_PER_SECOND / RATE,
            ),
        )
    }

    private fun waitForRoute(type: Int) {
        val deadline = System.nanoTime() + ROUTE_TIMEOUT_MS * NANOS_PER_MS.toLong()
        while (audioManager.communicationDevice?.type != type && System.nanoTime() < deadline) Thread.sleep(POLL_MS)
        Thread.sleep(SETTLE_MS) // the HAL switches the output a little after the route reports
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    /** Runs [command] as the shell user and returns its output once it has finished. */
    private fun shell(command: String): String {
        val out = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(out).use { String(it.readBytes()) }
    }

    private fun writeWav(name: String, pcm: ShortArray) {
        val dir = File(context.getExternalFilesDir(null), "loopback").apply { mkdirs() }
        RandomAccessFile(File(dir, "$name.wav"), "rw").use { f ->
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
        const val TAG = "AcousticLoopback"
        const val RATE = 48_000
        const val FULL_SCALE = 32768.0
        const val TINY = 1e-12
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MS = 1e6
        const val MS_PER_SECOND = 1000.0
        const val CAPTURE_SLACK_NANOS = 5 * NANOS_PER_SECOND
        const val ROUTE_TIMEOUT_MS = 2_000
        const val POLL_MS = 20L
        const val SETTLE_MS = 500L
        const val WAV_HEADER = 44
        const val REFUSED = -1

        /**
         * The package's mode in `appops get` (`RECORD_AUDIO: allow; time=...`), not the uid's
         * (`Uid mode: RECORD_AUDIO: foreground`).
         */
        val APP_OP_MODE = Regex("""(?m)^RECORD_AUDIO: (\w+)""")

        /** A heard probe comes back this much above what the matched filter finds with silence played... */
        const val OVER_SILENCE_DB = 20.0

        /** ...taking silence as no lower than this loop gain (digital zeros give -310 dB). */
        const val SILENCE_FLOOR_DB = -100.0

        /** A heard probe's matched-filter peak over the rest of the correlation. */
        const val MIN_PEAK_DB = 35.0
        const val NO_NOISE_DB = 99.0
        const val MAX_LATENCY_MS = 500.0

        /** Capture runs a second before the probe starts. */
        const val RECORD_LEAD = RATE

        /** The probe: one second of silence before the sweep, half a second after it. */
        const val PREROLL = RATE
        const val SWEEP_SECONDS = 1.0
        const val SWEEP_DBFS = -12.0
        const val F0 = 200.0
        const val F1 = 8_000.0

        /** What the app records with, and the other sources for comparison. */
        val SOURCES = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION to "VOICE_COMMUNICATION",
            MediaRecorder.AudioSource.MIC to "MIC",
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.CAMCORDER to "CAMCORDER",
        )

        /** Exponential sweep F0 to F1 with 10 ms raised-cosine fades. */
        val sweep: DoubleArray = run {
            val n = (SWEEP_SECONDS * RATE).toInt()
            val k = ln(F1 / F0)
            val amplitude = 10.0.pow(SWEEP_DBFS / 20) * sqrt(2.0)
            val fade = RATE / 100
            DoubleArray(n) { i ->
                val t = i.toDouble() / RATE
                val phase = 2 * PI * F0 * SWEEP_SECONDS / k * (exp(t / SWEEP_SECONDS * k) - 1)
                val edge = minOf(i, n - 1 - i)
                val window = if (edge < fade) 0.5 - 0.5 * cos(PI * edge / fade) else 1.0
                amplitude * window * sin(phase)
            }
        }

        val probe: FloatArray = FloatArray(PREROLL + sweep.size + RATE / 2).also {
            for (i in sweep.indices) it[PREROLL + i] = sweep[i].toFloat()
        }

        val sweepEnergy: Double = sweep.sumOf { it * it }

        fun power(x: DoubleArray, from: Int, to: Int): Double {
            var sum = 0.0
            val end = minOf(to, x.size)
            for (i in from until end) sum += x[i] * x[i]
            return sum / max(1, end - from)
        }
    }
}
