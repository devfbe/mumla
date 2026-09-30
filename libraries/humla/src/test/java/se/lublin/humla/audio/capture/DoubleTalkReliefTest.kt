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
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * When RNNoise's limit eases: only while the far end talks and the frames in front of RNNoise show
 * the user talking for three frames running, and back to the user's limit afterwards.
 */
class DoubleTalkReliefTest {
    private var now = 0L
    private val farEnd = FarEndActivity({ now })
    private val relief = DoubleTalkRelief(farEnd, clock = { now })

    /** A voiced frame (two tones) at about [dbfs] RMS. */
    private fun speech(dbfs: Float, seed: Int = 0): ShortArray {
        val amplitude = FULL_SCALE * 10.0.pow(dbfs / 20.0)
        return ShortArray(FRAME) { (amplitude * sin((seed * FRAME + it) * 0.0731)).roundToInt().toShort() }
    }

    private val silence = ShortArray(FRAME)

    private fun farTalks() = farEnd.onFarEndFrame(speech(-20f))

    /** One 10 ms tick: the far end plays if [far], then [input] reaches RNNoise; the limit for the output. */
    private fun tick(input: ShortArray, user: Float = USER, far: Boolean = true): Float {
        if (far) farTalks()
        val limit = relief.limitFor(input, FRAME, user)
        now += FRAME_NANOS
        return limit
    }

    @Test
    fun `the user talking alone keeps the user's limit, sample for sample`() {
        val limits = List(50) { tick(speech(-20f, it), far = false) }

        assertThat(limits.toSet()).containsExactly(USER)
        assertThat(relief.engaged).isFalse()
    }

    @Test
    fun `the far end talking alone keeps the user's limit`() {
        val limits = List(50) { tick(silence) }

        assertThat(limits.toSet()).containsExactly(USER)
    }

    /**
     * Two frames of evidence are not enough; the third engages, and it is the frame RNNoise outputs
     * next that is relieved, so the first syllable is not clipped. Then 60 % of the remaining way per
     * frame.
     */
    @Test
    fun `talking over the far end eases the limit from the third voiced frame on`() {
        tick(silence)
        assertThat(tick(speech(-20f, 1))).isEqualTo(USER)
        assertThat(tick(speech(-20f, 2))).isEqualTo(USER)

        val first = tick(speech(-20f, 3))
        val second = tick(speech(-20f, 4))

        assertThat(relief.engaged).isTrue()
        assertThat(first).isWithin(1e-4f).of(USER * (1 - DoubleTalkRelief.RELAX_PER_FRAME))
        assertThat(second).isWithin(1e-4f).of(USER * (1 - DoubleTalkRelief.RELAX_PER_FRAME).pow(2))
        val settled = List(20) { tick(speech(-20f, 5 + it)) }.last()
        assertThat(settled).isEqualTo(DoubleTalkRelief.RELIEF_LIMIT_DB)
    }

    /** The platform's half duplex leaks 20 ms bursts of echo; they must not open RNNoise. */
    @Test
    fun `a two-frame burst never engages the relief`() {
        repeat(20) { tick(silence) }
        val limits = mutableListOf<Float>()
        repeat(10) {
            limits += tick(speech(-20f, 1))
            limits += tick(speech(-20f, 2))
            repeat(4) { limits += tick(silence) }
        }

        assertThat(limits.toSet()).containsExactly(USER)
    }

    @Test
    fun `frames below the near-end floor are no evidence`() {
        repeat(200) { tick(silence) }

        val limits = List(30) { tick(speech(DoubleTalkRelief.NEAR_FLOOR_DBFS - 5f, it)) }

        assertThat(limits.toSet()).containsExactly(USER)
    }

    /** Short gaps inside a sentence keep it; three quiet frames end it, and it fades out. */
    @Test
    fun `the relief holds across a one-frame gap and fades out after the user stops`() {
        repeat(20) { tick(speech(-20f, it)) }
        assertThat(tick(silence)).isEqualTo(DoubleTalkRelief.RELIEF_LIMIT_DB)
        repeat(5) { tick(speech(-20f, it)) }

        tick(silence)
        tick(silence)
        val released = tick(silence)
        assertThat(relief.engaged).isFalse()
        assertThat(released).isWithin(1e-3f).of(USER * DoubleTalkRelief.RESTORE_PER_FRAME)
        val later = List(100) { tick(silence) }
        assertThat(later.last()).isEqualTo(USER)
        assertWithMessage("the fade out is monotonic").that(later).isInOrder()
    }

    /** The far end falling silent while the user talks on is single talk again. */
    @Test
    fun `the relief ends when the far end has been silent for the hold`() {
        repeat(20) { tick(speech(-20f, it)) }
        val holdFrames = (FarEndActivity.HOLD_MS / 10).toInt()
        repeat(holdFrames) { tick(speech(-20f, it), far = false) }

        assertThat(relief.engaged).isFalse()
        assertThat(List(100) { tick(speech(-20f, it), far = false) }.last()).isEqualTo(USER)
    }

    /** "Unlimited" comes back exactly, so RNNoise's own output is untouched outside double talk. */
    @Test
    fun `an unlimited user setting is eased while relieved and exactly unlimited otherwise`() {
        val inf = Float.POSITIVE_INFINITY
        assertThat(tick(silence, user = inf)).isEqualTo(inf)
        repeat(3) { tick(speech(-20f, it), user = inf) }
        val eased = tick(speech(-20f, 3), user = inf)
        assertThat(eased).isLessThan(60f)

        val after = List(200) { tick(silence, user = inf) }
        assertThat(after.last()).isEqualTo(inf)
    }

    /** The user's own limit below the relief's is never raised. */
    @Test
    fun `a user limit below the relief limit is kept`() {
        val relief = DoubleTalkRelief(farEnd, reliefLimitDb = 6f, clock = { now })
        val limits = List(30) {
            farTalks()
            relief.limitFor(speech(-20f, it), FRAME, 3f).also { now += FRAME_NANOS }
        }

        assertThat(limits.toSet()).containsExactly(3f)
    }

    private companion object {
        const val FRAME = 480
        const val FULL_SCALE = 32768.0
        const val USER = 18f
        const val FRAME_NANOS = 10_000_000L
    }
}
