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

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import se.lublin.humla.audio.capture.DoubleTalkRig.EchoPath
import se.lublin.humla.audio.capture.DoubleTalkRig.Noise
import se.lublin.humla.audio.capture.DoubleTalkRig.Scenario
import se.lublin.humla.audio.capture.DoubleTalkRig.Talkers
import se.lublin.humla.audio.capture.DoubleTalkRig.log
import java.util.Locale

/**
 * The measurements of [DoubleTalkAec3DeviceTest.shippedChainInDoubleTalk] and
 * [RnnoiseAttenuationLimitDeviceTest] again, with real speech ([Talkers.PIPER], see [SpeechCorpus])
 * in place of the synthetic vowels: RNNoise is a model trained on speech, so its verdict on
 * synthetic vowels may not carry over. Tables go to logcat (tag [DoubleTalkRig.TAG]).
 *
 * Measured on an SM-S938B (2026-09-30), eight scenarios per pair:
 * - Shipped chain, synthetic reference pair against the four Piper pairs: double-talk gate 41 %
 *   against 66-81 %, voiced near-end frames zeroed 63 % against 34-39 %, near end alone 71 %
 *   against 98-99 %, echo-only false-open 13 % against 4-7 %. APM alone: gate 82-90 %, nothing
 *   zeroed. So the near-end-alone loss was an artifact of the synthetic vowels; RNNoise still
 *   zeroes real near-end speech in double talk, mostly at loud echo (0 and +6 dB: 50-76 %; at
 *   -20 dB: 1-10 %).
 * - Attenuation limit, AGC2 in front, Piper pairs: gate 74 % without a limit, 85-86 % with 12 to
 *   30 dB; mean echo false-open 5.9 % without, 3.5 / 3.9 / 4.7 / 6.4 % at 12 / 18 / 24 / 30 dB
 *   (worst scenario +0.4 / +0.4 / +3.6 / +7.2 points); pink residual -93 dBFS without, -59 / -65 /
 *   -71 / -77 dBFS with; real babble opens the gate on 84-92 % of frames whatever the limit; near end
 *   alone 98-99 % throughout.
 */
@RunWith(AndroidJUnit4::class)
class RealSpeechDeviceTest {

    /** The clips are there, 12 s at 48 kHz, and speech: loud enough and with pauses. */
    @Test
    fun theSpeechCorpusLoadsAsTwelveSecondsOfSpeech() {
        for (clip in SpeechCorpus.Clip.entries) {
            val pcm = SpeechCorpus.load(clip)
            val frames = pcm.size / DoubleTalkRig.FRAME
            val levels = (0 until frames).map { DoubleTalkRig.dbfs(DoubleTalkRig.meanPower(pcm, it, it + 1)) }
            val peak = levels.max()
            val active = levels.count { it > peak - ACTIVE_RANGE_DB }.toFloat() / frames
            log(String.format(Locale.ROOT, "%s: %d samples, peak frame %.1f dBFS, active %.0f%%",
                clip, pcm.size, peak, 100 * active))
            assertWithMessage("%s length", clip).that(pcm.size).isEqualTo(CLIP_SECONDS * SpeechCorpus.RATE)
            assertWithMessage("%s peak frame level", clip).that(peak).isGreaterThan(-30f)
            // Measured 42-62 %: speech with its pauses, not a tone and not mostly silence.
            assertWithMessage("%s share of active frames", clip).that(active).isIn(Range.closed(0.3f, 0.9f))
        }
        for (pair in DoubleTalkRig.PIPER_PAIRS.indices) {
            val voiced = DoubleTalkRig.voicedDoubleTalkFrames(NEAR_DBFS, pair, Talkers.PIPER)
            assertWithMessage("voiced near-end frames in double talk, pair %s", pair)
                .that(voiced.size).isAtLeast((DoubleTalkRig.dtEnd - DoubleTalkRig.dtStart) / 3)
        }
    }

    /**
     * The shipped WEBRTC chain (AEC3+AGC2+HPF, then RNNoise) and APM alone, over the sweep's eight
     * scenarios, synthetic reference pair against the four Piper pairs: decides whether "RNNoise
     * behind AEC3 closes the gate in double talk" holds for real speech. The Piper pairs are held
     * to loose characterization bounds around the measured numbers (see the class comment); the
     * synthetic pair is bounded by [DoubleTalkAec3DeviceTest].
     */
    @Test
    fun shippedChainWithRealSpeech() {
        val factory = CapturePreprocessorFactory(log = { Log.w(DoubleTalkRig.TAG, it) })
        val chains = listOf<Pair<String, () -> CaptureChain>>(
            SHIPPED to { factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC) },
            UNLIMITED to { DoubleTalkRig.limitedChain(Float.POSITIVE_INFINITY, agcAfter = false) },
            "APM only" to { factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC) },
        )
        log(HEADER)
        val voices = listOf(Talkers.SYNTHETIC to 0) + DoubleTalkRig.PIPER_PAIRS.indices.map { Talkers.PIPER to it }
        val rows = ArrayList<ChainRow>()
        for ((name, build) in chains) {
            for ((corpus, pair) in voices) {
                val nearAlone = DoubleTalkRig.runNearAlone(build(), NEAR_DBFS, pair, corpus)
                val results = scenarios(pair, corpus).map {
                    DoubleTalkRig.measure(it, build, nearAlone).also { m -> logMeasurement(name, it, m) }
                }
                val nearAloneGate = DoubleTalkRig.nearAloneGate(nearAlone, NEAR_DBFS, pair, corpus)
                rows += ChainRow(name, corpus, pair, results, nearAloneGate)
            }
        }
        log("summary: chain, talkers | double-talk gate, zeroed voiced frames | echo-only false-open | near alone")
        rows.forEach { log(it.summary()) }
        for (row in rows.filter { it.corpus == Talkers.PIPER }) {
            val what = "${row.chain}, Piper pair #${row.pair}"
            assertWithMessage("%s: near end alone, gate open", what).that(row.nearAloneGate).isAtLeast(0.9f)
            assertWithMessage("%s: echo alone, mean false-open", what).that(row.falseOpen).isAtMost(0.15)
            when (row.chain) {
                SHIPPED -> {
                    // The shipped chain is limitedChain(18 dB, AGC2 in front), which the sweep below
                    // measured at 85-86 % mean gate; the limit keeps RNNoise from zeroing the near end
                    // (0 % zeroed in DoubleTalkAec3DeviceTest). Bounded per pair a little below that.
                    assertWithMessage("%s: double-talk gate, mean", what).that(row.gateOpen).isAtLeast(SHIPPED_MIN_GATE)
                    assertWithMessage("%s: zeroed voiced frames, mean", what).that(row.zeroed).isAtMost(0.05)
                }
                UNLIMITED -> {
                    // Measured 66-81 % and 34-39 %: RNNoise alone still zeroes real near-end speech.
                    assertWithMessage("%s: double-talk gate, mean", what).that(row.gateOpen)
                        .isIn(Range.closed(0.55, 0.92))
                    assertWithMessage("%s: zeroed voiced frames, mean", what).that(row.zeroed)
                        .isIn(Range.closed(0.2, 0.55))
                }
                else -> {
                    // Measured 82-90 % and none: without RNNoise nothing is zeroed.
                    assertWithMessage("%s: double-talk gate, mean", what).that(row.gateOpen).isAtLeast(0.72)
                    assertWithMessage("%s: zeroed voiced frames, mean", what).that(row.zeroed).isAtMost(0.05)
                }
            }
        }
    }

    /**
     * RNNoise's attenuation limit (AGC2 in front, as shipped) over the four Piper pairs: double
     * talk, echo alone, pink noise, real-speech babble and the near end alone, held to loose
     * characterization bounds around the measured numbers (see the class comment). Takes many
     * minutes.
     */
    @Test
    fun attenuationLimitSweepWithRealSpeech() {
        log(HEADER)
        val results = LIMITS.associateWith { limit ->
            val build = { DoubleTalkRig.limitedChain(limit, agcAfter = false) }
            DoubleTalkRig.PIPER_PAIRS.indices.map { pair ->
                val nearAlone = DoubleTalkRig.runNearAlone(build(), NEAR_DBFS, pair, Talkers.PIPER)
                val doubleTalk = scenarios(pair, Talkers.PIPER).map {
                    DoubleTalkRig.measure(it, build, nearAlone).also { m -> logMeasurement(limitName(limit), it, m) }
                }
                Results(doubleTalk, DoubleTalkRig.nearAloneGate(nearAlone, NEAR_DBFS, pair, Talkers.PIPER))
            }.let { perPair ->
                Sweep(
                    perPair,
                    DoubleTalkRig.measureNoise(build(), DoubleTalkRig.noise(Noise.PINK, NOISE_DBFS)),
                    DoubleTalkRig.measureNoise(build(), DoubleTalkRig.noise(Noise.BABBLE_REAL, NOISE_DBFS)),
                )
            }
        }
        val base = results.getValue(Float.POSITIVE_INFINITY).perPair.flatMap { it.doubleTalk }
        log(
            String.format(
                Locale.ROOT, "summary over %d scenarios, Piper talkers; noise in: pink %.1f, real babble %.1f dBFS",
                base.size, results.values.first().pink.inputDbfs, results.values.first().babble.inputDbfs,
            ),
        )
        log("limit  | DT gate mean  min | echo FO mean  max+ vs inf | pink FO  resid p95 | " +
            "babble FO  resid p95 | near alone")
        for ((limit, sweep) in results) {
            val mine = sweep.perPair.flatMap { it.doubleTalk }
            val worst = mine.indices.maxOf { mine[it].falseOpen - base[it].falseOpen }
            log(
                String.format(
                    Locale.ROOT,
                    "%-6s | %5.1f%%  %4.0f%% | %5.1f%%  %+5.1f pts | %5.1f%% %6.1f %6.1f | " +
                        "%5.1f%% %6.1f %6.1f | %5.1f%%",
                    limitName(limit), 100 * mine.map { it.gateOpen }.average(), 100 * mine.minOf { it.gateOpen },
                    100 * mine.map { it.falseOpen }.average(), 100 * worst,
                    100 * sweep.pink.falseOpen, sweep.pink.residualDbfs, sweep.pink.residualP95Dbfs,
                    100 * sweep.babble.falseOpen, sweep.babble.residualDbfs, sweep.babble.residualP95Dbfs,
                    100 * sweep.perPair.map { it.nearAloneGate }.average(),
                ),
            )
        }
        assertSweepBounds(results, base)
    }

    /** Loose characterization bounds around the numbers in the class comment. */
    private fun assertSweepBounds(results: Map<Float, Sweep>, base: List<DoubleTalkRig.Measurement>) {
        val baseGate = base.map { it.gateOpen }.average()
        for ((limit, sweep) in results) {
            val name = limitName(limit)
            val mine = sweep.perPair.flatMap { it.doubleTalk }
            assertWithMessage("%s: near end alone, gate open", name)
                .that(sweep.perPair.map { it.nearAloneGate }.average()).isAtLeast(0.9)
            assertWithMessage("%s: pink noise, false-open", name).that(sweep.pink.falseOpen).isAtMost(0.02f)
            // Measured 84-92 %: no limit keeps real babble out of the gate.
            assertWithMessage("%s: real babble, false-open", name).that(sweep.babble.falseOpen).isAtLeast(0.6f)
            if (limit.isInfinite()) continue
            // Measured 85-86 % against 74 % without a limit.
            assertWithMessage("%s: double-talk gate, mean, over no limit's", name)
                .that(mine.map { it.gateOpen }.average() - baseGate).isAtLeast(0.05)
            // Measured within 2 dB of the input level minus the limit.
            val expected = sweep.pink.inputDbfs - limit.toDouble()
            assertWithMessage("%s: pink noise residual (dBFS)", name).that(sweep.pink.residualDbfs.toDouble())
                .isIn(Range.closed(expected - RESIDUAL_TOLERANCE_DB, expected + RESIDUAL_TOLERANCE_DB))
            if (limit <= 18f) {
                // Measured +0.4 points at 12 and 18 dB (+3.6 at 24, +7.2 at 30, not bounded).
                assertWithMessage("%s: echo false-open, worst scenario over no limit's", name)
                    .that(mine.indices.maxOf { mine[it].falseOpen - base[it].falseOpen }).isAtMost(0.03f)
            }
        }
    }

    private class ChainRow(
        val chain: String,
        val corpus: Talkers,
        val pair: Int,
        val results: List<DoubleTalkRig.Measurement>,
        val nearAloneGate: Float,
    ) {
        val gateOpen = results.map { it.gateOpen }.average()
        val zeroed = results.map { it.zeroed }.average()
        val falseOpen = results.map { it.falseOpen }.average()

        fun summary(): String = String.format(
            Locale.ROOT,
            "%-9s %-9s #%d | gate %5.1f%% (min %3.0f%%) zeroed %5.1f%% | echo FO %5.1f%% " +
                "(max %4.1f%%) | near alone %5.1f%%",
            chain, corpus, pair, 100 * gateOpen, 100 * results.minOf { it.gateOpen }, 100 * zeroed,
            100 * falseOpen, 100 * results.maxOf { it.falseOpen }, 100 * nearAloneGate,
        )
    }

    private class Results(val doubleTalk: List<DoubleTalkRig.Measurement>, val nearAloneGate: Float)

    private class Sweep(
        val perPair: List<Results>,
        val pink: DoubleTalkRig.NoiseMeasurement,
        val babble: DoubleTalkRig.NoiseMeasurement,
    )

    private fun limitName(limit: Float) = if (limit.isInfinite()) "X=inf" else "X=${limit.toInt()}"

    private fun scenarios(pair: Int, corpus: Talkers) =
        SWEEP_SCENARIOS.map { Scenario(it.echoGainDb, it.nearDbfs, it.path, pair, corpus) }

    private fun logMeasurement(name: String, scenario: Scenario, m: DoubleTalkRig.Measurement) {
        log(
            String.format(
                Locale.ROOT,
                "%-19s %-38s %6.1f  | %+6.1f  %4.0f%%  %4.0f%%  | %6.1f %6.1f  %4.1f%% (%4.0f%%)",
                name, scenario.name, m.echoInDbfs, m.keptDb, 100 * m.gateOpen, 100 * m.zeroed,
                m.residualDbfs, m.residualP95Dbfs, 100 * m.falseOpen, 100 * m.falseOpenConverging,
            ),
        )
    }

    private companion object {
        const val SHIPPED = "shipped"
        const val UNLIMITED = "no limit"
        const val SHIPPED_MIN_GATE = 0.72
        const val RESIDUAL_TOLERANCE_DB = 6.0
        const val NEAR_DBFS = -26f
        const val NOISE_DBFS = -45f
        const val CLIP_SECONDS = 12
        const val ACTIVE_RANGE_DB = 20f

        const val HEADER = "chain               scenario                               echo-in  | " +
            "DT: kept  gate  zeroed | echo only: resid  p95   false-open (0-2 s)"

        val LIMITS = listOf(Float.POSITIVE_INFINITY, 12f, 18f, 24f, 30f)

        val SWEEP_SCENARIOS = EchoPath.entries.flatMap { path ->
            listOf(-20f, -10f, 0f, 6f).map { Scenario(echoGainDb = it, nearDbfs = NEAR_DBFS, path) }
        }
    }
}
