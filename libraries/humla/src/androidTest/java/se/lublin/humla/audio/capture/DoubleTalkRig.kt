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
import com.google.common.truth.Truth.assertThat
import se.lublin.humla.audio.native.RnnoiseNative
import se.lublin.humla.audio.native.WebRtcApmNative
import java.util.Locale
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Synthetic double talk through the real capture chain (`libhumla_native.so`), shared by the
 * device tests in this package.
 *
 * Timeline (48 kHz mono, 10 ms frames): far end only (AEC3 converges), then double talk, then near
 * end only. Three runs per chain with an identical far-end reference:
 * - A: mic = echo + near + noise (what the phone records),
 * - B: mic = echo + noise (A without the near end: the echo-only case),
 * - C: mic = near + noise, far-end reference silent (the near end as the chain passes it alone).
 * Near-end retention in a phase is `10*log10((P_A - P_B) / P_C)`. The gate is the app's default
 * adaptive VAD (`VadConfig.adaptive`, onset 2 frames) on the chain's output.
 */
internal object DoubleTalkRig {
    const val TAG = "DoubleTalk"
    const val RATE = 48_000
    const val FRAME = 480
    const val FRAME_MS = 10
    const val FRAMES_PER_SECOND = 100
    const val FAR_DBFS = -18f
    const val NOISE_DBFS = -65f
    const val ECHO_DELAY_MS = 60
    private const val NANOS_PER_MS = 1_000_000L
    private const val APP_ONSET_FRAMES = 2
    private const val FULL_SCALE = 32768.0
    private const val TINY = 1e-3

    enum class Phase(val seconds: Int) { FAR_ONLY(8), DOUBLE_TALK(4), NEAR_ONLY(4) }

    val frames = Phase.entries.sumOf { it.seconds } * FRAMES_PER_SECOND
    private val samples = frames * FRAME
    val dtStart = Phase.FAR_ONLY.seconds * FRAMES_PER_SECOND
    val dtEnd = dtStart + Phase.DOUBLE_TALK.seconds * FRAMES_PER_SECOND

    /** Echo-only statistics skip AEC3's first two seconds of convergence (reported separately). */
    const val CONVERGED_FRAME = 2 * FRAMES_PER_SECOND

    /**
     * The loudspeaker-to-microphone path.
     * - LINEAR: delay, two short reflections (5 and 15 ms), a gentle low-pass.
     * - NONLINEAR: a phone speaker instead: a 300 Hz high-pass (small driver), light tanh
     *   saturation (about 2 dB of compression at the far end's peaks), then a room with the direct
     *   path, early reflections and a sparse exponentially decaying tail to 300 ms (RT60 400 ms),
     *   well past AEC3's default 52 ms linear filter.
     */
    enum class EchoPath { LINEAR, NONLINEAR }

    /** [talkers] picks the far-end and near-end utterances (vowels, pitch contour); 0 is the reference pair. */
    class Scenario(val echoGainDb: Float, val nearDbfs: Float, val path: EchoPath, val talkers: Int = 0) {
        val name: String
            get() = String.format(Locale.ROOT, "%s echo %+3.0f dB, near %.0f dBFS", path, echoGainDb, nearDbfs) +
                if (talkers != 0) " #$talkers" else ""
    }

    private val farByTalkers = HashMap<Int, FloatArray>()
    private val nearByTalkers = HashMap<Int, FloatArray>()

    /** The far end, near end and noise do not depend on the scenario's echo; generated once. */
    val far: FloatArray get() = far(0)

    fun far(talkers: Int): FloatArray = farByTalkers.getOrPut(talkers) {
        speechLike(samples, f0 = 115.0, syllableHz = 4.1, seed = 1 + 10 * talkers, dbfs = FAR_DBFS).also {
            for (i in (Phase.FAR_ONLY.seconds + Phase.DOUBLE_TALK.seconds) * RATE until samples) it[i] = 0f
        }
    }

    private fun nearUnit(talkers: Int): FloatArray = nearByTalkers.getOrPut(talkers) {
        speechLike(samples, f0 = 205.0, syllableHz = 3.3, seed = 2 + 10 * talkers, dbfs = 0f).also {
            for (i in 0 until Phase.FAR_ONLY.seconds * RATE) it[i] = 0f
        }
    }
    val noiseFloor: FloatArray by lazy { whiteNoise(samples, NOISE_DBFS, seed = 3) }
    val silence: FloatArray by lazy { FloatArray(samples) }

    fun near(nearDbfs: Float, talkers: Int = 0): FloatArray {
        val gain = 10.0.pow(nearDbfs / 20.0).toFloat()
        val unit = nearUnit(talkers)
        return FloatArray(samples) { unit[it] * gain }
    }

    fun echo(scenario: Scenario): FloatArray = when (scenario.path) {
        EchoPath.LINEAR -> linearEcho(far(scenario.talkers), scenario.echoGainDb)
        EchoPath.NONLINEAR -> nonlinearEcho(far(scenario.talkers), scenario.echoGainDb)
    }

    /** Frames in double talk where the near end is actually voiced (within 10 dB of its level). */
    fun voicedDoubleTalkFrames(nearDbfs: Float, talkers: Int = 0): List<Int> {
        val near = near(nearDbfs, talkers)
        return (dtStart until dtEnd).filter { dbfs(meanPower(near, it, it + 1)) > nearDbfs - 10f }
    }

    /** Per-frame output power, the gate trace and a hash of every output sample of one run. */
    class Run(frames: Int) {
        val power = DoubleArray(frames)
        val transmit = BooleanArray(frames)
        val floor = FloatArray(frames)
        val threshold = FloatArray(frames)
        var hash = 0L
    }

    /** The app's WEBRTC chain with [aec3] in place of the shipped tuning, optionally with RNNoise. */
    fun appChain(aec3: Aec3Tuning?, rnnoise: Boolean = true): CaptureChain {
        val apm = WebRtcApmPreprocessor(WebRtcApmNative, WebRtcApmConfig.FOR_ECHO_CANCELLATION.copy(aec3 = aec3))
        val denoiser = RnnoisePreprocessor(RnnoiseNative, Float.POSITIVE_INFINITY)
        val stages = if (rnnoise) listOf(apm, denoiser) else listOf(apm)
        return CaptureChain(if (rnnoise) ChainedPreprocessor(stages) else apm, apm, apm.farEndFrameSize)
    }

    /**
     * AEC3 (+ high-pass), RNNoise limited to [limitDb], and AGC2 either in the canceller's APM in
     * front of RNNoise (the chain before the limit) or in a second APM behind it. Without [aec] it is
     * RNNoise alone (echo cancellation off).
     */
    fun limitedChain(limitDb: Float, agcAfter: Boolean, aec: Boolean = true): CaptureChain {
        val denoiser = RnnoisePreprocessor(RnnoiseNative, limitDb)
        if (!aec) return CaptureChain(denoiser, null)
        val apm = WebRtcApmPreprocessor(
            WebRtcApmNative, WebRtcApmConfig.FOR_ECHO_CANCELLATION.copy(gainControl = !agcAfter),
        )
        val stages = if (agcAfter) {
            val agc = WebRtcApmConfig(
                echoCancellation = false, noiseSuppression = false, gainControl = true, highPass = false,
            )
            listOf(apm, denoiser, WebRtcApmPreprocessor(WebRtcApmNative, agc))
        } else {
            listOf(apm, denoiser)
        }
        return CaptureChain(ChainedPreprocessor(stages), apm, apm.farEndFrameSize)
    }

    /** Runs a freshly built [chain] over the whole timeline, like `AudioOutput` + `CapturePipeline`. */
    fun run(chain: CaptureChain, farEnd: FloatArray, mic: FloatArray): Run {
        val apm = chain.farEndSink as WebRtcApmPreprocessor?
        val chunker = apm?.let { FarEndFrameChunker(chain.farEndFrameSize, it) }
        var now = 0L
        val vad = VoiceActivityDetector(VadConfig.adaptive(onsetFrames = APP_ONSET_FRAMES)) { now }
        val result = Run(frames)
        val render = ShortArray(FRAME)
        val capture = ShortArray(FRAME)
        var hash = -0x340d631b7bdddcdbL // FNV-1a offset basis
        try {
            for (f in 0 until frames) {
                toPcm(farEnd, f * FRAME, render)
                toPcm(mic, f * FRAME, capture)
                // Per tick the far-end frame goes in before the capture frame holding its echo.
                chunker?.push(render, FRAME)
                chain.preprocessor.process(capture)
                var sumSquares = 0.0
                for (s in capture) {
                    sumSquares += s.toDouble() * s
                    hash = (hash xor (s.toLong() and 0xffff)) * 0x100000001b3L
                }
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
        result.hash = hash
        return result
    }

    /** Run C (the near end alone) of one chain; it does not depend on the echo. */
    fun runNearAlone(chain: CaptureChain, nearDbfs: Float, talkers: Int = 0): Run =
        run(chain, silence, sum(near(nearDbfs, talkers), noiseFloor))

    /** Share of voiced near-end frames (double talk and near-only phases) on which [nearAlone]'s gate is open. */
    fun nearAloneGate(nearAlone: Run, nearDbfs: Float, talkers: Int = 0): Float {
        val near = near(nearDbfs, talkers)
        val voiced = (dtStart until frames).filter { dbfs(meanPower(near, it, it + 1)) > nearDbfs - 10f }
        return voiced.count { nearAlone.transmit[it] }.toFloat() / voiced.size
    }

    /** Background noise with no speech: fan-like pink noise, or babble (six talkers at once). */
    enum class Noise { PINK, BABBLE }

    private val noises = HashMap<Pair<Noise, Float>, FloatArray>()

    fun noise(kind: Noise, dbfs: Float): FloatArray = noises.getOrPut(kind to dbfs) {
        when (kind) {
            Noise.PINK -> pinkNoise(samples, dbfs, seed = 5)
            Noise.BABBLE -> babble(samples, dbfs)
        }
    }

    /** What a chain does with noise alone; statistics skip the first two seconds (VAD floor settling). */
    class NoiseMeasurement(
        /** Share of frames on which the gate opens. */
        val falseOpen: Float,
        /** Mean output level, dBFS. */
        val residualDbfs: Float,
        /** 95th percentile of frame output levels, dBFS. */
        val residualP95Dbfs: Float,
        /** Mean input level, dBFS. */
        val inputDbfs: Float,
    )

    fun measureNoise(chain: CaptureChain, noise: FloatArray): NoiseMeasurement {
        val mic = sum(noise, noiseFloor)
        val r = run(chain, silence, mic)
        val settled = CONVERGED_FRAME until frames
        val levels = settled.map { dbfs(r.power[it]) }.sorted()
        return NoiseMeasurement(
            falseOpen = settled.count { r.transmit[it] }.toFloat() / (frames - CONVERGED_FRAME),
            residualDbfs = dbfs(mean(r.power, CONVERGED_FRAME, frames)),
            residualP95Dbfs = levels[(levels.size * 0.95).toInt()],
            inputDbfs = dbfs(meanPower(mic, CONVERGED_FRAME, frames)),
        )
    }

    /** What one tuning does to one scenario, full app chain. */
    @Suppress("LongParameterList") // a plain record of the eight numbers the harness reports
    class Measurement(
        /** Near-end power kept in double talk relative to the near end alone, dB. */
        val keptDb: Float,
        /** Share of voiced near-end frames in double talk on which the gate is open. */
        val gateOpen: Float,
        /** Share of voiced near-end frames in double talk the chain output as digital silence. */
        val zeroed: Float,
        /** Echo-only run B, converged (2 s to the end of double talk): mean output level, dBFS. */
        val residualDbfs: Float,
        /** The same, 95th percentile of frame levels. */
        val residualP95Dbfs: Float,
        /** Share of echo-only frames, converged, on which the gate opens (false transmit). */
        val falseOpen: Float,
        /** The same during the first two seconds of the call, while AEC3 converges. */
        val falseOpenConverging: Float,
        /** Echo level at the microphone during far end only, dBFS. */
        val echoInDbfs: Float,
    )

    /** The microphone signals of runs A (echo + near + noise) and B (echo + noise) for one scenario. */
    class Mics(val a: FloatArray, val b: FloatArray, val echoInDbfs: Float)

    fun mics(scenario: Scenario): Mics {
        val echo = echo(scenario)
        return Mics(
            a = sum(echo, near(scenario.nearDbfs, scenario.talkers), noiseFloor),
            b = sum(echo, noiseFloor),
            echoInDbfs = dbfs(meanPower(echo, 0, dtStart)),
        )
    }

    fun measure(
        scenario: Scenario,
        build: () -> CaptureChain,
        nearAlone: Run,
        mics: Mics = mics(scenario),
    ): Measurement {
        val far = far(scenario.talkers)
        val a = run(build(), far, mics.a)
        val b = run(build(), far, mics.b)
        val c = nearAlone
        val kept = db(max(mean(a.power, dtStart, dtEnd) - mean(b.power, dtStart, dtEnd), TINY) /
            max(mean(c.power, dtStart, dtEnd), TINY))
        val voiced = voicedDoubleTalkFrames(scenario.nearDbfs, scenario.talkers)
        val echoOnly = CONVERGED_FRAME until dtEnd
        val levels = echoOnly.map { dbfs(b.power[it]) }.sorted()
        return Measurement(
            keptDb = kept,
            gateOpen = voiced.count { a.transmit[it] }.toFloat() / voiced.size,
            zeroed = voiced.count { a.power[it] < 1.0 }.toFloat() / voiced.size,
            residualDbfs = dbfs(mean(b.power, echoOnly.first, echoOnly.last + 1)),
            residualP95Dbfs = levels[(levels.size * 0.95).toInt()],
            falseOpen = echoOnly.count { b.transmit[it] }.toFloat() / (echoOnly.last + 1 - echoOnly.first),
            falseOpenConverging = (0 until CONVERGED_FRAME).count { b.transmit[it] }.toFloat() / CONVERGED_FRAME,
            echoInDbfs = mics.echoInDbfs,
        )
    }

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

    private fun toPcm(signal: FloatArray, offset: Int, out: ShortArray) {
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

    private fun linearEcho(far: FloatArray, gainDb: Float): FloatArray {
        val d = ECHO_DELAY_MS * RATE / 1000
        return room(far, intArrayOf(d, d + 5 * RATE / 1000, d + 15 * RATE / 1000), floatArrayOf(1f, 0.4f, 0.2f), gainDb)
    }

    private fun nonlinearEcho(far: FloatArray, gainDb: Float): FloatArray {
        // Speaker: first-order high-pass at 300 Hz, then saturation.
        val alpha = (1.0 / (1.0 + 2 * PI * SPEAKER_HIGH_PASS_HZ / RATE)).toFloat()
        val driven = FloatArray(far.size)
        var prevIn = 0f
        var prevOut = 0f
        for (i in far.indices) {
            val hp = alpha * (prevOut + far[i] - prevIn)
            prevIn = far[i]
            prevOut = hp
            driven[i] = tanh(SPEAKER_DRIVE * hp) / SPEAKER_DRIVE
        }
        // Room: direct path, two early reflections, then a sparse decaying tail to 300 ms.
        val d = ECHO_DELAY_MS * RATE / 1000
        val random = Random(4)
        val tailTaps = 80
        val taps = IntArray(3 + tailTaps)
        val weights = FloatArray(3 + tailTaps)
        taps[0] = d; weights[0] = 1f
        taps[1] = d + 7 * RATE / 1000; weights[1] = -0.5f
        taps[2] = d + 13 * RATE / 1000; weights[2] = 0.35f
        val decayPerSecond = 3 * ln(10.0) / RT60_SECONDS // amplitude: -60 dB at RT60
        for (k in 0 until tailTaps) {
            val t = 0.005 + random.nextDouble() * (TAIL_SECONDS - 0.005)
            val sign = if (random.nextBoolean()) 1f else -1f
            taps[3 + k] = d + (t * RATE).toInt()
            weights[3 + k] = sign * (0.35 * exp(-decayPerSecond * t) * (0.5 + 0.5 * random.nextDouble())).toFloat()
        }
        return room(driven, taps, weights, gainDb)
    }

    /** Sparse FIR with [taps]/[weights], a gentle low-pass, and [gainDb]. */
    private fun room(x: FloatArray, taps: IntArray, weights: FloatArray, gainDb: Float): FloatArray {
        val gain = 10.0.pow(gainDb / 20.0).toFloat()
        val out = FloatArray(x.size)
        for (t in taps.indices) {
            val lag = taps[t]
            val w = weights[t]
            for (i in lag until x.size) out[i] += w * x[i - lag]
        }
        var lowPassed = 0f
        for (i in out.indices) {
            lowPassed += 0.5f * (out[i] - lowPassed)
            out[i] = gain * lowPassed
        }
        return out
    }

    private const val SPEAKER_HIGH_PASS_HZ = 300.0
    private const val SPEAKER_DRIVE = 2f
    private const val RT60_SECONDS = 0.4
    private const val TAIL_SECONDS = 0.3

    private fun whiteNoise(samples: Int, dbfs: Float, seed: Int): FloatArray {
        val random = Random(seed)
        val amplitude = 10.0.pow(dbfs / 20.0).toFloat() * sqrt(3f) // uniform: rms = a/sqrt(3)
        return FloatArray(samples) { (random.nextFloat() * 2f - 1f) * amplitude }
    }

    /** Pink (-3 dB per octave) noise, Paul Kellet's filter over white noise, scaled to [dbfs]. */
    private fun pinkNoise(samples: Int, dbfs: Float, seed: Int): FloatArray {
        val random = Random(seed)
        val b = DoubleArray(7)
        val out = FloatArray(samples)
        for (i in out.indices) {
            val white = random.nextDouble() * 2 - 1
            b[0] = 0.99886 * b[0] + white * 0.0555179
            b[1] = 0.99332 * b[1] + white * 0.0750759
            b[2] = 0.96900 * b[2] + white * 0.1538520
            b[3] = 0.86650 * b[3] + white * 0.3104856
            b[4] = 0.55000 * b[4] + white * 0.5329522
            b[5] = -0.7616 * b[5] - white * 0.0168980
            out[i] = (b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + white * 0.5362).toFloat()
            b[6] = white * 0.115926
        }
        return scaled(out, dbfs)
    }

    /** Six speech-like talkers at once, each with its own pitch, rate and pauses, scaled to [dbfs]. */
    private fun babble(samples: Int, dbfs: Float): FloatArray {
        val out = FloatArray(samples)
        for (k in 0 until BABBLE_TALKERS) {
            val talker =
                speechLike(samples, f0 = 100.0 + 25.0 * k, syllableHz = 3.0 + 0.35 * k, seed = 100 + k, dbfs = 0f)
            // Stagger the pauses, which speechLike puts at the same place in every 2 s.
            val shift = k * RATE / 3
            for (i in out.indices) out[i] += talker[(i + shift) % samples]
        }
        return scaled(out, dbfs)
    }

    private fun scaled(signal: FloatArray, dbfs: Float): FloatArray {
        var sumSquares = 0.0
        for (v in signal) sumSquares += v.toDouble() * v
        val gain = (10.0.pow(dbfs / 20.0) / sqrt(sumSquares / signal.size)).toFloat()
        for (i in signal.indices) signal[i] *= gain
        return signal
    }

    private const val BABBLE_TALKERS = 6

    /** Vowel formants (F1, F2, F3) in Hz. */
    private val VOWELS = arrayOf(
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
    private fun speechLike(samples: Int, f0: Double, syllableHz: Double, seed: Int, dbfs: Float): FloatArray {
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
    private fun formantGains(f0: Double, formants: DoubleArray, gains: DoubleArray) {
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

    private fun harmonics(gains: DoubleArray, phase: Double): Double {
        var v = 0.0
        for (h in 1 until gains.size) if (gains[h] != 0.0) v += gains[h] * sin(h * phase)
        return v
    }
}
