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

/**
 * The two things the user asked for, tested where they are decided: a gate that follows him around
 * the room, and one that a single keyboard click cannot open.
 *
 * Frames here are built to a level rather than to an amplitude, because every threshold in
 * [VadMode.ADAPTIVE] is in dBFS and a test written in sample values would not be readable against
 * the numbers in [AdaptiveVadTracker]'s KDoc.
 */
class AdaptiveVoiceGateTest {
    private var nowNanos = 0L
    private fun detector(config: VadConfig) = VoiceActivityDetector(config) { nowNanos }

    /** A constant frame whose RMS is exactly [dbfs] relative to full scale. */
    private fun frameAt(dbfs: Float, length: Int = 480): ShortArray {
        val amplitude = (32768.0 * 10.0.pow(dbfs / 20.0)).roundToInt().coerceIn(0, 32767)
        return ShortArray(length) { amplitude.toShort() }
    }

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
     * Speech-shaped input: 700 ms of talking, then 300 ms of room, repeated.
     *
     * A constant tone is **not** a fixture for speech, and using one is how a test proves the
     * opposite of what it meant to. Fed a level that never stops, the floor estimator converges on
     * it -- correctly, because something that never stops is by definition noise -- and the gate
     * closes. The pauses are what let the floor fall back at 24 dB/s between words, and they are
     * the reason the asymmetric rates work at all.
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

    /**
     * The legacy score and the level are two readings of the same energy, and the settings screen
     * shows both, so a drift between them would be two numbers for one quantity.
     */
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
     * The reported symptom, reproduced as a fixture: continuous typing is suppressed but the first
     * impulse after a pause is not. A click is one frame; speech is not.
     */
    @Test
    fun `a single loud frame does not open the gate and two consecutive ones do`() {
        val config = VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 2)

        val click = detector(config)
        click.run(-60f, 20)
        assertThat(click.run(-20f, 1)).containsExactly(false)

        val speech = detector(config)
        speech.run(-60f, 20)
        assertThat(speech.run(-20f, 2)).containsExactly(false, true).inOrder()
    }

    @Test
    fun `one frame of onset is the legacy behaviour and lets the click through`() {
        val click = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1))
        click.run(-60f, 20)
        assertThat(click.run(-20f, 1)).containsExactly(true)
    }

    /**
     * The price, in frames rather than in feeling. A frame is 10 ms at the 48 kHz the pipeline
     * resamples to, so `onsetFrames` costs `(onsetFrames - 1) * 10 ms` of the start of a word.
     */
    @Test
    fun `the onset requirement costs exactly one frame of speech per frame demanded`() {
        for (onset in 1..4) {
            val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = onset))
            d.run(-60f, 20)
            val opened = d.run(-20f, 8).indexOf(true)
            assertThat(opened).isEqualTo(onset - 1)
        }
    }

    /**
     * A gap inside a word must not pay the onset cost again; that is what the hold is for.
     *
     * **The hold is also what made the first version of this test toothless, and a mutation said
     * so.** With a 100 ms hold and three demanded frames, dropping the `talking ||` arm changed
     * nothing any assertion could see: the gate stayed open through the gap on the hold alone, and
     * by the time the hold could have expired the onset counter had caught up again. The two
     * versions only differ where the hold runs out **before** `onsetFrames` consecutive frames have
     * accumulated -- 20 ms of hold against four demanded frames. Same shape as spec 4.05's
     * shadowed assertion, one level up: the fixture, not the assertion, was doing the covering.
     */
    @Test
    fun `the onset is only demanded while the gate is shut`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 20, onsetFrames = 4))
        d.run(-60f, 20)
        assertThat(d.run(-20f, 4).last()).isTrue()
        // One quiet frame, carried by the hold, then one loud one: open again immediately, on the
        // first frame rather than on the fourth, and past the point where the hold has expired.
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

    /**
     * The case the user described: he walks away, loses about 6 dB per doubling of distance, and
     * the gate has to come with him. Without the tracker the threshold stays at -32 dBFS and a
     * talker at -37 is gone for good.
     */
    @Test
    fun `a talker who walks away keeps the gate open`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 250, onsetFrames = 2))
        // Three seconds at arm's length in a -45 dBFS room, then he walks about two doublings
        // away (-15 dB). `dropLast(30)` lands the reading in the talking part of the last second
        // rather than in its pause.
        assertThat(d.speak(-22f, -45f, 3).dropLast(30).last()).isTrue()

        val far = d.speak(-37f, -45f, 15)
        assertThat(far.takeLast(100).any { it }).isTrue()
        assertThat(d.thresholdDbfs).isLessThan(-37f)
    }

    /**
     * The same fixture against a fixed threshold, to show what the tracking is worth rather than
     * asserting that it helps. The legacy amplitude gate is calibrated on the close half and the
     * far half never reaches it again.
     *
     * **The pause has to be longer than the hold, and that is a finding rather than a fixture
     * detail.** With the 200 ms pause this fixture first used, the legacy gate never closed at all:
     * its hysteresis is [VadConfig.AMPLITUDE_HYSTERESIS] = 0.15 of the score, i.e. **14.4 dB**, so
     * a talker four doublings away still sits over the stop threshold, and the 250 ms hold bridges
     * every pause shorter than itself. So the legacy failure at distance is not only "he stops
     * being heard" -- it is also "the gate latches open and the room goes out with him", depending
     * on which side of 14.4 dB the distance puts him. The 6 dB in [VadConfig.hysteresisDb] is the
     * other half of this task's answer to that.
     */
    @Test
    fun `a fixed threshold calibrated close by loses the same talker`() {
        // -22 dBFS is a score of 0.771; a slider set just under it is the honest calibration.
        val fixed = detector(VadConfig.amplitude(0.76f, holdTimeMs = 250, onsetFrames = 2))
        assertThat(fixed.speak(-22f, -45f, 3).dropLast(30).last()).isTrue()
        // The first second of the far half is dropped: the 250 ms hold from the close half still
        // reaches into it, and counting that as "he was heard" would be the shadowing 4.05 warns of.
        assertThat(fixed.speak(-37f, -45f, 15).drop(100).any { it }).isFalse()
    }

    /** And the honest half: the room does not follow him, so the demand cannot fall forever. */
    @Test
    fun `the gate reports when it has run out of room rather than opening on anything`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 2))
        // A room only 8 dB under the talker is below MIN_USABLE_GAP_DB, and no loop invents the
        // signal-to-noise that is missing. The gate says so instead of opening on the room.
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
        // A thousand frames of room noise 30 dB over the hand-set floor move it by nothing. The
        // gap is still tracked -- pinning the floor pins the floor, not the threshold.
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

    /**
     * **Both estimates have to have moved before the reset, and the first version of this test
     * moved neither.** It drove the detector with a loud constant level, which transmits on every
     * frame -- so the floor was never learned and stayed at its fresh value, and the speech peak
     * jumped straight back to that level on the frame after the reset. Emptying `recalibrate()`
     * left it green. A quiet run moves the floor; a loud burst moves the peak; then the reset has
     * something to undo.
     */
    @Test
    fun `recalibrating puts the estimates back to the fresh state`() {
        val d = detector(VadConfig.adaptive(holdTimeMs = 0, onsetFrames = 1))
        d.run(-70f, 200)
        d.run(-10f, 50)
        assertThat(d.floorDbfs).isLessThan(AdaptiveVadTracker.DEFAULT_FLOOR_DBFS - 5f)
        assertThat(d.speechDbfs).isWithin(0.1f).of(-10f)

        d.recalibrate()
        // The reset belongs to the capture thread, so it lands on the next frame, not on the call.
        assertThat(d.floorDbfs).isLessThan(AdaptiveVadTracker.DEFAULT_FLOOR_DBFS - 5f)
        d.run(-70f, 1)
        assertThat(d.floorDbfs).isWithin(0.3f).of(AdaptiveVadTracker.DEFAULT_FLOOR_DBFS)
        assertThat(d.speechDbfs - d.floorDbfs).isWithin(0.3f).of(AdaptiveVadTracker.DEFAULT_GAP_DB)
    }

    /**
     * H1-16 and H1-17 of the sweep: switching from the tracked floor to a hand-set one **while the
     * detector is running** is the case the manual-floor test could not reach, because it built the
     * detector with the manual floor already set and then fed it levels loud enough to transmit --
     * so neither `setFloor` nor `learnFloor` had anything to do.
     */
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

    /** The meter draws these; a mode with no tracker has to answer rather than throw. */
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
