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
        // Digital silence stays finite thanks to the `+1` the sum starts from.
        assertThat(VoiceActivityDetector.amplitudeScore(constant(0), 480)).isWithin(0.001f).of(-0.220f)
    }

    /** The legacy formula yields infinity for a zero-sample frame, i.e. louder than anything. */
    @Test
    fun `an empty frame is not voice`() {
        assertThat(VoiceActivityDetector.amplitudeScore(ShortArray(480), 0)).isEqualTo(VoiceActivityDetector.NO_SIGNAL)
        assertThat(VoiceActivityDetector.NO_SIGNAL).isLessThan(0f)
        assertThat(detector(VadConfig.amplitude(0f)).isVoice(ShortArray(480) { 32767 }, 0, null)).isFalse()
        assertThat(detector(VadConfig.probability(holdTimeMs = 0)).isVoice(ShortArray(480), 0, null)).isFalse()
    }

    /**
     * An off-by-one in the loop bound is invisible with constant frames, so all energy sits in the
     * two end samples: 0.75206 with both, 0.72070 with either missing.
     */
    @Test
    fun `the first and the last sample of the frame are both measured`() {
        val spikes = ShortArray(480).also { it[0] = 32767; it[479] = 32767 }
        assertThat(VoiceActivityDetector.amplitudeScore(spikes, 480)).isWithin(0.0005f).of(0.75206f)
    }

    /**
     * The user's slider is calibrated against this curve: 20 (amplitude dB), 96 (maps -96 dBFS
     * onto 0) and the `+1` offset.
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
     * A negative start is also rejected by the stop range check, so the message tells the two
     * guards apart. A negative stop would latch the microphone on for the session.
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
     * [VoiceActivityDetector] converts the hold with `holdTimeMs * 1_000_000`, which overflows
     * above [VadConfig.MAX_HOLD_MS]; the wrapped deadline would silently disable the hold.
     */
    @Test
    fun `a hold time that would overflow the nanosecond deadline is rejected`() {
        assertThat(VadConfig.MAX_HOLD_MS).isEqualTo(Long.MAX_VALUE / 1_000_000L)
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

    @Test
    fun `the hold is measured in nanoseconds`() {
        val d = detector(VadConfig.probability(holdTimeMs = 250))
        assertThat(d.isVoice(constant(0), 480, 0.9f)).isTrue()
        nowNanos = 300L  // 300 ns: past 250 ms only if the unit conversion is missing
        assertThat(d.isVoice(constant(0), 480, 0.0f)).isTrue()
    }

    /**
     * `System.nanoTime()` has an arbitrary origin, so values near `Long.MAX_VALUE` are legal; the
     * detector compares against a deadline (`now - deadline < 0`), which survives the wrap.
     */
    @Test
    fun `a clock near the end of its range does not latch the detector on`() {
        nowNanos = Long.MAX_VALUE - ms(10)
        val d = detector(VadConfig.probability(holdTimeMs = 250))
        assertThat(d.isVoice(constant(0), 480, 0.0f)).isFalse()
        nowNanos += ms(20)  // wraps past Long.MAX_VALUE
        assertThat(d.isVoice(constant(0), 480, 0.0f)).isFalse()
    }

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
     * The fallback is the level, not zero: a chain with no stages hands over `null` on every frame
     * and must not silently mute the user.
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
     * Existing users have no stored value and drive the detector through the level slider, which
     * is a no-op in probability mode; defaulting to AMPLITUDE keeps that slider working.
     */
    @Test
    fun `vad mode preference parsing defaults to the amplitude detector users have today`() {
        assertThat(VadMode.fromPreferenceValue("amplitude")).isEqualTo(VadMode.AMPLITUDE)
        assertThat(VadMode.fromPreferenceValue("probability")).isEqualTo(VadMode.PROBABILITY)
        assertThat(VadMode.fromPreferenceValue("nonsense")).isEqualTo(VadMode.AMPLITUDE)
        assertThat(VadMode.fromPreferenceValue(null)).isEqualTo(VadMode.AMPLITUDE)
    }

    /**
     * Pins the stored spelling directly: a misspelt value would still round-trip, because unknown
     * values fall back to the same default.
     */
    @Test
    fun `every vad mode keeps its on-disk value`() {
        assertThat(VadMode.entries.associate { it.name to it.preferenceValue })
            .containsExactly("AMPLITUDE", "amplitude", "PROBABILITY", "probability", "ADAPTIVE", "adaptive")
        assertThat(VadMode.entries.map { it.preferenceValue }).containsNoDuplicates()
        for (mode in VadMode.entries) {
            assertThat(VadMode.fromPreferenceValue(mode.preferenceValue)).isEqualTo(mode)
        }
    }

    /**
     * What the probability defaults mean as levels when `LevelToProbability` is the only opinion
     * (noise suppression NONE, echo cancellation WEBRTC).
     *
     * With the APM noise suppressor off, AGC2 caps non-speech output at a floor of roughly
     * -43.55 dBFS (hum) to -47.8 dBFS (white), median about -45. The default stop must stay above
     * that floor to be reachable, and the start means about 13 dB of signal above it.
     */
    @Test
    fun `the probability defaults sit at these dBFS levels on the apm window`() {
        fun levelFor(p: Float) =
            LevelToProbability.SILENCE_DBFS + p * (LevelToProbability.FULL_DBFS - LevelToProbability.SILENCE_DBFS)

        assertThat(levelFor(VadConfig.DEFAULT.startThreshold)).isWithin(0.05f).of(-32.0f)
        assertThat(levelFor(VadConfig.DEFAULT.stopThreshold)).isWithin(0.05f).of(-38.5f)

        // The contract: SNR above the measured floor.
        val medianNoiseFloorDbfs = -45.0f
        assertThat(levelFor(VadConfig.DEFAULT.startThreshold) - medianNoiseFloorDbfs).isWithin(0.05f).of(13.0f)
        assertThat(levelFor(VadConfig.DEFAULT.stopThreshold) - medianNoiseFloorDbfs).isWithin(0.05f).of(6.5f)

        // The floor reads as silence, and the loud edge of the band stays below the default stop.
        assertThat(LevelToProbability.fromDbfs(medianNoiseFloorDbfs)).isEqualTo(0f)
        assertThat(LevelToProbability.fromDbfs(-43.55f)).isWithin(0.001f).of(0.0668f)
        assertThat(LevelToProbability.fromDbfs(-43.55f)).isLessThan(VadConfig.DEFAULT.stopThreshold)
    }

    /**
     * Start, stop and hold only interact over a sequence, so 200 000 pseudo-random scores are
     * checked against a reference state machine. The loop reports the first diverging frame;
     * comparing two whole lists with Truth is far too slow and produces huge failure messages.
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

        // One counter per arm, asserted afterwards, so the sweep cannot pass while stuck in one branch.
        var started = 0      // off -> on, i.e. the start threshold decided
        var keptByStop = 0   // on and above stop but below start, i.e. the hysteresis decided
        var keptByHold = 0   // on with no detection at all, i.e. the hold decided
        var stopped = 0      // on -> off

        for (i in 0 until 200_000) {
            // Runs rather than independent draws, so the hold actually gets entered.
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
