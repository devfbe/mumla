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
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The far end's activity: a level threshold with a hold, set on one thread and read on another. */
class FarEndActivityTest {
    private var now = 5_000_000_000L
    private val activity = FarEndActivity({ now })

    /** A constant frame whose mean square sits at [dbfs]. */
    private fun frameAt(dbfs: Float) = ShortArray(FRAME) { (FULL_SCALE * 10.0.pow(dbfs / 20.0)).roundToInt().toShort() }

    private fun advanceMs(ms: Long) {
        now += ms * 1_000_000L
    }

    @Test
    fun `nothing played yet is not activity`() {
        assertThat(activity.isActive()).isFalse()
    }

    /** The mix plays silence while nobody talks; that must not count. */
    @Test
    fun `silence and frames below the threshold keep the far end inactive`() {
        activity.onFarEndFrame(ShortArray(FRAME))
        activity.onFarEndFrame(frameAt(FarEndActivity.THRESHOLD_DBFS - 1f))

        assertThat(activity.isActive()).isFalse()
    }

    @Test
    fun `a frame at the threshold makes the far end active`() {
        activity.onFarEndFrame(frameAt(FarEndActivity.THRESHOLD_DBFS + 0.1f))

        assertThat(activity.isActive()).isTrue()
    }

    /** The echo arrives about 300 ms after the frame is handed over, and the room rings on. */
    @Test
    fun `activity holds for the hold time after the last loud frame, then ends`() {
        activity.onFarEndFrame(frameAt(-20f))
        advanceMs(FarEndActivity.HOLD_MS - 10)
        activity.onFarEndFrame(ShortArray(FRAME))

        assertThat(activity.isActive()).isTrue()

        advanceMs(10)
        assertThat(activity.isActive()).isFalse()
    }

    @Test
    fun `every loud frame starts the hold again`() {
        activity.onFarEndFrame(frameAt(-20f))
        advanceMs(600)
        activity.onFarEndFrame(frameAt(-20f))
        advanceMs(600)

        assertThat(activity.isActive()).isTrue()
    }

    /** Speech is not constant: the frame's mean square decides, not its peak. */
    @Test
    fun `the frame's mean power decides, not its peak`() {
        // One sample peaking at -30 dBFS is -57 dBFS as the frame's mean power.
        val click = ShortArray(FRAME).also { it[0] = 1000 }
        activity.onFarEndFrame(click)
        assertThat(activity.isActive()).isFalse()

        val amplitude = FULL_SCALE * 10.0.pow(-30.0 / 20.0) * sqrt(2.0)
        activity.onFarEndFrame(ShortArray(FRAME) { (amplitude * kotlin.math.sin(it * 0.3)).roundToInt().toShort() })
        assertThat(activity.isActive()).isTrue()
    }

    @Test
    fun `an empty frame is ignored`() {
        activity.onFarEndFrame(ShortArray(0))

        assertThat(activity.isActive()).isFalse()
    }

    private companion object {
        const val FRAME = 480
        const val FULL_SCALE = 32768.0
    }
}
