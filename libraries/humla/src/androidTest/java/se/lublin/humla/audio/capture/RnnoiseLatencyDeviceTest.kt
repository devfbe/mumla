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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import se.lublin.humla.audio.capture.DoubleTalkRig.FRAME
import se.lublin.humla.audio.capture.DoubleTalkRig.Noise
import se.lublin.humla.audio.capture.DoubleTalkRig.log
import se.lublin.humla.audio.native.RnnoiseNative
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The dry path of [RnnoisePreprocessor]'s attenuation limit must line up with rnnoise's output to the
 * sample, or the mix comb-filters. Real `libhumla_native.so`.
 */
@RunWith(AndroidJUnit4::class)
class RnnoiseLatencyDeviceTest {

    /**
     * Cross-correlation of rnnoise's output with its input peaks at [RnnoisePreprocessor.LATENCY_SAMPLES].
     * rnnoise high-passes its input first (a DC blocker, about 15 Hz); its phase lead moves the
     * peak of a raw correlation of speech one sample early, so the input is filtered the same way
     * before correlating. What is left is the pure delay the dry path has to match.
     */
    @Test
    fun rnnoiseOutputLagsItsInputByTheDelayOfTheDryPath() {
        val input = pcm(DoubleTalkRig.sum(DoubleTalkRig.near(-26f), DoubleTalkRig.noise(Noise.PINK, -45f)))
        val output = process(RnnoisePreprocessor(RnnoiseNative, Float.POSITIVE_INFINITY), input)
        val reference = rnnoiseHighPass(input)
        // The near end talks from 8 s on; correlate 10-14 s.
        val from = 10 * DoubleTalkRig.RATE
        val to = 14 * DoubleTalkRig.RATE
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in 0..MAX_LAG) {
            var dot = 0.0
            var energy = 0.0
            for (n in from until to) {
                val x = reference[n - lag]
                dot += output[n] * x
                energy += x * x
            }
            val score = dot / sqrt(energy)
            if (score > bestScore) {
                bestScore = score
                best = lag
            }
        }
        log("rnnoise latency: cross-correlation peaks at $best samples")
        assertThat(best).isEqualTo(RnnoisePreprocessor.LATENCY_SAMPLES)
    }

    /** `rnn_biquad` with `denoise.c`'s coefficients (b = {-2, 1}, a = {-1.99599, 0.99600}). */
    private fun rnnoiseHighPass(input: ShortArray): DoubleArray {
        val out = DoubleArray(input.size)
        var mem0 = 0.0
        var mem1 = 0.0
        for (i in input.indices) {
            val x = input[i].toDouble()
            val y = x + mem0
            mem0 = mem1 + (-2 * x - (-1.99599) * y)
            mem1 = 1 * x - 0.99600 * y
            out[i] = y
        }
        return out
    }

    /** No limit is rnnoise alone, sample for sample: the default path of today's chain. */
    @Test
    fun noLimitIsBitIdenticalToRnnoiseAlone() {
        val input = pcm(DoubleTalkRig.sum(DoubleTalkRig.near(-26f), DoubleTalkRig.noise(Noise.BABBLE, -40f)))
        val limited = process(RnnoisePreprocessor(RnnoiseNative, Float.POSITIVE_INFINITY), input)
        val handle = RnnoiseNative.create()
        val bare = input.copyOf()
        val frame = ShortArray(FRAME)
        try {
            for (offset in 0 until bare.size - FRAME + 1 step FRAME) {
                System.arraycopy(bare, offset, frame, 0, FRAME)
                RnnoiseNative.processFrame(handle, frame)
                System.arraycopy(frame, 0, bare, offset, FRAME)
            }
        } finally {
            RnnoiseNative.destroy(handle)
        }
        assertThat(limited.contentEquals(bare)).isTrue()
    }

    /**
     * The near end over pink noise through a strong mix (6 dB limit: half dry, half wet, where a
     * comb would be deepest), 8 s of talk averaged. Aligned, the transfer function is smooth across
     * frequency, smoother than rnnoise alone (whose band gains and pitch filter ripple by themselves);
     * the same mix with the dry path one frame short shows the 100 Hz comb, which proves the measure
     * can see one. (Stationary noise alone cannot: rnnoise removes nearly all of it, and with no wet
     * signal there is nothing for the dry path to comb against.)
     */
    @Test
    fun limitedMixHasNoCombNotches() {
        val input = pcm(DoubleTalkRig.sum(DoubleTalkRig.near(-26f), DoubleTalkRig.noise(Noise.PINK, -45f)))
        val inputSpectrum = spectrum(input)
        val ripple = { limitDb: Float, latency: Int ->
            val output = process(RnnoisePreprocessor(RnnoiseNative, limitDb, latency), input)
            ripple(inputSpectrum, spectrum(output))
        }
        val bare = ripple(Float.POSITIVE_INFINITY, RnnoisePreprocessor.LATENCY_SAMPLES)
        val aligned = ripple(6f, RnnoisePreprocessor.LATENCY_SAMPLES)
        val oneFrameShort = ripple(6f, RnnoisePreprocessor.LATENCY_SAMPLES - FRAME)
        val shipped = ripple(RnnoisePreprocessor.ATTENUATION_LIMIT_DB, RnnoisePreprocessor.LATENCY_SAMPLES)
        log(
            String.format(
                Locale.ROOT,
                "transfer ripple: rnnoise alone %.2f dB; 6 dB limit aligned %.2f dB, one frame short %.2f dB; " +
                    "shipped limit %.2f dB",
                bare, aligned, oneFrameShort, shipped,
            ),
        )
        assertWithMessage("aligned mix ripple").that(aligned).isAtMost(bare)
        assertWithMessage("a misaligned mix is visible").that(oneFrameShort).isAtLeast(3 * aligned)
        assertWithMessage("a misaligned mix is visible").that(oneFrameShort).isGreaterThan(bare)
        assertWithMessage("shipped mix ripple").that(shipped).isAtMost(bare + RIPPLE_MARGIN_DB)
    }

    private fun pcm(signal: FloatArray) = ShortArray(signal.size) {
        (signal[it] * FULL_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun process(stage: RnnoisePreprocessor, input: ShortArray): ShortArray {
        val out = input.copyOf()
        val frame = ShortArray(FRAME)
        try {
            for (offset in 0 until out.size - FRAME + 1 step FRAME) {
                System.arraycopy(out, offset, frame, 0, FRAME)
                assertThat(stage.process(frame)).isNotNull()
                System.arraycopy(frame, 0, out, offset, FRAME)
            }
        } finally {
            stage.release()
        }
        return out
    }

    /** Welch power spectrum: Hann windows of [FFT] samples, half overlap, while the near end talks. */
    private fun spectrum(signal: ShortArray): DoubleArray {
        val power = DoubleArray(FFT / 2)
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        var start = DoubleTalkRig.dtStart * FRAME
        while (start + FFT <= signal.size) {
            for (i in 0 until FFT) {
                re[i] = signal[start + i] * (0.5 - 0.5 * cos(2 * PI * i / FFT))
                im[i] = 0.0
            }
            fft(re, im)
            for (k in power.indices) power[k] += re[k] * re[k] + im[k] * im[k]
            start += FFT / 2
        }
        return power
    }

    /**
     * RMS deviation, 300 Hz-6 kHz, of the transfer function in dB from its own moving average over
     * about +-140 Hz: a comb (notches every 100 Hz for one frame of misalignment) shows here, the
     * broad band gains rnnoise applies do not.
     */
    private fun ripple(input: DoubleArray, output: DoubleArray): Double {
        val binHz = DoubleTalkRig.RATE.toDouble() / FFT
        val lo = (300 / binHz).toInt()
        val hi = (6000 / binHz).toInt()
        val db = DoubleArray(input.size) { 10 * log10((output[it] + 1e-9) / (input[it] + 1e-9)) }
        var sum = 0.0
        for (k in lo..hi) {
            var smooth = 0.0
            for (j in k - SMOOTH_BINS..k + SMOOTH_BINS) smooth += db[j]
            smooth /= 2 * SMOOTH_BINS + 1
            val d = db[k] - smooth
            sum += d * d
        }
        return sqrt(sum / (hi - lo + 1))
    }

    /** In-place radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(angle * k)
                    val wi = sin(angle * k)
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                }
            }
            len = len shl 1
        }
    }

    private companion object {
        const val FULL_SCALE = 32768f
        const val MAX_LAG = 2 * DoubleTalkRig.RATE / 50 // 40 ms
        const val FFT = 4096
        const val SMOOTH_BINS = 12
        const val RIPPLE_MARGIN_DB = 0.1
    }
}
