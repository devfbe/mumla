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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import se.lublin.humla.audio.native.RnnoiseNative
import se.lublin.humla.audio.native.WebRtcApmNative
import java.util.Locale
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * CHARACTERIZATION (double-talk bug): how much near-end speech survives the capture chain the app
 * builds for [EchoCancellationMode.WEBRTC] (real `libhumla_native.so`: AEC3 + AGC2 + high-pass,
 * optionally followed by RNNoise) while the far end is talking, and whether the app's default
 * adaptive gate opens on what is left.
 *
 * Timeline (48 kHz mono, 10 ms frames): far end only (AEC3 converges), then double talk, then near
 * end only. Three runs per chain with identical far-end reference:
 * - A: mic = echo + near + noise (what the phone records),
 * - B: mic = echo + noise (A without the near end),
 * - C: mic = near + noise, far-end reference silent (the near end as the chain passes it alone).
 * Near-end retention in a phase is `10*log10((P_A - P_B) / P_C)`.
 *
 * Prints its tables to logcat (tag [TAG]); asserts only that the chain accepted every frame.
 */
@RunWith(AndroidJUnit4::class)
class DoubleTalkAec3DeviceTest {

    private class Scenario(val name: String, val echoGainDb: Float, val nearDbfs: Float)

    private enum class Phase(val seconds: Int) { FAR_ONLY(8), DOUBLE_TALK(4), NEAR_ONLY(4) }

    /**
     * The chains measured: the two the app builds for WEBRTC echo cancellation (via
     * [CapturePreprocessorFactory]), and two diagnostic variants that isolate AGC2 and AEC3.
     */
    private enum class Chain(val label: String) {
        APP_APM("APM (app: AEC3+AGC2+HPF)"),
        APP_APM_RNNOISE("APM+RNNoise (app)"),
        APM_NO_AGC_RNNOISE("APM without AGC2 + RNNoise (diagnostic)"),
        RNNOISE_ONLY("RNNoise only, no AEC (diagnostic)"),
    }

    /** Per-frame output power and the gate trace of one chain run. */
    private class Run(frames: Int) {
        val power = DoubleArray(frames)
        val transmit = BooleanArray(frames)
        val floor = FloatArray(frames)
        val threshold = FloatArray(frames)
    }

    @Test
    fun nearEndSurvivesTheWebRtcChainDuringDoubleTalk() {
        val scenarios = listOf(
            Scenario("echo -20 dB, near -26 dBFS", echoGainDb = -20f, nearDbfs = -26f),
            Scenario("echo -10 dB, near -26 dBFS", echoGainDb = -10f, nearDbfs = -26f),
            Scenario("echo -10 dB, near -32 dBFS", echoGainDb = -10f, nearDbfs = -32f),
            Scenario("echo   0 dB, near -26 dBFS", echoGainDb = 0f, nearDbfs = -26f),
            Scenario("echo  +6 dB, near -26 dBFS", echoGainDb = 6f, nearDbfs = -26f),
        )
        log("far end ${FAR_DBFS} dBFS active, echo delay ${ECHO_DELAY_MS} ms, mic noise ${NOISE_DBFS} dBFS")
        log("VAD: app defaults, adaptive fraction 0.65, hold 250 ms, onset 2 frames")
        for (scenario in scenarios) {
            for (chain in Chain.entries) measure(scenario, chain)
        }
    }

    private fun measure(scenario: Scenario, chain: Chain) {
        val frames = Phase.entries.sumOf { it.seconds } * FRAMES_PER_SECOND
        val samples = frames * FRAME
        val farEndStop = (Phase.FAR_ONLY.seconds + Phase.DOUBLE_TALK.seconds) * RATE
        val nearStart = Phase.FAR_ONLY.seconds * RATE

        val far = speechLike(samples, f0 = 115.0, syllableHz = 4.1, seed = 1, dbfs = FAR_DBFS)
        for (i in farEndStop until samples) far[i] = 0f
        val near = speechLike(samples, f0 = 205.0, syllableHz = 3.3, seed = 2, dbfs = scenario.nearDbfs)
        for (i in 0 until nearStart) near[i] = 0f
        val echo = echoOf(far, scenario.echoGainDb)
        val noiseFloor = whiteNoise(samples, NOISE_DBFS, seed = 3)
        val silence = FloatArray(samples)

        val micA = sum(echo, near, noiseFloor)
        val a = run(chain, far, micA)
        val b = run(chain, far, sum(echo, noiseFloor))
        val c = run(chain, silence, sum(near, noiseFloor))

        log("== ${scenario.name}, ${chain.label} ==")
        log("phase        mic-in  echo-in  out A   out B   out C   near kept  gate A   first open  floor/thr A (end)")
        var start = 0
        for (phase in Phase.entries) {
            val end = start + phase.seconds * FRAMES_PER_SECOND
            val pA = mean(a.power, start, end)
            val pB = mean(b.power, start, end)
            val pC = mean(c.power, start, end)
            val kept = if (phase == Phase.FAR_ONLY) Float.NaN else db(max(pA - pB, TINY) / max(pC, TINY))
            val open = (start until end).count { a.transmit[it] }
            val firstOpen = (start until end).firstOrNull { a.transmit[it] }?.let { (it - start) * FRAME_MS }
            log(
                String.format(
                    Locale.ROOT,
                    "%-12s %6.1f  %6.1f  %6.1f  %6.1f  %6.1f  %8s  %5.0f %%  %10s  %6.1f/%6.1f",
                    phase.name, dbfs(meanPower(micA, start, end)), dbfs(meanPower(echo, start, end)),
                    dbfs(pA), dbfs(pB), dbfs(pC),
                    if (kept.isNaN()) "-" else String.format(Locale.ROOT, "%+.1f dB", kept),
                    100f * open / (end - start),
                    firstOpen?.let { "$it ms" } ?: "never",
                    a.floor[end - 1], a.threshold[end - 1],
                ),
            )
            start = end
        }
        // Frames where the near end is actually voiced (within 10 dB of its level), double talk only.
        val dtStart = Phase.FAR_ONLY.seconds * FRAMES_PER_SECOND
        val dtEnd = dtStart + Phase.DOUBLE_TALK.seconds * FRAMES_PER_SECOND
        val voiced = (dtStart until dtEnd).filter { dbfs(meanPower(near, it, it + 1)) > scenario.nearDbfs - 10f }
        val openA = voiced.count { a.transmit[it] }
        val openC = voiced.count { c.transmit[it] }
        val zeroA = voiced.count { a.power[it] < 1.0 }
        val levelA = dbfs(voiced.sumOf { a.power[it] } / voiced.size)
        val levelC = dbfs(voiced.sumOf { c.power[it] } / voiced.size)
        log(
            String.format(
                Locale.ROOT,
                "voiced near-end frames in double talk: %d; out A %.1f dBFS vs near alone (C) %.1f dBFS; " +
                    "gate open A %.0f %% vs C %.0f %%; frames zeroed in A %d",
                voiced.size, levelA, levelC, 100f * openA / voiced.size, 100f * openC / voiced.size, zeroA,
            ),
        )
    }

    private fun build(chain: Chain): CaptureChain {
        val factory = CapturePreprocessorFactory(log = { Log.w(TAG, it) })
        return when (chain) {
            Chain.APP_APM -> factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC)
            Chain.APP_APM_RNNOISE -> factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC)
            Chain.RNNOISE_ONLY -> factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.NONE)
            Chain.APM_NO_AGC_RNNOISE -> {
                val config = WebRtcApmConfig.FOR_ECHO_CANCELLATION.copy(gainControl = false)
                val apm = WebRtcApmPreprocessor(WebRtcApmNative, config)
                val stages = listOf(apm, RnnoisePreprocessor(RnnoiseNative))
                CaptureChain(ChainedPreprocessor(stages), apm, apm.farEndFrameSize)
            }
        }
    }

    /** Runs one freshly built chain over the whole timeline, like `AudioOutput` + `CapturePipeline`. */
    private fun run(chainKind: Chain, farEnd: FloatArray, mic: FloatArray): Run {
        val chain = build(chainKind)
        val apm = chain.farEndSink as WebRtcApmPreprocessor?
        val chunker = apm?.let { FarEndFrameChunker(chain.farEndFrameSize, it) }
        var now = 0L
        val vad = VoiceActivityDetector(VadConfig.adaptive(onsetFrames = APP_ONSET_FRAMES)) { now }
        val frames = mic.size / FRAME
        val result = Run(frames)
        val render = ShortArray(FRAME)
        val capture = ShortArray(FRAME)
        try {
            for (f in 0 until frames) {
                toPcm(farEnd, f * FRAME, render)
                toPcm(mic, f * FRAME, capture)
                // Per tick the far-end frame goes in before the capture frame holding its echo.
                chunker?.push(render, FRAME)
                chain.preprocessor.process(capture)
                var sumSquares = 0.0
                for (s in capture) sumSquares += s.toDouble() * s
                result.power[f] = sumSquares / FRAME
                result.transmit[f] = vad.isVoice(capture, FRAME, null)
                result.floor[f] = vad.floorDbfs
                result.threshold[f] = vad.thresholdDbfs
                now += FRAME_MS * NANOS_PER_MS
            }
            if (apm != null) {
                assertThat(apm.rejectedFrames).isEqualTo(0)
                assertThat(apm.rejectedFarEndFrames).isEqualTo(0)
            }
        } finally {
            chain.preprocessor.release()
        }
        return result
    }

    private companion object {
        const val TAG = "DoubleTalk"
        const val RATE = 48_000
        const val FRAME = 480
        const val FRAME_MS = 10
        const val FRAMES_PER_SECOND = 100
        const val NANOS_PER_MS = 1_000_000L
        const val APP_ONSET_FRAMES = 2
        const val FAR_DBFS = -18f
        const val NOISE_DBFS = -65f
        const val ECHO_DELAY_MS = 60
        const val FULL_SCALE = 32768.0
        const val TINY = 1e-3

        fun log(line: String) {
            Log.i(TAG, line)
            println(line)
        }

        fun db(ratio: Double): Float = (10 * log10(ratio)).toFloat()

        fun dbfs(power: Double): Float = db(max(power, TINY) / (FULL_SCALE * FULL_SCALE))

        fun mean(values: DoubleArray, from: Int, to: Int): Double {
            var sum = 0.0
            for (i in from until to) sum += values[i]
            return sum / (to - from)
        }

        /** Mean power of [signal] (full scale 1.0 = 32768) over frames [from, to). */
        fun meanPower(signal: FloatArray, from: Int, to: Int): Double {
            var sum = 0.0
            for (i in from * FRAME until to * FRAME) {
                val v = signal[i] * FULL_SCALE
                sum += v * v
            }
            return sum / ((to - from) * FRAME)
        }

        fun toPcm(signal: FloatArray, offset: Int, out: ShortArray) {
            for (i in out.indices) {
                val v = (signal[offset + i] * FULL_SCALE).roundToInt()
                out[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }

        fun sum(vararg signals: FloatArray): FloatArray = FloatArray(signals[0].size) { i ->
            var v = 0f
            for (s in signals) v += s[i]
            v
        }

        /** Loudspeaker-to-mic path: delay, a short reflection tail, a gentle low-pass, [gainDb]. */
        fun echoOf(far: FloatArray, gainDb: Float): FloatArray {
            val gain = 10.0.pow(gainDb / 20.0).toFloat()
            val d = ECHO_DELAY_MS * RATE / 1000
            val taps = intArrayOf(d, d + 5 * RATE / 1000, d + 15 * RATE / 1000)
            val weights = floatArrayOf(1f, 0.4f, 0.2f)
            val out = FloatArray(far.size)
            var lowPassed = 0f
            for (i in far.indices) {
                var x = 0f
                for (t in taps.indices) if (i >= taps[t]) x += weights[t] * far[i - taps[t]]
                lowPassed += 0.5f * (x - lowPassed)
                out[i] = gain * lowPassed
            }
            return out
        }

        fun whiteNoise(samples: Int, dbfs: Float, seed: Int): FloatArray {
            val random = Random(seed)
            val amplitude = 10.0.pow(dbfs / 20.0).toFloat() * sqrt(3f) // uniform: rms = a/sqrt(3)
            return FloatArray(samples) { (random.nextFloat() * 2f - 1f) * amplitude }
        }

        /** Vowel formants (F1, F2, F3) in Hz. */
        val VOWELS = arrayOf(
            doubleArrayOf(730.0, 1090.0, 2440.0), // a
            doubleArrayOf(270.0, 2290.0, 3010.0), // i
            doubleArrayOf(300.0, 870.0, 2240.0), // u
            doubleArrayOf(530.0, 1840.0, 2480.0), // e
            doubleArrayOf(570.0, 840.0, 2410.0), // o
        )

        /**
         * Deterministic voiced speech substitute: a harmonic source (with vibrato) shaped by a vowel's
         * formants, one vowel per syllable at [syllableHz], raised-cosine syllable envelopes and a
         * 300 ms pause every 2 s; harmonics stop at 3.4 kHz. Scaled so the active part is at [dbfs].
         */
        fun speechLike(samples: Int, f0: Double, syllableHz: Double, seed: Int, dbfs: Float): FloatArray {
            val random = Random(seed)
            val syllables = (samples / RATE.toDouble() * syllableHz).toInt() + 2
            val vowel = IntArray(syllables) { random.nextInt(VOWELS.size) }
            val pitch = DoubleArray(syllables) { 1.0 + (random.nextDouble() - 0.5) * 0.2 }
            val out = FloatArray(samples)
            val maxHarmonics = (3400.0 / (f0 * 0.8)).toInt()
            val gains = DoubleArray(maxHarmonics + 1)
            var phase = 0.0
            var lastSyllable = -1
            var activeEnergy = 0.0
            var activeCount = 0
            for (n in 0 until samples) {
                val t = n / RATE.toDouble()
                val syllablePos = t * syllableHz
                val k = syllablePos.toInt()
                val p = syllablePos - k
                val inPause = (t % 2.0) > 1.7
                val envelope = if (inPause || p > 0.75) 0.0 else sin(PI * p / 0.75).pow(2)
                val f = f0 * pitch[k] * (1 + 0.03 * sin(2 * PI * 0.8 * t))
                if (k != lastSyllable) {
                    lastSyllable = k
                    formantGains(f0 * pitch[k], VOWELS[vowel[k]], gains)
                }
                phase += 2 * PI * f / RATE
                if (phase > 2 * PI * 1000) phase -= 2 * PI * 1000
                val v = if (envelope > 0) harmonics(gains, phase) * envelope else 0.0
                out[n] = v.toFloat()
                if (envelope > 0.1) {
                    activeEnergy += v * v
                    activeCount++
                }
            }
            val rms = sqrt(activeEnergy / max(activeCount, 1))
            val scale = (10.0.pow(dbfs / 20.0) / rms).toFloat()
            for (n in out.indices) out[n] *= scale
            return out
        }

        /** Fills [gains] from index 1 for the harmonics of [f0] under [formants], zero outside 150..3400 Hz. */
        fun formantGains(f0: Double, formants: DoubleArray, gains: DoubleArray) {
            for (h in 1 until gains.size) {
                val fh = h * f0
                var g = 0.0
                for ((i, fm) in formants.withIndex()) {
                    val bandwidth = 80.0 + 40.0 * i
                    g += 1.0 / (1.0 + ((fh - fm) / bandwidth).pow(2)) / (i + 1)
                }
                gains[h] = if (fh in 150.0..3400.0) g / sqrt(h.toDouble()) else 0.0
            }
        }

        fun harmonics(gains: DoubleArray, phase: Double): Double {
            var v = 0.0
            for (h in 1 until gains.size) if (gains[h] != 0.0) v += gains[h] * sin(h * phase)
            return v
        }
    }
}
