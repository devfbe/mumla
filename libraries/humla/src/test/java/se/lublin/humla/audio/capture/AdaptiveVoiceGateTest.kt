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
import se.lublin.humla.audio.frameAt

/**
 * The adaptive gate: it follows a talker around the room, and a single keyboard click cannot open
 * it. Frames are built to a level in dBFS, the unit of every threshold in [VadMode.ADAPTIVE].
 */
class AdaptiveVoiceGateTest {
    private var nowNanos = 0L
    private fun detector(config: VadConfig) = VoiceActivityDetector(config) { nowNanos }

    private fun advanceOneFrame() {
        nowNanos += 10_000_000L
    }

    private fun VoiceActivityDetector.run(dbfs: Float, frames: Int): List<Boolean> =
        (0 until frames).map {
            val voice = isVoice(frameAt(dbfs), 480, null)
            advanceOneFrame()
            voice
        }

    /**
     * 700 ms of talking, then 300 ms of room, repeated. A constant tone would be learned as noise
     * and close the gate; the pauses let the floor fall back between words.
     */
    private fun VoiceActivityDetector.speak(
        speechDbfs: Float,
        roomDbfs: Float,
        seconds: Int,
    ): List<Boolean> = (0 until seconds).flatMap { run(speechDbfs, 70) + run(roomDbfs, 30) }

    // --- levelDbfs, the primitive the whole mode is written in -------------------------------

    @Test
    fun `levelDbfs measures the frame against full scale`() {
        assertThat(VoiceActivityDetector.levelDbfs(frameAt(-20f), 480)).isWithin(0.05f).of(-20f)
        assertThat(VoiceActivityDetector.levelDbfs(frameAt(-45f), 480)).isWithin(0.05f).of(-45f)
        assertThat(VoiceActivityDetector.levelDbfs(frameAt(-3f), 480)).isWithin(0.05f).of(-3f)
    }

    @Test
    fun `an empty frame has no level rather than an infinite one`() {
        assertThat(VoiceActivityDetector.levelDbfs(ShortArray(480) { 32767 }, 0))
            .isEqualTo(VoiceActivityDetector.NO_SIGNAL_DBFS)
        assertThat(VoiceActivityDetector.NO_SIGNAL_DBFS).isFinite()
        assertThat(VoiceActivityDetector.NO_SIGNAL_DBFS).isLessThan(AdaptiveVadTracker.MIN_FLOOR_DBFS)
    }

    /** The settings screen shows both, so they must agree. */
    @Test
    fun `the legacy score is the level on the legacy 96 dB scale`() {
        for (dbfs in listOf(-10f, -30f, -60f, -90f)) {
            val frame = frameAt(dbfs)
            val fromLevel = 1f + VoiceActivityDetector.levelDbfs(frame, 480) / 96f
            assertThat(VoiceActivityDetector.amplitudeScore(frame, 480)).isWithin(0.001f).of(fromLevel)
        }
    }

    // --- the onset requirement, which is the keyboard-click fix ------------------------------

    /**
     * A click is one frame; speech is not. With two frames of onset a single loud frame stays shut
     * and two open the gate; one frame is the legacy behaviour and lets the click through. A frame
     * is 10 ms, so `onsetFrames` costs `(onsetFrames - 1) * 10 ms` at the start of a word.
     */
    @Test
    fun `the onset requirement costs exactly one frame of speech per frame demanded`() {
        for (onset in 1..4) {
            val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = onset))
            d.run(-60f, 20)
            val opened = d.run(-20f, 8).indexOf(true)
            assertWithMessage("onset $onset").that(opened).isEqualTo(onset - 1)
        }
    }

    /**
     * A gap inside a word must not pay the onset cost again. The hold (20 ms) must run out before
     * four onset frames could accumulate, or the hold alone would keep the gate open.
     */
    @Test
    fun `the onset is only demanded while the gate is shut`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 20, onsetFrames = 4))
        d.run(-60f, 20)
        assertThat(d.run(-20f, 4).last()).isTrue()
        // One quiet frame carried by the hold, then loud: open again immediately, past the hold.
        assertThat(d.run(-60f, 1)).containsExactly(true)
        assertThat(d.run(-20f, 1)).containsExactly(true)
        assertThat(d.run(-20f, 1)).containsExactly(true)
    }

    @Test
    fun `the onset counter is cleared by a frame below the threshold`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 3))
        d.run(-60f, 20)
        assertThat(d.run(-20f, 2)).containsExactly(false, false)
        assertThat(d.run(-60f, 1)).containsExactly(false)
        assertThat(d.run(-20f, 2)).containsExactly(false, false)
    }

    @Test
    fun `the onset requirement applies to the legacy modes too`() {
        val amplitude = detector(VadConfig.amplitude(0.6f, holdTimeMs = 0, onsetFrames = 2))
        assertThat(amplitude.isVoice(frameAt(-20f), 480, null)).isFalse()
        assertThat(amplitude.isVoice(frameAt(-20f), 480, null)).isTrue()

        val probability = detector(VadConfig.probability(holdTimeMs = 0, onsetFrames = 2))
        assertThat(probability.isVoice(frameAt(-60f), 480, 0.9f)).isFalse()
        assertThat(probability.isVoice(frameAt(-60f), 480, 0.9f)).isTrue()
    }

    // --- the gate itself ---------------------------------------------------------------------

    @Test
    fun `a fresh adaptive gate demands 13 dB over the floor, as the fixed window did`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1))
        assertThat(d.thresholdDbfs).isWithin(0.01f).of(-32f)

        // -31 dBFS is over it, -33 is under it.
        assertThat(d.run(-31f, 1)).containsExactly(true)
        assertThat(detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1)).run(-33f, 1))
            .containsExactly(false)
    }

    /** Walking away loses about 6 dB per doubling of distance; the gate has to follow. */
    @Test
    fun `a talker who walks away keeps the gate open`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 250, onsetFrames = 2))
        // Three seconds close by in a -45 dBFS room, then about two doublings away (-15 dB).
        // `dropLast(30)` reads the talking part of the last second rather than its pause.
        assertThat(d.speak(-22f, -45f, 3).dropLast(30).last()).isTrue()

        val far = d.speak(-37f, -45f, 15)
        assertThat(far.takeLast(100).any { it }).isTrue()
        assertThat(d.thresholdDbfs).isLessThan(-37f)
    }

    /**
     * The same fixture against the legacy fixed threshold, for comparison. The pause is longer than
     * the hold, because the legacy 14.4 dB hysteresis plus hold would otherwise latch the gate open.
     */
    @Test
    fun `a fixed threshold calibrated close by loses the same talker`() {
        // -22 dBFS is a score of 0.771; the slider is set just under it.
        val fixed = detector(VadConfig.amplitude(0.76f, holdTimeMs = 250, onsetFrames = 2))
        assertThat(fixed.speak(-22f, -45f, 3).dropLast(30).last()).isTrue()
        // Skip the first second: the hold from the close half still reaches into it.
        assertThat(fixed.speak(-37f, -45f, 15).drop(100).any { it }).isFalse()
    }

    /** The room does not follow the talker, so the demand cannot fall forever. */
    @Test
    fun `the gate reports when it has run out of room rather than opening on anything`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 2))
        // A room only 8 dB under the talker is below MIN_USABLE_GAP_DB; the gate reports it.
        d.speak(-37f, -45f, 30)
        assertThat(d.tooClose).isTrue()
        assertThat(d.thresholdDbfs - d.floorDbfs).isWithin(0.01f).of(6.5f)
    }

    @Test
    fun `a louder room raises the threshold with it`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 2))
        val before = d.thresholdDbfs
        d.run(-35f, 1000)
        assertThat(d.floorDbfs).isGreaterThan(-45f)
        assertThat(d.thresholdDbfs).isGreaterThan(before)
    }

    @Test
    fun `the stop threshold sits the configured number of dB below the start threshold`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1, hysteresisDb = 6f))
        assertThat(d.run(-31f, 1)).containsExactly(true)
        // -36 is below the -32 start but above the -38 stop, so it keeps the gate open.
        assertThat(d.run(-36f, 1)).containsExactly(true)
        assertThat(d.run(-39f, 1)).containsExactly(false)
    }

    @Test
    fun `a manual floor pins the threshold where the user put it`() {
        val d = detector(
            VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1, adaptiveFloor = false, manualFloorDbfs = -60f)
        )
        assertThat(d.floorDbfs).isEqualTo(-60f)
        assertThat(d.thresholdDbfs).isWithin(0.01f).of(-47f)
        // Room noise 30 dB over the hand-set floor does not move it.
        d.run(-30f, 1000)
        assertThat(d.floorDbfs).isEqualTo(-60f)
    }

    @Test
    fun `the slider is a fraction of the measured gap, not a level`() {
        val greedy = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1, snrFraction = 0.9f))
        val relaxed = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1, snrFraction = 0.3f))
        assertThat(greedy.thresholdDbfs).isWithin(0.01f).of(-45f + 18f)
        assertThat(relaxed.thresholdDbfs).isWithin(0.01f).of(-45f + 6f)
    }

    @Test
    fun `changing the config at runtime moves the threshold without losing what was learned`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1))
        d.run(-15f, 100)
        val learnedFloor = d.floorDbfs
        val learnedGap = d.thresholdDbfs - learnedFloor

        d.config = VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1, snrFraction = 0.3f)
        assertThat(d.floorDbfs).isEqualTo(learnedFloor)
        assertThat(d.thresholdDbfs - learnedFloor).isLessThan(learnedGap)
    }

    /** Both estimates are moved first (quiet run for the floor, loud burst for the peak). */
    @Test
    fun `recalibrating puts the estimates back to the fresh state`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1))
        d.run(-70f, 200)
        d.run(-10f, 50)
        assertThat(d.floorDbfs).isLessThan(AdaptiveVadTracker.DEFAULT_FLOOR_DBFS - 5f)
        assertThat(d.speechDbfs).isWithin(0.1f).of(-10f)

        d.recalibrate()
        // The reset is applied on the capture thread with the next frame, not on the call.
        assertThat(d.floorDbfs).isLessThan(AdaptiveVadTracker.DEFAULT_FLOOR_DBFS - 5f)
        d.run(-70f, 1)
        assertThat(d.floorDbfs).isWithin(0.3f).of(AdaptiveVadTracker.DEFAULT_FLOOR_DBFS)
        assertThat(d.speechDbfs - d.floorDbfs).isWithin(0.3f).of(AdaptiveVadTracker.DEFAULT_GAP_DB)
    }

    /** Switching to a hand-set floor while the detector is running. */
    @Test
    fun `switching to a hand-set floor moves the floor that was learned`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1))
        d.run(-70f, 200)
        assertThat(d.floorDbfs).isLessThan(-50f)

        d.config = VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1, adaptiveFloor = false, manualFloorDbfs = -40f)
        d.run(-70f, 1)
        assertThat(d.floorDbfs).isEqualTo(-40f)
    }

    @Test
    fun `a hand-set floor is not learned away by a quiet room`() {
        val d = detector(
            VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1, adaptiveFloor = false, manualFloorDbfs = -40f)
        )
        d.run(-80f, 500)
        assertThat(d.floorDbfs).isEqualTo(-40f)
    }

    /** The level meter reads these; modes without a tracker must still answer. */
    @Test
    fun `the legacy modes still answer for the level meter`() {
        val amplitude = detector(VadConfig.amplitude(0.6f))
        assertThat(amplitude.floorDbfs).isEqualTo(AdaptiveVadTracker.DEFAULT_FLOOR_DBFS)
        amplitude.isVoice(frameAt(-20f), 480, null)
        assertThat(amplitude.lastLevelDbfs).isWithin(0.05f).of(-20f)

        val probability = detector(VadConfig.probability())
        probability.isVoice(frameAt(-20f), 480, 0.9f)
        assertThat(probability.lastLevelDbfs).isWithin(0.05f).of(-20f)
    }
}
