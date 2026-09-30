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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import se.lublin.humla.audio.capture.DoubleTalkRig.EchoPath
import se.lublin.humla.audio.capture.DoubleTalkRig.Noise
import se.lublin.humla.audio.capture.DoubleTalkRig.Scenario
import se.lublin.humla.audio.capture.DoubleTalkRig.log
import java.util.Locale

/**
 * RNNoise's attenuation limit ([RnnoisePreprocessor]) and AGC2's place in the WEBRTC chain, over
 * double talk, echo alone, noise alone and the near end alone (real `libhumla_native.so`, see
 * [DoubleTalkRig]). Tables go to logcat (tag [DoubleTalkRig.TAG]).
 *
 * Measured 2026-09 (x86-64 host build of the same native sources, three talker pairs): any limit
 * from 12 to 30 dB with AGC2 in front lifts the double-talk gate from 44 % to 85 % and the near end
 * alone from 73 % to 97 %, and halves the mean echo-only false transmit (21 to 10 %), but on the
 * loud nonlinear echo path (+6 dB) echo alone opens the gate 12-16 points more often than without
 * a limit. AGC2 behind RNNoise lifts residual babble from about -48 to -26 dBFS, so AGC2 stays in
 * front. With real speech and in a real room 18 dB did not show that echo-only penalty, and it
 * ships: see [RnnoisePreprocessor.ATTENUATION_LIMIT_DB]. "X=inf" here is RNNoise without a limit.
 */
@RunWith(AndroidJUnit4::class)
class RnnoiseAttenuationLimitDeviceTest {

    private class Combo(val limitDb: Float, val agcAfter: Boolean) {
        val name: String
            get() = (if (limitDb.isInfinite()) "X=inf" else "X=${limitDb.toInt()}") +
                if (agcAfter) " AGC after" else " AGC before"

        fun build() = DoubleTalkRig.limitedChain(limitDb, agcAfter)
    }

    private class Results(
        val doubleTalk: List<DoubleTalkRig.Measurement>,
        val nearAloneGate: Float,
        val pink: DoubleTalkRig.NoiseMeasurement,
        val babble: DoubleTalkRig.NoiseMeasurement,
    )

    private fun measure(combo: Combo, talkers: Int, log: Boolean = true): Results {
        val nearAlone = DoubleTalkRig.runNearAlone(combo.build(), NEAR_DBFS, talkers)
        val doubleTalk = SWEEP_SCENARIOS.map { Scenario(it.echoGainDb, it.nearDbfs, it.path, talkers) }.map {
            DoubleTalkRig.measure(it, combo::build, nearAlone).also { m -> if (log) logMeasurement(combo.name, it, m) }
        }
        return Results(
            doubleTalk,
            DoubleTalkRig.nearAloneGate(nearAlone, NEAR_DBFS, talkers),
            DoubleTalkRig.measureNoise(combo.build(), DoubleTalkRig.noise(Noise.PINK, NOISE_DBFS)),
            DoubleTalkRig.measureNoise(combo.build(), DoubleTalkRig.noise(Noise.BABBLE, NOISE_DBFS)),
        )
    }

    /**
     * CHARACTERIZATION, asserted, reference talkers: the 24 dB candidate against the shipped chain.
     * Bounds sit well outside what the host build measured (in brackets), so this catches a limit
     * that stopped working (gate back near 40 %, near end cut) or one that lets noise through.
     */
    @Test
    fun limitOf24DbAgainstTheShippedChain() {
        val today = measure(Combo(Float.POSITIVE_INFINITY, agcAfter = false), talkers = 0)
        val limited = measure(Combo(CANDIDATE_DB, agcAfter = false), talkers = 0)
        val gateToday = today.doubleTalk.map { it.gateOpen }.average()
        val gate = limited.doubleTalk.map { it.gateOpen }.average()
        val falseOpenToday = today.doubleTalk.map { it.falseOpen }.average()
        val falseOpen = limited.doubleTalk.map { it.falseOpen }.average()
        log(
            String.format(
                Locale.ROOT,
                "24 dB vs shipped: gate %.2f vs %.2f, echo false-open %.3f vs %.3f, near alone %.2f vs %.2f",
                gate, gateToday, falseOpen, falseOpenToday, limited.nearAloneGate, today.nearAloneGate,
            ),
        )
        assertWithMessage("mean double-talk gate (0.85 vs 0.42)").that(gate).isAtLeast(0.75)
        assertWithMessage("gain over the shipped chain").that(gate - gateToday).isAtLeast(0.25)
        for ((i, m) in limited.doubleTalk.withIndex()) {
            val name = SWEEP_SCENARIOS[i].name
            assertWithMessage("gate, %s (67-97 %%)", name).that(m.gateOpen).isAtLeast(0.5f)
            assertWithMessage("echo false-open, %s (0-34 %%)", name).that(m.falseOpen).isAtMost(0.45f)
        }
        assertWithMessage("mean echo false-open (0.112 vs 0.124)").that(falseOpen).isAtMost(falseOpenToday + 0.03)
        assertWithMessage("near end alone (0.96 vs 0.68)").that(limited.nearAloneGate).isAtLeast(0.9f)
        assertWithMessage("pink noise false-open (0)").that(limited.pink.falseOpen).isAtMost(0.02f)
        assertWithMessage("pink residual, 45 dBFS in (-71 dBFS)").that(limited.pink.residualDbfs).isAtMost(-64f)
        assertWithMessage("babble false-open (0.91 vs 0.95)").that(limited.babble.falseOpen)
            .isAtMost(today.babble.falseOpen + 0.05f)
    }

    /**
     * MEASUREMENT HARNESS: every combination over [SWEEP_TALKERS] talker pairs; asserts only that
     * every frame was taken. Takes many minutes.
     */
    @Test
    fun sweepAttenuationLimitAndAgcPlacement() {
        val combos = LIMITS.flatMap { limit -> listOf(false, true).map { Combo(limit, it) } }
        val today = combos.first { it.limitDb.isInfinite() && !it.agcAfter }
        log("combo               scenario                         echo-in  | DT: kept  gate  zeroed | " +
            "echo only: resid  p95   false-open (0-2 s)")
        val results = combos.associateWith { combo -> (0 until SWEEP_TALKERS).map { measure(combo, it) } }

        val noiseIn = results.getValue(today).first()
        log(
            String.format(
                Locale.ROOT, "summary over %d scenarios; noise in: pink %.1f dBFS, babble %.1f dBFS",
                SWEEP_SCENARIOS.size * SWEEP_TALKERS, noiseIn.pink.inputDbfs, noiseIn.babble.inputDbfs,
            ),
        )
        log("combo               | DT gate mean  min | echo FO mean  max+ vs today | pink FO  resid p95 | " +
            "babble FO  resid p95 | near alone")
        val base = results.getValue(today).flatMap { it.doubleTalk }
        for ((combo, perTalkers) in results) {
            val mine = perTalkers.flatMap { it.doubleTalk }
            val worst = mine.indices.maxOf { mine[it].falseOpen - base[it].falseOpen }
            log(
                String.format(
                    Locale.ROOT,
                    "%-19s | %5.1f%%  %4.0f%% | %5.1f%%  %+5.1f pts | " +
                        "%5.1f%% %6.1f %6.1f | %5.1f%% %6.1f %6.1f | %5.1f%%",
                    combo.name, 100 * mine.map { it.gateOpen }.average(), 100 * mine.minOf { it.gateOpen },
                    100 * mine.map { it.falseOpen }.average(), 100 * worst,
                    100 * perTalkers.map { it.pink.falseOpen }.average(),
                    perTalkers.map { it.pink.residualDbfs }.average(),
                    perTalkers.map { it.pink.residualP95Dbfs }.average(),
                    100 * perTalkers.map { it.babble.falseOpen }.average(),
                    perTalkers.map { it.babble.residualDbfs }.average(),
                    perTalkers.map { it.babble.residualP95Dbfs }.average(),
                    100 * perTalkers.map { it.nearAloneGate }.average(),
                ),
            )
        }
    }

    private fun logMeasurement(name: String, scenario: Scenario, m: DoubleTalkRig.Measurement) {
        log(
            String.format(
                Locale.ROOT,
                "%-19s %-32s %6.1f  | %+6.1f  %4.0f%%  %4.0f%%  | %6.1f %6.1f  %4.1f%% (%4.0f%%)",
                name, scenario.name, m.echoInDbfs, m.keptDb, 100 * m.gateOpen, 100 * m.zeroed,
                m.residualDbfs, m.residualP95Dbfs, 100 * m.falseOpen, 100 * m.falseOpenConverging,
            ),
        )
    }

    private companion object {
        const val NEAR_DBFS = -26f
        const val NOISE_DBFS = -45f
        const val CANDIDATE_DB = 24f

        /** Talker pairs; three so one pair's luck does not decide. */
        const val SWEEP_TALKERS = 3

        val LIMITS = listOf(12f, 18f, 24f, 30f, Float.POSITIVE_INFINITY)

        val SWEEP_SCENARIOS = EchoPath.entries.flatMap { path ->
            listOf(-20f, -10f, 0f, 6f).map { Scenario(echoGainDb = it, nearDbfs = NEAR_DBFS, path) }
        }
    }
}
