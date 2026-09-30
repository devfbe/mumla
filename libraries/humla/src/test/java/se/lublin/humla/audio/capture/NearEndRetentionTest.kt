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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** The double-talk retention metric of the device harness: level, blocks and dropouts. */
class NearEndRetentionTest {
    /** "Speech": two tones, [FRAMES] frames. */
    private val words = ShortArray(FRAMES * FRAME) {
        (6000 * sin(it * 0.0731) + 3000 * sin(it * 0.2913)).roundToInt().toShort()
    }

    private val starts = IntArray(FRAMES) { it * FRAME }

    private fun scaled(gain: Double, from: ShortArray = words) =
        ShortArray(from.size) { (from[it] * gain).roundToInt().toShort() }

    private fun measure(doubleTalk: ShortArray, alone: ShortArray = words) =
        NearEndRetention.measure(doubleTalk, alone, starts, starts, FRAME)

    @Test
    fun `the same words come out at 0 dB with nothing dropped`() {
        val r = measure(words.copyOf())

        assertThat(r.retentionDb).isWithin(0.01).of(0.0)
        assertThat(r.medianBlockDb).isWithin(0.01).of(0.0)
        assertThat(r.droppedShare).isEqualTo(0.0)
        assertThat(r.dropoutsPerSecond).isEqualTo(0.0)
        assertThat(r.frames).isEqualTo(FRAMES)
    }

    @Test
    fun `half the amplitude is 6 dB down`() {
        assertThat(measure(scaled(0.5)).retentionDb).isWithin(0.05).of(20 * log10(0.5))
    }

    /** The echo is another talker: it adds power but not the user's words, and must not count. */
    @Test
    fun `an uncorrelated echo on top does not count as the user`() {
        val random = Random(7)
        val echo = ShortArray(words.size) { (random.nextDouble(-1.0, 1.0) * 4000).roundToInt().toShort() }
        val doubleTalk = ShortArray(words.size) { (words[it] * 0.5 + echo[it]).roundToInt().toShort() }

        assertThat(measure(doubleTalk).retentionDb).isWithin(0.5).of(20 * log10(0.5))
    }

    /** The platform's half duplex or a denoiser deleting whole stretches: level, median and dropouts. */
    @Test
    fun `deleted stretches cost their share of the power and count as dropouts`() {
        // Frames 0-9 kept, 10-19 deleted, 20-29 kept, ... : half the blocks, five stretches.
        val doubleTalk = words.copyOf()
        for (f in 0 until FRAMES) if ((f / 10) % 2 == 1) doubleTalk.fill(0, f * FRAME, (f + 1) * FRAME)

        val r = measure(doubleTalk)

        assertThat(r.retentionDb).isWithin(0.1).of(10 * log10(0.5))
        assertThat(r.droppedShare).isWithin(1e-9).of(0.5)
        assertThat(r.dropoutsPerSecond).isWithin(1e-9).of(5 / (FRAMES / 100.0))
    }

    @Test
    fun `a frame 25 dB down is dropped, one 15 dB down is not`() {
        val doubleTalk = words.copyOf()
        val down25 = 10.0.pow(-25 / 20.0)
        val down15 = 10.0.pow(-15 / 20.0)
        for (i in 0 until FRAME) doubleTalk[3 * FRAME + i] = (words[3 * FRAME + i] * down25).roundToInt().toShort()
        for (i in 0 until FRAME) doubleTalk[7 * FRAME + i] = (words[7 * FRAME + i] * down15).roundToInt().toShort()

        val r = measure(doubleTalk)

        assertThat(r.droppedShare).isWithin(1e-9).of(1.0 / FRAMES)
    }

    /** Silence alone says nothing about the user, so a silent frame in double talk is no dropout. */
    @Test
    fun `frames silent alone are no dropouts and no blocks`() {
        val alone = ShortArray(words.size)
        val r = measure(ShortArray(words.size), alone)

        assertThat(r.droppedShare).isEqualTo(0.0)
        assertThat(r.retentionDb).isEqualTo(NearEndRetention.NOTHING_DB)
    }

    /** The two copies sit at different places in their recordings; only the listed starts count. */
    @Test
    fun `each frame is compared with the same words wherever they start`() {
        val offset = 1234
        val recording = ShortArray(words.size + offset + FRAME)
        scaled(0.25).copyInto(recording, offset)

        val r = NearEndRetention.measure(recording, words, IntArray(FRAMES) { offset + it * FRAME }, starts, FRAME)

        assertThat(r.retentionDb).isWithin(0.05).of(20 * log10(0.25))
    }

    @Test
    fun `no frames to compare give the floor value, not a crash`() {
        val r = NearEndRetention.measure(words, words, IntArray(0), IntArray(0), FRAME)

        assertThat(r.retentionDb).isEqualTo(NearEndRetention.NOTHING_DB)
        assertThat(r.frames).isEqualTo(0)
    }

    private fun Double.pow(x: Double) = Math.pow(this, x)

    private companion object {
        const val FRAME = 480
        const val FRAMES = 100
    }
}
