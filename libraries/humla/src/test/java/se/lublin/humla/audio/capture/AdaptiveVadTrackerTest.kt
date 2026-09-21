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

/**
 * The tracker is a pure state machine over dBFS readings, so every time constant claimed in its
 * KDoc is checkable here by feeding it frames and counting them. A number in a comment that no
 * test divides by is a wish; these are the divisions.
 */
class AdaptiveVadTrackerTest {
    private fun tracker(floor: Float = -45f) = AdaptiveVadTracker(initialFloorDbfs = floor)

    /** Feeds [frames] frames of 10 ms and returns the tracker. */
    private fun AdaptiveVadTracker.feed(level: Float, transmitting: Boolean, frames: Int) = apply {
        repeat(frames) { update(level, transmitting, 10f) }
    }

    @Test
    fun `a fresh tracker demands exactly the window spec 4_1 adopted`() {
        // speech is seeded at floor + 20 dB and the default fraction is 0.65, so 0.65 * 20 = 13.0 dB,
        // which is the demand the fixed -45/-23.3 window makes at a start threshold of 0.6.
        val t = tracker(-45f)
        assertThat(t.gapDb).isWithin(0.001f).of(AdaptiveVadTracker.DEFAULT_GAP_DB)
        assertThat(t.marginDb(AdaptiveVadTracker.DEFAULT_FRACTION)).isWithin(0.001f).of(13.0f)
        assertThat(t.thresholdDbfs(AdaptiveVadTracker.DEFAULT_FRACTION)).isWithin(0.001f).of(-32.0f)
    }

    @Test
    fun `the floor is only learned while nothing is transmitted`() {
        val quiet = tracker(-45f).feed(level = -20f, transmitting = true, frames = 500)
        assertThat(quiet.floorDbfs).isEqualTo(-45f)

        val learned = tracker(-45f).feed(level = -20f, transmitting = false, frames = 500)
        assertThat(learned.floorDbfs).isGreaterThan(-45f)
    }

    @Test
    fun `the floor rises at 3 dB per second and falls at 24`() {
        // 100 frames of 10 ms is exactly one second, so the reading is the rate itself.
        val up = tracker(-60f).feed(level = -10f, transmitting = false, frames = 100)
        assertThat(up.floorDbfs).isWithin(0.01f).of(-60f + AdaptiveVadTracker.FLOOR_RISE_DB_PER_SECOND)
        assertThat(AdaptiveVadTracker.FLOOR_RISE_DB_PER_SECOND).isEqualTo(3f)

        val down = tracker(-40f).feed(level = -90f, transmitting = false, frames = 100)
        assertThat(down.floorDbfs).isWithin(0.01f).of(-40f - AdaptiveVadTracker.FLOOR_FALL_DB_PER_SECOND)
        assertThat(AdaptiveVadTracker.FLOOR_FALL_DB_PER_SECOND).isEqualTo(24f)
    }

    @Test
    fun `the floor never overshoots the level it is chasing`() {
        val t = tracker(-45f).feed(level = -44f, transmitting = false, frames = 500)
        assertThat(t.floorDbfs).isWithin(0.001f).of(-44f)
    }

    @Test
    fun `one speech frame slipped past the onset guard moves the floor by 0_03 dB`() {
        val t = tracker(-45f)
        t.update(-10f, transmitting = false, frameMs = 10f)
        assertThat(t.floorDbfs).isWithin(0.0005f).of(-45f + 0.03f)
    }

    @Test
    fun `the speech estimate only rises while transmitting, and instantly`() {
        val silent = tracker(-45f).feed(level = -12f, transmitting = false, frames = 1)
        assertThat(silent.speechDbfs).isLessThan(-12f)

        val talking = tracker(-45f).feed(level = -12f, transmitting = true, frames = 1)
        assertThat(talking.speechDbfs).isEqualTo(-12f)
    }

    @Test
    fun `the speech estimate follows a talker walking away at 6 dB per second`() {
        // One doubling of distance costs 6 dB, so one second is the time to follow one step away.
        val t = tracker(-45f).feed(level = -12f, transmitting = true, frames = 1)
        t.feed(level = -80f, transmitting = true, frames = 100)
        assertThat(t.speechDbfs).isWithin(0.01f).of(-12f - AdaptiveVadTracker.SPEECH_FALL_DB_PER_SECOND)
        assertThat(AdaptiveVadTracker.SPEECH_FALL_DB_PER_SECOND).isEqualTo(6f)
    }

    @Test
    fun `a silent minute relaxes the demand instead of latching the gate shut`() {
        // Without this the loop eats itself: a loud close talker raises the threshold, he walks
        // away, the gate never opens again, and because the speech estimate is only fed while
        // transmitting it can never learn that he got quieter.
        val t = tracker(-45f).feed(level = -12f, transmitting = true, frames = 1)
        val demandWhileClose = t.marginDb(AdaptiveVadTracker.DEFAULT_FRACTION)

        t.feed(level = -45f, transmitting = false, frames = 100)
        assertThat(t.speechDbfs).isWithin(0.01f).of(-12f - AdaptiveVadTracker.SPEECH_RELAX_DB_PER_SECOND)
        assertThat(t.marginDb(AdaptiveVadTracker.DEFAULT_FRACTION)).isLessThan(demandWhileClose)
        assertThat(AdaptiveVadTracker.SPEECH_RELAX_DB_PER_SECOND).isEqualTo(2f)
    }

    @Test
    fun `the relaxation stops at the smallest gap the design can still work in`() {
        val t = tracker(-45f).feed(level = -45f, transmitting = false, frames = 10_000)
        assertThat(t.gapDb).isWithin(0.001f).of(AdaptiveVadTracker.MIN_USABLE_GAP_DB)
        assertThat(t.marginDb(AdaptiveVadTracker.DEFAULT_FRACTION)).isWithin(0.001f).of(6.5f)
    }

    @Test
    fun `the margin is clamped at both ends`() {
        // Shouting into the microphone from 10 cm must not raise the demand past what the chain's
        // own curve calls full-scale speech, which is the 21.7 dB width of the adopted window.
        val loud = tracker(-90f).feed(level = -1f, transmitting = true, frames = 1)
        assertThat(loud.gapDb).isGreaterThan(AdaptiveVadTracker.MAX_MARGIN_DB)
        assertThat(loud.marginDb(1f)).isWithin(0.001f).of(AdaptiveVadTracker.MAX_MARGIN_DB)

        // And a fraction of nearly nothing must not drop the demand into the floor's own spread.
        val t = tracker(-45f)
        assertThat(t.marginDb(0f)).isWithin(0.001f).of(AdaptiveVadTracker.MIN_MARGIN_DB)
    }

    @Test
    fun `the gap the design stops helping in is reported rather than hidden`() {
        // -37 dBFS against a -45 floor is a gap of 8 dB, below what the design can work in, so
        // the peak is held at the clamp and the tracker says so instead of pretending. 200 frames
        // is 2 s, more than the 1.67 s the peak needs to fall the 10 dB from its seed at 6 dB/s.
        val far = tracker(-45f).feed(level = -37f, transmitting = true, frames = 200)
        assertThat(far.speechDbfs).isWithin(0.001f).of(-35f)
        assertThat(far.gapDb).isWithin(0.001f).of(AdaptiveVadTracker.MIN_USABLE_GAP_DB)
        assertThat(far.tooClose).isTrue()

        val near = tracker(-45f).feed(level = -20f, transmitting = true, frames = 1)
        assertThat(near.tooClose).isFalse()
    }

    @Test
    fun `the floor is clamped to a range a microphone can produce`() {
        val low = tracker(-89f).feed(level = -140f, transmitting = false, frames = 10_000)
        assertThat(low.floorDbfs).isEqualTo(AdaptiveVadTracker.MIN_FLOOR_DBFS)

        val high = tracker(-25f).feed(level = 0f, transmitting = false, frames = 10_000)
        assertThat(high.floorDbfs).isEqualTo(AdaptiveVadTracker.MAX_FLOOR_DBFS)
    }

    @Test
    fun `a hand-set floor moves without forgetting the talker`() {
        val t = tracker(-45f).feed(level = -12f, transmitting = true, frames = 1)
        t.setFloor(-60f)
        assertThat(t.floorDbfs).isEqualTo(-60f)
        assertThat(t.speechDbfs).isEqualTo(-12f)
        assertThat(t.gapDb).isWithin(0.001f).of(48f)
    }

    @Test
    fun `a hand-set floor that lands above the talker still leaves the smallest usable gap`() {
        val t = tracker(-45f).feed(level = -40f, transmitting = true, frames = 500)
        t.setFloor(-30f)
        assertThat(t.speechDbfs).isWithin(0.001f).of(-20f)
    }

    @Test
    fun `a pinned floor is not learned but the talker is still followed`() {
        val t = tracker(-45f)
        repeat(500) { t.update(-20f, transmitting = false, frameMs = 10f, learnFloor = false) }
        assertThat(t.floorDbfs).isEqualTo(-45f)
        assertThat(t.gapDb).isWithin(0.001f).of(AdaptiveVadTracker.MIN_USABLE_GAP_DB)
    }

    @Test
    fun `reset puts both estimates back where a fresh tracker has them`() {
        val t = tracker(-45f).feed(level = -5f, transmitting = true, frames = 50)
        t.reset(-52f)
        assertThat(t.floorDbfs).isEqualTo(-52f)
        assertThat(t.gapDb).isWithin(0.001f).of(AdaptiveVadTracker.DEFAULT_GAP_DB)
    }

    @Test
    fun `the frame length decides how much one update moves an estimate`() {
        val short = tracker(-60f).feed(level = -10f, transmitting = false, frames = 1)
        val long = tracker(-60f).apply { update(-10f, transmitting = false, frameMs = 20f) }
        assertThat(long.floorDbfs - (-60f)).isWithin(0.0005f).of(2 * (short.floorDbfs - (-60f)))
    }

    @Test
    fun `a speech peak never sits below the floor plus the smallest usable gap`() {
        val t = tracker(-45f).feed(level = -90f, transmitting = true, frames = 10_000)
        assertThat(t.gapDb).isWithin(0.001f).of(AdaptiveVadTracker.MIN_USABLE_GAP_DB)
    }
}
