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
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class VoiceActivityDetectorTest {
    private var nowNanos = 0L
    private fun ms(v: Long) = v * 1_000_000L
    private fun detector(config: VadConfig) = VoiceActivityDetector(config) { nowNanos }
    private fun constant(value: Int) = ShortArray(480) { value.toShort() }

    @Test
    fun `amplitude score is the legacy dBFS mapping`() {
        // rms 3277 -> 20*log10(3277/32768) = -20.0 dB -> 1 + (-20/96) = 0.7917
        assertThat(VoiceActivityDetector.amplitudeScore(constant(3277), 480)).isWithin(0.002f).of(0.792f)
        assertThat(VoiceActivityDetector.amplitudeScore(constant(32767), 480)).isWithin(0.001f).of(1.0f)
        // Digital silence, and the value is finite because of the `+1` the sum starts from:
        // without it the logarithm of zero would make this -Infinity, which is a level meter with
        // nothing to draw and a score no threshold can be set against.
        assertThat(VoiceActivityDetector.amplitudeScore(constant(0), 480)).isWithin(0.001f).of(-0.220f)
    }

    /**
     * The legacy formula answers `sqrt(1.0 / 0)` = infinity for a zero-sample frame, i.e. a score
     * above every threshold: `length == 0` reads as louder than anything.
     *
     * **Not as an observed defect -- the sentence this replaces said it was one.**
     * `AudioInput.loop` is the only call site on this path today and it skips a read of 0
     * before calling, handing over the whole frame size rather than the count, so the infinity is
     * shielded. From B1/B11 on nothing shields it: task 8's `CapturePipeline` passes a
     * resampler's own output length down, and `AudioHandler:430` -- which does pass a read count
     * straight through -- is task 11's to replace.
     */
    @Test
    fun `an empty frame is not voice`() {
        assertThat(VoiceActivityDetector.amplitudeScore(ShortArray(480), 0)).isEqualTo(VoiceActivityDetector.NO_SIGNAL)
        assertThat(VoiceActivityDetector.NO_SIGNAL).isLessThan(0f)
        assertThat(detector(VadConfig.amplitude(0f)).isVoice(ShortArray(480) { 32767 }, 0, null)).isFalse()
        assertThat(detector(VadConfig.probability(holdTimeMs = 0)).isVoice(ShortArray(480), 0, null)).isFalse()
    }

    /**
     * Both ends of the frame are in the sum. An off-by-one at either end of the loop bound is
     * invisible in every other fixture in this file -- over 480 constant samples, dropping one
     * moves the score by 0.0001, which no threshold assertion can see -- so it takes a frame whose
     * whole energy sits in its two end samples. Measured: **0.75206** with both, **0.72070** with
     * either one missing. `1 until length` and `0 until length - 1` both survived the suite before
     * this case existed.
     */
    @Test
    fun `the first and the last sample of the frame are both measured`() {
        val spikes = ShortArray(480).also { it[0] = 32767; it[479] = 32767 }
        assertThat(VoiceActivityDetector.amplitudeScore(spikes, 480)).isWithin(0.0005f).of(0.75206f)
    }

    /**
     * The three constants of the legacy formula, each written out, because the shape of the curve
     * is what the user's slider is calibrated against and all three are invisible in the value
     * above: 20 (amplitude rather than power dB), 96 (the divisor that maps -96 dBFS onto 0) and
     * the `+1` offset. `amplitudeScore(1) - amplitudeScore(2)` is 20/96 dB per halving.
     */
    @Test
    fun `the amplitude curve has the legacy slope and anchor`() {
        val loud = VoiceActivityDetector.amplitudeScore(constant(16384), 480)
        val half = VoiceActivityDetector.amplitudeScore(constant(8192), 480)
        // One halving of the amplitude is 6.0206 dB, i.e. 0.0627 of the score.
        assertThat(loud - half).isWithin(0.0005f).of(0.0627f)
        // -96 dBFS is the zero of the scale: rms 32768 * 10^(-96/20) = 0.5188.
        assertThat(VoiceActivityDetector.amplitudeScore(ShortArray(480) { if (it < 130) 1 else 0 }, 480))
            .isWithin(0.02f).of(0f)
    }

    @Test
    fun `amplitude mode compares the frame level against the start threshold`() {
        assertThat(detector(VadConfig.amplitude(0.7f)).isVoice(constant(3277), 480, null)).isTrue()
        assertThat(detector(VadConfig.amplitude(0.85f)).isVoice(constant(3277), 480, null)).isFalse()
    }

    /** Amplitude mode is a level detector by definition; a stage's opinion must not enter it. */
    @Test
    fun `amplitude mode ignores the preprocessor probability`() {
        assertThat(detector(VadConfig.amplitude(0.85f)).isVoice(constant(3277), 480, 1.0f)).isFalse()
        assertThat(detector(VadConfig.amplitude(0.7f)).isVoice(constant(3277), 480, 0.0f)).isTrue()
    }

    @Test
    fun `amplitude config derives the stop threshold 0_15 below start`() {
        val c = VadConfig.amplitude(0.5f)
        assertThat(c.mode).isEqualTo(VadMode.AMPLITUDE)
        assertThat(c.startThreshold).isEqualTo(0.5f)
        assertThat(c.stopThreshold).isWithin(0.0001f).of(0.35f)
        assertThat(c.holdTimeMs).isEqualTo(250L)
        assertThat(VadConfig.amplitude(0.1f).stopThreshold).isEqualTo(0f)
        assertThat(VadConfig.AMPLITUDE_HYSTERESIS).isEqualTo(0.15f)
    }

    /** The slider reaches this straight from a preference; out-of-range is clamped, not refused. */
    @Test
    fun `the amplitude slider is clamped into range rather than throwing`() {
        assertThat(VadConfig.amplitude(2f).startThreshold).isEqualTo(1f)
        assertThat(VadConfig.amplitude(2f).stopThreshold).isWithin(0.0001f).of(0.85f)
        assertThat(VadConfig.amplitude(-1f).startThreshold).isEqualTo(0f)
        assertThat(VadConfig.amplitude(-1f).stopThreshold).isEqualTo(0f)
    }

    @Test
    fun `probability defaults are start 0_6 stop 0_3 hold 250`() {
        assertThat(VadConfig.DEFAULT).isEqualTo(VadConfig(VadMode.PROBABILITY, 0.6f, 0.3f, 250L))
        assertThat(VadConfig.DEFAULT_HOLD_MS).isEqualTo(250L)
    }

    @Test
    fun `stop threshold above start is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { VadConfig(VadMode.PROBABILITY, 0.3f, 0.6f, 250L) }
    }

    @Test
    fun `a start threshold outside zero to one is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { VadConfig(VadMode.PROBABILITY, 1.5f, 0.3f, 250L) }
        assertThrows(IllegalArgumentException::class.java) { VadConfig(VadMode.PROBABILITY, -0.1f, -0.2f, 250L) }
    }

    /**
     * Both range checks, each mutated on its own -- and the reason this test exists at all is that
     * dropping the **lower** bound of either one survived the first sweep.
     *
     * `stopThreshold in 0f..startThreshold` already rejects every negative start, because the
     * range is empty then, so `startThreshold in 0f..1f`'s lower half never gets to be the reason
     * for a rejection: two guards over one observable, which spec §4.04 says read as one guard and
     * a lie. The observable that separates them is the **message**, which is the only thing a
     * programmer-error guard produces, and it is asserted by its first word so that
     * "stopThreshold must be within [0, startThreshold]" cannot pass for the other one.
     *
     * The other survivor was not masked, it was simply an input nobody had written: a negative
     * *stop* under a valid start constructs happily without the lower bound, and a negative stop
     * means the detector can never fall below it -- the microphone latches on for the session.
     */
    @Test
    fun `each range check names the field it rejected`() {
        assertThat(
            assertThrows(IllegalArgumentException::class.java) {
                VadConfig(VadMode.PROBABILITY, -0.1f, -0.1f, 250L)
            }
        ).hasMessageThat().startsWith("startThreshold")
        assertThat(
            assertThrows(IllegalArgumentException::class.java) {
                VadConfig(VadMode.PROBABILITY, 0.6f, -0.1f, 250L)
            }
        ).hasMessageThat().startsWith("stopThreshold")
    }

    @Test
    fun `a negative hold time is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { VadConfig(VadMode.PROBABILITY, 0.6f, 0.3f, -1L) }
    }

    /**
     * The other end of the same guard, and the same class of defect as the unbounded stop
     * threshold: [VoiceActivityDetector] turns this into nanoseconds with `holdTimeMs * 1_000_000`,
     * which overflows above [VadConfig.MAX_HOLD_MS] -- about 9.2e12 ms, 292 years. Past it the
     * product wraps negative, the deadline lands in the past and **the hold silently stops
     * holding**, which is a detector that clips the end of every word. Unreachable through today's
     * call sites, exactly as an unbounded stop threshold was; the guard is one line and the
     * ceiling is not arbitrary, it is the point where the deadline idiom
     * (`now - deadline < 0`) loses its precondition.
     */
    @Test
    fun `a hold time that would overflow the nanosecond deadline is rejected`() {
        assertThat(VadConfig.MAX_HOLD_MS).isEqualTo(Long.MAX_VALUE / 1_000_000L)
        // The largest hold that still converts is accepted.
        assertThat(VadConfig(VadMode.PROBABILITY, 0.6f, 0.3f, VadConfig.MAX_HOLD_MS).holdTimeMs)
            .isEqualTo(VadConfig.MAX_HOLD_MS)
        assertThat(
            assertThrows(IllegalArgumentException::class.java) {
                VadConfig(VadMode.PROBABILITY, 0.6f, 0.3f, VadConfig.MAX_HOLD_MS + 1L)
            }
        ).hasMessageThat().startsWith("holdTimeMs")
        assertThrows(IllegalArgumentException::class.java) {
            VadConfig(VadMode.PROBABILITY, 0.6f, 0.3f, Long.MAX_VALUE)
        }
    }

    @Test
    fun `probability mode uses the preprocessor probability, not the level`() {
        val d = detector(VadConfig.probability())
        assertThat(d.isVoice(constant(0), 480, 0.9f)).isTrue()      // silent frame, model says voice
        nowNanos = ms(1000)
        assertThat(d.isVoice(constant(32767), 480, 0.1f)).isFalse()  // loud frame, model says noise
    }

    @Test
    fun `hysteresis keeps talking while above stop and needs start to begin again`() {
        val d = detector(VadConfig.probability(start = 0.8f, stop = 0.6f, holdTimeMs = 0))
        assertThat(d.isVoice(constant(0), 480, 0.7f)).isFalse()   // below start: not talking
        assertThat(d.isVoice(constant(0), 480, 0.85f)).isTrue()   // above start: talking
        assertThat(d.isVoice(constant(0), 480, 0.7f)).isTrue()    // above stop while talking: still talking
        assertThat(d.isVoice(constant(0), 480, 0.5f)).isFalse()   // below stop: stops
        assertThat(d.isVoice(constant(0), 480, 0.7f)).isFalse()   // below start again: stays off
    }

    /** The comparison is `>=`, on both arms: a score sitting exactly on a threshold passes it. */
    @Test
    fun `a score exactly on a threshold counts as above it`() {
        val d = detector(VadConfig.probability(start = 0.8f, stop = 0.6f, holdTimeMs = 0))
        assertThat(d.isVoice(constant(0), 480, 0.8f)).isTrue()
        assertThat(d.isVoice(constant(0), 480, 0.6f)).isTrue()
        assertThat(d.isVoice(constant(0), 480, 0.5f)).isFalse()
    }

    @Test
    fun `hold time keeps talking for holdTimeMs after the last detection`() {
        val d = detector(VadConfig.probability(holdTimeMs = 250))
        nowNanos = 0
        assertThat(d.isVoice(constant(0), 480, 0.9f)).isTrue()
        nowNanos = ms(100)
        assertThat(d.isVoice(constant(0), 480, 0.0f)).isTrue()
        nowNanos = ms(249)
        assertThat(d.isVoice(constant(0), 480, 0.0f)).isTrue()
        nowNanos = ms(251)
        assertThat(d.isVoice(constant(0), 480, 0.0f)).isFalse()
    }

    /**
     * The hold is a deadline in nanoseconds, not in milliseconds. Without the conversion the
     * 250 ms default would last 250 ns, i.e. the hold would be gone and every consonant gap
     * would clip the transmission -- which is the complaint the legacy SPEECH_DELAY was added
     * for. The frame above at `ms(100)` is what distinguishes the two.
     */
    @Test
    fun `the hold is measured in nanoseconds`() {
        val d = detector(VadConfig.probability(holdTimeMs = 250))
        assertThat(d.isVoice(constant(0), 480, 0.9f)).isTrue()
        nowNanos = 300L  // 300 ns: past 250 ms only if the unit conversion is missing
        assertThat(d.isVoice(constant(0), 480, 0.0f)).isTrue()
    }

    /**
     * `System.nanoTime()` documents its origin as arbitrary -- "some fixed but arbitrary origin
     * time" -- so a value near `Long.MAX_VALUE` is inside the contract, not a curiosity.
     *
     * Measured with a `lastVoiceNanos = Long.MIN_VALUE / 2` sentinel and a plain
     * `now - lastVoiceNanos < holdNanos`: the subtraction overflows to about -4.6e18, which is
     * below any hold, so the very first frame reports voice with a silent buffer and the
     * microphone is open with nothing spoken. The production form holds a *deadline* and
     * compares `now - deadline < 0`, which is correct across a wrap.
     */
    @Test
    fun `a clock near the end of its range does not latch the detector on`() {
        nowNanos = Long.MAX_VALUE - ms(10)
        val d = detector(VadConfig.probability(holdTimeMs = 250))
        assertThat(d.isVoice(constant(0), 480, 0.0f)).isFalse()
        nowNanos += ms(20)  // wraps past Long.MAX_VALUE
        assertThat(d.isVoice(constant(0), 480, 0.0f)).isFalse()
    }

    /** And the hold still works across the same wrap, which is the other half of the idiom. */
    @Test
    fun `the hold survives a clock wrap`() {
        nowNanos = Long.MAX_VALUE - ms(10)
        val d = detector(VadConfig.probability(holdTimeMs = 250))
        assertThat(d.isVoice(constant(0), 480, 0.9f)).isTrue()
        nowNanos += ms(100)  // wrapped; 100 ms after the detection
        assertThat(d.isVoice(constant(0), 480, 0.0f)).isTrue()
        nowNanos += ms(200)  // 300 ms after the detection
        assertThat(d.isVoice(constant(0), 480, 0.0f)).isFalse()
    }

    @Test
    fun `probability mode falls back to the level when no stage gives a probability`() {
        val d = detector(VadConfig.probability(start = 0.7f, stop = 0.5f))
        assertThat(d.isVoice(constant(3277), 480, null)).isTrue()   // 0.792 >= 0.7
        assertThat(detector(VadConfig.probability(start = 0.85f, stop = 0.5f)).isVoice(constant(3277), 480, null)).isFalse()
    }

    /**
     * The fallback is the level, not zero. A chain with every stage released, or with noise
     * suppression set to NONE and echo cancellation to NONE, hands over `null` on every frame;
     * clamping that to "certainly not speech" is the frame path muting the user with no log line
     * and no error. Same ruling as `RnnoisePreprocessor`'s refusal sentinel.
     */
    @Test
    fun `a chain with no opinion at all still transmits a loud frame`() {
        val d = detector(VadConfig.probability(start = 0.6f, stop = 0.3f, holdTimeMs = 0))
        assertThat(d.isVoice(constant(32767), 480, null)).isTrue()
    }

    @Test
    fun `config can be replaced at runtime`() {
        val d = detector(VadConfig.probability(start = 0.95f, stop = 0.9f, holdTimeMs = 0))
        assertThat(d.isVoice(constant(0), 480, 0.8f)).isFalse()
        d.config = VadConfig.probability(start = 0.5f, stop = 0.3f, holdTimeMs = 0)
        assertThat(d.isVoice(constant(0), 480, 0.8f)).isTrue()
    }

    /**
     * The default is AMPLITUDE, and that is a migration decision rather than a preference about
     * which detector is better -- the plan said PROBABILITY.
     *
     * There is no on-disk value for this setting yet. Every installation is running the amplitude
     * detector today, driven by the `detection_threshold` slider that reaches
     * `ActivityInputMode.setThreshold` from `HumlaService:547`; and `setThreshold` is a no-op in
     * probability mode, on purpose, because a slider calibrated in level units means nothing on a
     * model's probability. A fallback of PROBABILITY therefore turns that slider dead for every
     * user who has never seen the new key -- a switch that does not do what it says, which is the
     * failure class this project exists to remove. Same reasoning, and the same shape, as
     * `NoiseSuppressionMode`'s fallback to SPEEX.
     */
    @Test
    fun `vad mode preference parsing defaults to the amplitude detector users have today`() {
        assertThat(VadMode.fromPreferenceValue("amplitude")).isEqualTo(VadMode.AMPLITUDE)
        assertThat(VadMode.fromPreferenceValue("probability")).isEqualTo(VadMode.PROBABILITY)
        assertThat(VadMode.fromPreferenceValue("nonsense")).isEqualTo(VadMode.AMPLITUDE)
        assertThat(VadMode.fromPreferenceValue(null)).isEqualTo(VadMode.AMPLITUDE)
    }

    /**
     * The on-disk spelling, pinned in the writing direction, and the set pinned with it.
     * `fromPreferenceValue` alone cannot do it: `firstOrNull { it.preferenceValue == value }`
     * falls back to the same constant a misspelt entry would have matched, so `AMPLITUDE("amp")`
     * survives every read test including a round trip. The direction with no read test is the one
     * that reaches the disk, in task 12's ListPreference. Copied in shape from `ModesTest`.
     */
    @Test
    fun `every vad mode keeps its on-disk value`() {
        assertThat(VadMode.entries.associate { it.name to it.preferenceValue })
            .containsExactly("AMPLITUDE", "amplitude", "PROBABILITY", "probability")
        assertThat(VadMode.entries.map { it.preferenceValue }).containsNoDuplicates()
        for (mode in VadMode.entries) {
            assertThat(VadMode.fromPreferenceValue(mode.preferenceValue)).isEqualTo(mode)
        }
    }

    /**
     * What the probability defaults mean as levels, for the one chain where the number they are
     * compared against is not a speech model's (spec §4.1: noise suppression NONE with echo
     * cancellation WEBRTC, where `LevelToProbability` is the only opinion).
     *
     * This is the pin for M-7, and for the window spec §4.1 adopted after it. The measurement
     * behind the numbers was taken on the host against the real APM (`tests/CMakeLists.txt`'s
     * `humla_apm`, 800 frames per point, read after 5 s of settling), and reproduced independently.
     *
     * **The floor is a band, and the axis it is flat against is the input level inside one noise
     * character -- not "whatever the input".** With the APM's own noise suppressor off, AGC2's
     * `max_output_noise_level_dbfs` cap binds and a **non-speech** frame stops tracking its input:
     * over input levels from -70 to -45 dBFS the processed level moves by well under a dB inside
     * one character. Across characters it does move. Two runs, three characters: **-43.55 dBFS
     * (hum) to -47.80 (white), median about -45**. With the suppressor on it tracked the input
     * instead (-61.9 / -56.6 / -51.1 / -45.6 for inputs of -59 / -54 / -49 / -44).
     * -45 is that **measured median with spread**. It is not derived from webrtc's constant --
     * that constant is -50, and the fact that the measurement lands 5 dB off it is the reason to
     * trust the measurement and not the constant.
     *
     * Three consequences, and they are what these assertions hold:
     *
     * - The default stop threshold has to stay **reachable**. Under the old -50/-20 window
     *   `fromDbfs` could not return less than 0.167 at the median floor (0.215 at its loud edge),
     *   so a stop threshold below that could never be crossed and the detector would never
     *   release. That was a live defect at the *bottom* of the window and it is fixed by moving
     *   `SILENCE_DBFS` onto the measured floor.
     * - **"0.6" is not a loudness. It is a demand for 13.0 dB of signal above the floor** -- and
     *   naming that axis is the whole point of this test. `fromDbfs` is a ratio over the window
     *   *width*, so raising one edge rescales the whole curve: leaving `FULL_DBFS` at -20 would
     *   have *tightened* the start to 15.0 dB rather than preserving it. -23.3 is the value that
     *   leaves the start contract exactly where the shipping window put it.
     * - The stop necessarily moves, from 4.0 dB of reserve to 6.5. With one edge pinned by the
     *   floor there is one free parameter and two contracts, and the start is the one that decides
     *   whether a person is heard at all.
     *
     * Still open, and it is not this test's to close: the NS-off decision cost speech about 4.9 dB
     * of effective SNR (probability 0.61 -> 0.44 on the same synthetic input), so holding the
     * nominal start contract leaves a real talker slightly worse off. The top of the window cannot
     * be calibrated without a real voice -- QA with hardware, spec §4.1, owner B task 13.
     */
    @Test
    fun `the probability defaults sit at these dBFS levels on the apm window`() {
        fun levelFor(p: Float) =
            LevelToProbability.SILENCE_DBFS + p * (LevelToProbability.FULL_DBFS - LevelToProbability.SILENCE_DBFS)

        assertThat(levelFor(VadConfig.DEFAULT.startThreshold)).isWithin(0.05f).of(-32.0f)
        assertThat(levelFor(VadConfig.DEFAULT.stopThreshold)).isWithin(0.05f).of(-38.5f)

        // The same two numbers said as what they actually are: SNR above the measured floor. This
        // is the contract, and the level above is only its reading on today's floor.
        val medianNoiseFloorDbfs = -45.0f
        assertThat(levelFor(VadConfig.DEFAULT.startThreshold) - medianNoiseFloorDbfs).isWithin(0.05f).of(13.0f)
        assertThat(levelFor(VadConfig.DEFAULT.stopThreshold) - medianNoiseFloorDbfs).isWithin(0.05f).of(6.5f)

        // The floor reads as silence again, and the *loud edge* of the measured band -- the hum
        // character, the worst case of the two runs -- still reads well below the default stop.
        // 0.167 was neither worst case: it was the median floor on the old window.
        assertThat(LevelToProbability.fromDbfs(medianNoiseFloorDbfs)).isEqualTo(0f)
        assertThat(LevelToProbability.fromDbfs(-43.55f)).isWithin(0.001f).of(0.0668f)
        assertThat(LevelToProbability.fromDbfs(-43.55f)).isLessThan(VadConfig.DEFAULT.stopThreshold)
    }

    /**
     * A sweep over the input space rather than a list of cases, because the three arms of the
     * decision -- start, stop and hold -- only interact over a *sequence*, and a pinned sibling
     * lends the other arms an air of coverage (spec §4.04, "inside a function, sweep by arm").
     * 200 000 frames of pseudo-random scores against an independently written state machine.
     *
     * The comparison is an index loop that reports the **first** diverging frame, not
     * `assertThat(actual).isEqualTo(expected)` over two lists: spec §4.05 measured the naive form
     * at 273 s and a 43 MB failure message for a single differing sample, against 0.1 s here.
     *
     * What it is not: it is not an independent derivation of the rule, it is the same rule in a
     * different shape. Its value is the input space, and the table in the task report says which
     * mutations die only here.
     */
    @Test
    fun `a long score sequence agrees with the state machine on every frame`() {
        val config = VadConfig.probability(start = 0.62f, stop = 0.31f, holdTimeMs = 30)
        val holdNanos = config.holdTimeMs * 1_000_000L
        val d = detector(config)

        var expectedOn = false
        var expectedDeadline = nowNanos
        var everDetected = false
        val random = Random(20260919)

        // One counter per arm the loop has to reach, asserted afterwards. Without them the sweep
        // can report 200 000 agreements over a sequence that never leaves one branch, which is
        // exactly the "confirm whatever this configuration does" failure spec §4.04 names.
        var started = 0      // off -> on, i.e. the start threshold decided
        var keptByStop = 0   // on and above stop but below start, i.e. the hysteresis decided
        var keptByHold = 0   // on with no detection at all, i.e. the hold decided
        var stopped = 0      // on -> off

        for (i in 0 until 200_000) {
            // Runs rather than independent draws: a hold that is never entered is a hold that is
            // never swept, and an independent draw per frame almost never produces one.
            val score = if (random.nextInt(4) == 0) random.nextFloat() else if (i / 7 % 2 == 0) 0.9f else 0.05f
            // The tick is 10 ms, with the occasional longer gap so the hold expires mid-run.
            nowNanos += if (random.nextInt(50) == 0) ms(40) else ms(10)

            val wasOn = expectedOn
            val threshold = if (expectedOn) config.stopThreshold else config.startThreshold
            val detected = score >= threshold
            if (detected) {
                expectedDeadline = nowNanos + holdNanos
                everDetected = true
            }
            expectedOn = detected || (everDetected && nowNanos < expectedDeadline)

            if (!wasOn && expectedOn) started++
            if (wasOn && expectedOn && detected && score < config.startThreshold) keptByStop++
            if (wasOn && expectedOn && !detected) keptByHold++
            if (wasOn && !expectedOn) stopped++

            val actual = d.isVoice(EMPTY_FRAME, 0, score)
            if (actual != expectedOn) {
                throw AssertionError(
                    "diverges at frame $i: score=$score now=$nowNanos expected=$expectedOn actual=$actual",
                )
            }
        }
        println(
            "vad sweep over 200 000 frames: $started starts, $stopped stops, " +
                "$keptByStop frames held by the stop threshold, $keptByHold by the hold"
        )
        assertThat(started).isGreaterThan(1000)
        assertThat(keptByStop).isGreaterThan(1000)
        assertThat(keptByHold).isGreaterThan(1000)
        assertThat(stopped).isGreaterThan(1000)
    }

    private companion object {
        /** length 0, so `amplitudeScore` is never the thing under test in the sweep. */
        val EMPTY_FRAME = ShortArray(0)
    }
}
