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

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The matched filter of the acoustic device tests: cross-correlation by FFT. */
internal object Correlation {
    /**
     * The cross-correlation of [x] with [h] (sum over i of x(lag + i) h(i)) at every lag where
     * [h] fits, by FFT.
     */
    fun crossCorrelate(x: DoubleArray, h: DoubleArray): DoubleArray {
        var n = 1
        while (n < x.size + h.size) n *= 2
        val xr = x.copyOf(n)
        val xi = DoubleArray(n)
        val hr = h.copyOf(n)
        val hi = DoubleArray(n)
        fft(xr, xi, inverse = false)
        fft(hr, hi, inverse = false)
        for (i in 0 until n) { // X * conj(H)
            val re = xr[i] * hr[i] + xi[i] * hi[i]
            val im = xi[i] * hr[i] - xr[i] * hi[i]
            xr[i] = re
            xi[i] = im
        }
        fft(xr, xi, inverse = true)
        return DoubleArray(x.size - h.size + 1) { xr[it] / n }
    }

    /** In-place iterative radix-2 FFT; the inverse is unscaled. */
    fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
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
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * if (inverse) 1 else -1
            val wr = cos(angle)
            val wi = sin(angle)
            var start = 0
            while (start < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = start + k
                    val b = a + len / 2
                    val tr = re[b] * cr - im[b] * ci
                    val ti = re[b] * ci + im[b] * cr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                start += len
            }
            len = len shl 1
        }
    }
}
