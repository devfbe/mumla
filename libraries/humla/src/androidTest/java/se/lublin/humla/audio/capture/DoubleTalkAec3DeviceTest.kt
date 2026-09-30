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
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import se.lublin.humla.audio.capture.Aec3Param.AUDIBILITY_LOW_RENDER_LIMIT
import se.lublin.humla.audio.capture.Aec3Param.AUDIBILITY_NORMAL_RENDER_LIMIT
import se.lublin.humla.audio.capture.Aec3Param.DNE_ENR_THRESHOLD
import se.lublin.humla.audio.capture.Aec3Param.DNE_TRIGGER_THRESHOLD
import se.lublin.humla.audio.capture.Aec3Param.EP_DEFAULT_LEN
import se.lublin.humla.audio.capture.Aec3Param.EP_NEAREND_LEN
import se.lublin.humla.audio.capture.Aec3Param.ERLE_MAX_H
import se.lublin.humla.audio.capture.Aec3Param.ERLE_MAX_L
import se.lublin.humla.audio.capture.Aec3Param.NEAREND_HF_ENR_SUPPRESS
import se.lublin.humla.audio.capture.Aec3Param.NEAREND_HF_ENR_TRANSPARENT
import se.lublin.humla.audio.capture.Aec3Param.NEAREND_LF_ENR_SUPPRESS
import se.lublin.humla.audio.capture.Aec3Param.NEAREND_LF_ENR_TRANSPARENT
import se.lublin.humla.audio.capture.Aec3Param.NORMAL_HF_ENR_SUPPRESS
import se.lublin.humla.audio.capture.Aec3Param.NORMAL_HF_ENR_TRANSPARENT
import se.lublin.humla.audio.capture.Aec3Param.NORMAL_LF_ENR_SUPPRESS
import se.lublin.humla.audio.capture.Aec3Param.NORMAL_LF_ENR_TRANSPARENT
import se.lublin.humla.audio.capture.Aec3Param.SUBBAND1_HIGH
import se.lublin.humla.audio.capture.Aec3Param.SUBBAND1_LOW
import se.lublin.humla.audio.capture.Aec3Param.SUBBAND2_HIGH
import se.lublin.humla.audio.capture.Aec3Param.SUBBAND2_LOW
import se.lublin.humla.audio.capture.Aec3Param.SUBBAND_NEAREND_THRESHOLD
import se.lublin.humla.audio.capture.Aec3Param.SUBBAND_SNR_THRESHOLD
import se.lublin.humla.audio.capture.Aec3Param.USE_SUBBAND_NEAREND_DETECTION
import se.lublin.humla.audio.capture.DoubleTalkRig.EchoPath
import se.lublin.humla.audio.capture.DoubleTalkRig.Phase
import se.lublin.humla.audio.capture.DoubleTalkRig.Scenario
import se.lublin.humla.audio.capture.DoubleTalkRig.db
import se.lublin.humla.audio.capture.DoubleTalkRig.dbfs
import se.lublin.humla.audio.capture.DoubleTalkRig.log
import se.lublin.humla.audio.capture.DoubleTalkRig.mean
import se.lublin.humla.audio.capture.DoubleTalkRig.meanPower
import se.lublin.humla.audio.native.RnnoiseNative
import se.lublin.humla.audio.native.WebRtcApmNative
import java.util.Locale
import kotlin.math.max

/**
 * Double talk through the capture chain the app builds for [EchoCancellationMode.WEBRTC] (real
 * `libhumla_native.so`: AEC3 + AGC2 + high-pass, then RNNoise), see [DoubleTalkRig]: how much
 * near-end speech survives while the far end talks, whether the app's default adaptive gate opens
 * on it, and how much echo gets through when only the far end talks.
 *
 * Measured on an SM-S938B (2026-09): no AEC3 configuration reachable through [Aec3Tuning] keeps
 * the gate open on 90 % of voiced near-end frames without transmitting more echo somewhere; the
 * gate closed because RNNoise, behind AEC3, silenced the near end (AEC3 alone keeps it). The
 * shipped chain therefore limits RNNoise to 18 dB, which keeps the gate open on 66-97 % of voiced
 * near-end frames. See [sweepAec3Tunings] and [shippedChainInDoubleTalk].
 */
@RunWith(AndroidJUnit4::class)
class DoubleTalkAec3DeviceTest {

    /** The chains the app builds for WEBRTC echo cancellation, and diagnostic variants. */
    private enum class Chain(val label: String) {
        APP_APM("APM (app: AEC3+AGC2+HPF)"),
        APP_APM_RNNOISE("APM+RNNoise (app)"),
        APM_NO_AGC_RNNOISE("APM without AGC2 + RNNoise (diagnostic)"),
        RNNOISE_ONLY("RNNoise only, no AEC (diagnostic)"),
    }

    /**
     * CHARACTERIZATION: the four chains, per phase. Prints its tables to logcat (tag
     * [DoubleTalkRig.TAG]); asserts only that the chain accepted every frame.
     */
    @Test
    fun nearEndSurvivesTheWebRtcChainDuringDoubleTalk() {
        val scenarios = listOf(
            Scenario(echoGainDb = -20f, nearDbfs = -26f, EchoPath.LINEAR),
            Scenario(echoGainDb = -10f, nearDbfs = -26f, EchoPath.LINEAR),
            Scenario(echoGainDb = -10f, nearDbfs = -32f, EchoPath.LINEAR),
            Scenario(echoGainDb = 0f, nearDbfs = -26f, EchoPath.LINEAR),
            Scenario(echoGainDb = 6f, nearDbfs = -26f, EchoPath.LINEAR),
        )
        log("far end ${DoubleTalkRig.FAR_DBFS} dBFS, echo delay ${DoubleTalkRig.ECHO_DELAY_MS} ms, " +
            "mic noise ${DoubleTalkRig.NOISE_DBFS} dBFS; shipped AEC3: ${WebRtcApmConfig.FOR_ECHO_CANCELLATION.aec3}")
        log("VAD: app defaults, adaptive fraction 0.65, hold 250 ms, onset 2 frames")
        for (scenario in scenarios) {
            for (chain in Chain.entries) characterize(scenario, chain)
        }
    }

    /**
     * webrtc's default config handed over as a tuning takes the injected-factory path in
     * `humla_apm.cpp`; it must be the very canceller the built-in path creates, sample for sample.
     * Also catches a Kotlin default that drifted from the native one.
     */
    @Test
    fun defaultTuningIsBitIdenticalToNoTuning() {
        val scenario = Scenario(echoGainDb = 0f, nearDbfs = -26f, EchoPath.NONLINEAR)
        val mic = DoubleTalkRig.sum(
            DoubleTalkRig.echo(scenario), DoubleTalkRig.near(scenario.nearDbfs), DoubleTalkRig.noiseFloor,
        )

        val builtIn = DoubleTalkRig.run(DoubleTalkRig.appChain(aec3 = null), DoubleTalkRig.far, mic)
        val injected = DoubleTalkRig.run(DoubleTalkRig.appChain(aec3 = Aec3Tuning.DEFAULT), DoubleTalkRig.far, mic)
        // A knob that changes this scenario (a raised ERLE cap does not: the measured ERLE stays below it).
        val shorterTail = D.with(EP_DEFAULT_LEN to 0.7f)
        val tuned = DoubleTalkRig.run(DoubleTalkRig.appChain(aec3 = shorterTail), DoubleTalkRig.far, mic)

        assertThat(injected.hash).isEqualTo(builtIn.hash)
        assertThat(tuned.hash).isNotEqualTo(builtIn.hash)
    }

    /**
     * CHARACTERIZATION, asserted, reference talkers: the shipped chain (RNNoise limited to 18 dB)
     * over the sweep's eight scenarios, and the finding behind the limit. Bounds sit outside what
     * the SM-S938B measured (see the constants), so a tuning or chain change that spams echo or
     * closes the gate again fails here.
     */
    @Test
    fun shippedChainInDoubleTalk() {
        val factory = CapturePreprocessorFactory(log = { Log.w(DoubleTalkRig.TAG, it) })
        val shipped = { factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC) }
        val unlimited = { DoubleTalkRig.limitedChain(Float.POSITIVE_INFINITY, agcAfter = false) }
        val apmOnly = { factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC) }
        val nearShipped = DoubleTalkRig.runNearAlone(shipped(), NEAR_DBFS)
        val nearUnlimited = DoubleTalkRig.runNearAlone(unlimited(), NEAR_DBFS)
        val nearApmOnly = DoubleTalkRig.runNearAlone(apmOnly(), NEAR_DBFS)
        // Everything is measured and logged before anything is asserted, so one run gives the table.
        val sweep = SWEEP_SCENARIOS.map { scenario ->
            DoubleTalkRig.measure(scenario, shipped, nearShipped)
                .also { logMeasurement("shipped (18 dB)", scenario, it) }
        }
        val findings = listOf(-20f, 0f).map { echoGainDb ->
            val scenario = Scenario(echoGainDb, NEAR_DBFS, EchoPath.LINEAR)
            Finding(
                scenario,
                shipped = DoubleTalkRig.measure(scenario, shipped, nearShipped),
                unlimited = DoubleTalkRig.measure(scenario, unlimited, nearUnlimited)
                    .also { logMeasurement("RNNoise unlimited", scenario, it) },
                apmOnly = DoubleTalkRig.measure(scenario, apmOnly, nearApmOnly)
                    .also { logMeasurement("APM only", scenario, it) },
            )
        }
        for ((scenario, m) in SWEEP_SCENARIOS.zip(sweep)) {
            assertWithMessage("false transmit on echo alone, %s", scenario.name).that(m.falseOpen)
                .isAtMost(SHIPPED_MAX_FALSE_OPEN)
            assertWithMessage("gate in double talk, %s", scenario.name).that(m.gateOpen).isAtLeast(SHIPPED_MIN_GATE)
        }
        // The finding behind the 18 dB default: AEC3 alone passes the near end, unlimited RNNoise
        // behind it silences it, and the limit gives most of it back.
        for (f in findings) {
            val name = f.scenario.name
            assertWithMessage("AEC3 alone keeps the gate open, %s (99, 94 %%)", name)
                .that(f.apmOnly.gateOpen).isAtLeast(0.9f)
            assertWithMessage("unlimited RNNoise behind AEC3 closes it, %s (62, 26 %%)", name)
                .that(f.unlimited.gateOpen).isAtMost(0.75f)
            assertWithMessage("unlimited RNNoise zeroes voiced near-end frames, %s (45, 64 %%)", name)
                .that(f.unlimited.zeroed).isAtLeast(0.3f)
            assertWithMessage("18 dB zeroes no voiced near-end frame, %s", name).that(f.shipped.zeroed).isAtMost(0.02f)
            assertWithMessage("18 dB opens the gate more than unlimited, %s", name)
                .that(f.shipped.gateOpen - f.unlimited.gateOpen).isAtLeast(SHIPPED_MIN_GAIN)
        }
    }

    private class Finding(
        val scenario: Scenario,
        val shipped: DoubleTalkRig.Measurement,
        val unlimited: DoubleTalkRig.Measurement,
        val apmOnly: DoubleTalkRig.Measurement,
    )

    /**
     * MEASUREMENT HARNESS: every candidate over the eight scenarios (four echo levels, linear and
     * nonlinear path), for [SWEEP_TALKERS] talker pairs. Logs one line per candidate and scenario
     * and a summary against the default; asserts only that every frame was taken. Takes minutes;
     * raise [SWEEP_TALKERS] to 3 before deciding anything, single pairs swing by tens of points.
     */
    @Test
    fun sweepAec3Tunings() {
        log("candidate           scenario                         echo-in  | DT: kept  gate  zeroed | " +
            "echo only: resid  p95   false-open (0-2 s)")
        val nearAlone = HashMap<String, DoubleTalkRig.Run>()
        val results = HashMap<Pair<String, String>, DoubleTalkRig.Measurement>()
        val scenarios = (0 until SWEEP_TALKERS).flatMap { talkers ->
            SWEEP_SCENARIOS.map { Scenario(it.echoGainDb, it.nearDbfs, it.path, talkers) }
        }
        for (scenario in scenarios) {
            for (candidate in CANDIDATES) {
                val build = candidate.chain ?: { DoubleTalkRig.appChain(candidate.tuning) }
                val c = nearAlone.getOrPut("${candidate.name}#${scenario.talkers}") {
                    DoubleTalkRig.runNearAlone(build(), scenario.nearDbfs, scenario.talkers)
                }
                val m = DoubleTalkRig.measure(scenario, build, c)
                results[candidate.name to scenario.name] = m
                logMeasurement(candidate.name, scenario, m)
            }
        }
        log("summary over ${scenarios.size} scenarios: mean gate, mean false-open, " +
            "scenarios with false-open > default + 2 points")
        val base = scenarios.map { results.getValue(CANDIDATES[0].name to it.name) }
        for (candidate in CANDIDATES) {
            val mine = scenarios.map { results.getValue(candidate.name to it.name) }
            val worse = mine.indices.count { mine[it].falseOpen > base[it].falseOpen + 0.02f }
            log(
                String.format(
                    Locale.ROOT, "%-19s gate %4.1f%%  false-open %4.1f%%  worse in %d; min gate %3.0f%%",
                    candidate.name, 100 * mine.map { it.gateOpen }.average(), 100 * mine.map { it.falseOpen }.average(),
                    worse, 100 * mine.minOf { it.gateOpen },
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

    private fun characterize(scenario: Scenario, chain: Chain) {
        val near = DoubleTalkRig.near(scenario.nearDbfs)
        val echo = DoubleTalkRig.echo(scenario)
        val noise = DoubleTalkRig.noiseFloor
        val micA = DoubleTalkRig.sum(echo, near, noise)
        val a = DoubleTalkRig.run(build(chain), DoubleTalkRig.far, micA)
        val b = DoubleTalkRig.run(build(chain), DoubleTalkRig.far, DoubleTalkRig.sum(echo, noise))
        val c = DoubleTalkRig.run(build(chain), DoubleTalkRig.silence, DoubleTalkRig.sum(near, noise))

        log("== ${scenario.name}, ${chain.label} ==")
        log("phase        mic-in  echo-in  out A   out B   out C   near kept  gate A   first open  floor/thr A (end)")
        var start = 0
        for (phase in Phase.entries) {
            val end = start + phase.seconds * DoubleTalkRig.FRAMES_PER_SECOND
            val pA = mean(a.power, start, end)
            val pB = mean(b.power, start, end)
            val pC = mean(c.power, start, end)
            val kept = if (phase == Phase.FAR_ONLY) Float.NaN else db(max(pA - pB, 1e-3) / max(pC, 1e-3))
            val open = (start until end).count { a.transmit[it] }
            val firstOpen = (start until end).firstOrNull { a.transmit[it] }
                ?.let { (it - start) * DoubleTalkRig.FRAME_MS }
            log(
                String.format(
                    Locale.ROOT,
                    "%-12s %6.1f  %6.1f  %6.1f  %6.1f  %6.1f  %8s  %5.0f %%  %10s  %6.1f/%6.1f",
                    phase.name, dbfs(meanPower(micA, start, end)), dbfs(meanPower(echo, start, end)),
                    dbfs(pA), dbfs(pB), dbfs(pC),
                    if (kept.isNaN()) "-" else String.format(Locale.ROOT, "%+.1f dB", kept),
                    100f * open / (end - start),
                    firstOpen?.let { "$it ms" } ?: "never",
                    a.floor[end - 1], a.threshold[end - 1],
                ),
            )
            start = end
        }
        val voiced = DoubleTalkRig.voicedDoubleTalkFrames(scenario.nearDbfs)
        log(
            String.format(
                Locale.ROOT,
                "voiced near-end frames in double talk: %d; out A %.1f dBFS vs near alone (C) %.1f dBFS; " +
                    "gate open A %.0f %% vs C %.0f %%; frames zeroed in A %d",
                voiced.size, dbfs(voiced.sumOf { a.power[it] } / voiced.size),
                dbfs(voiced.sumOf { c.power[it] } / voiced.size),
                100f * voiced.count { a.transmit[it] } / voiced.size,
                100f * voiced.count { c.transmit[it] } / voiced.size,
                voiced.count { a.power[it] < 1.0 },
            ),
        )
    }

    private fun build(chain: Chain): CaptureChain {
        val factory = CapturePreprocessorFactory(log = { Log.w(DoubleTalkRig.TAG, it) })
        return when (chain) {
            Chain.APP_APM -> factory.create(NoiseSuppressionMode.NONE, EchoCancellationMode.WEBRTC)
            Chain.APP_APM_RNNOISE -> factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.WEBRTC)
            Chain.RNNOISE_ONLY -> factory.create(NoiseSuppressionMode.RNNOISE, EchoCancellationMode.NONE)
            Chain.APM_NO_AGC_RNNOISE -> {
                val config = WebRtcApmConfig.FOR_ECHO_CANCELLATION.copy(gainControl = false)
                val apm = WebRtcApmPreprocessor(WebRtcApmNative, config)
                val stages = listOf(apm, RnnoisePreprocessor(RnnoiseNative, Float.POSITIVE_INFINITY))
                CaptureChain(ChainedPreprocessor(stages), apm, apm.farEndFrameSize)
            }
        }
    }

    /** A tuning of the app chain, or ([chain]) a different chain around the default AEC3. */
    private class Candidate(val name: String, val tuning: Aec3Tuning?, val chain: (() -> CaptureChain)? = null)

    private companion object {
        const val NEAR_DBFS = -26f

        /*
         * Bounds of shippedChainInDoubleTalk, the 18 dB chain, measured on the SM-S938B
         * (2026-09-30) with the reference (synthetic) talkers:
         * - echo alone: 0 % on the linear path; 9.6 / 25.3 / 11.9 / 37.4 % on the nonlinear one
         *   (-20 / -10 / 0 / +6 dB). Without a limit it was 18.6-29.9 % there: on the loud
         *   nonlinear path the synthetic vowels open the limited gate more often. Real speech
         *   (`RealSpeechDeviceTest`) and the real room (`RoomAcousticsDeviceTest`) do not show it,
         *   which is why the owner shipped 18 dB; this bound holds that trade where it was measured.
         * - double-talk gate: 97 / 93 / 92 / 74 % linear, 97 / 80 / 71 / 66 % nonlinear.
         * - against unlimited RNNoise at -20 / 0 dB linear: 97 vs 62 %, 92 vs 26 %; nothing zeroed
         *   (45 and 64 % without the limit).
         */
        const val SHIPPED_MAX_FALSE_OPEN = 0.45f
        const val SHIPPED_MIN_GATE = 0.55f
        const val SHIPPED_MIN_GAIN = 0.25f

        /** Talker pairs the sweep runs; 1 for a quick look, 3 for a decision. */
        const val SWEEP_TALKERS = 1

        val SWEEP_SCENARIOS = EchoPath.entries.flatMap { path ->
            listOf(-20f, -10f, 0f, 6f).map { Scenario(echoGainDb = it, nearDbfs = NEAR_DBFS, path) }
        }

        val D = Aec3Tuning.DEFAULT

        /** Masks keep where suppression starts (enr_transparent) but fall off gradually. */
        val SOFT = D.with(
            NORMAL_LF_ENR_SUPPRESS to 1.5f, NORMAL_HF_ENR_SUPPRESS to 1f,
            NEAREND_LF_ENR_SUPPRESS to 3f, NEAREND_HF_ENR_SUPPRESS to 1.5f,
        )

        /** One mechanism per candidate, then the combinations that looked best, then other chains. */
        val CANDIDATES = listOf(
            Candidate("default", null),
            // Dominant near-end detector: ENR_THRESHOLD is echo/near, so a higher value enters
            // near-end state (transparent masks) more easily; fewer trigger blocks enter it sooner.
            Candidate("dne-eager", D.with(DNE_ENR_THRESHOLD to 1f, DNE_TRIGGER_THRESHOLD to 4f)),
            // Near-end state lets more through (default lf 1.09/1.1, hf 0.1/0.3).
            Candidate(
                "nearend-open",
                D.with(
                    NEAREND_HF_ENR_TRANSPARENT to 0.5f, NEAREND_HF_ENR_SUPPRESS to 1.5f,
                    NEAREND_LF_ENR_TRANSPARENT to 1.5f, NEAREND_LF_ENR_SUPPRESS to 3f,
                ),
            ),
            // Normal state suppresses only when the echo is closer to the near end (default hf 0.07/0.1).
            Candidate(
                "normal-open",
                D.with(
                    NORMAL_HF_ENR_TRANSPARENT to 0.2f, NORMAL_HF_ENR_SUPPRESS to 0.4f,
                    NORMAL_LF_ENR_TRANSPARENT to 0.4f, NORMAL_LF_ENR_SUPPRESS to 0.8f,
                ),
            ),
            // Trust the linear filter more: the residual echo estimate is echo / min(ERLE, cap).
            Candidate("erle-16/8", D.with(ERLE_MAX_L to 16f, ERLE_MAX_H to 8f)),
            // Near end when 1-2 kHz is weaker than 125-375 Hz (a phone speaker has no bass).
            Candidate(
                "subband-lf",
                D.with(
                    USE_SUBBAND_NEAREND_DETECTION to 1f, SUBBAND1_LOW to 8f, SUBBAND1_HIGH to 16f,
                    SUBBAND2_LOW to 1f, SUBBAND2_HIGH to 3f,
                    SUBBAND_NEAREND_THRESHOLD to 1f, SUBBAND_SNR_THRESHOLD to 10f,
                ),
            ),
            // Shorter modelled reverb tail (0.7 per block: -25 dB per 100 ms instead of -8).
            Candidate("len.7", D.with(EP_DEFAULT_LEN to 0.7f)),
            Candidate("len.7+nlen.7", D.with(EP_DEFAULT_LEN to 0.7f, EP_NEAREND_LEN to 0.7f)),
            Candidate("soft-ramp", SOFT),
            Candidate("soft+len.7", SOFT.with(EP_DEFAULT_LEN to 0.7f)),
            // Leave more residual echo per bin before suppressing (a floor under the gain).
            Candidate(
                "floor-10000",
                D.with(AUDIBILITY_NORMAL_RENDER_LIMIT to 10000f, AUDIBILITY_LOW_RENDER_LIMIT to 40000f),
            ),
            Candidate("chain: no RNNoise", null) { DoubleTalkRig.appChain(null, rnnoise = false) },
            Candidate("chain: AEC3>RNN>AGC2", null) {
                val aecOnly = WebRtcApmConfig.FOR_ECHO_CANCELLATION.copy(gainControl = false)
                val aec = WebRtcApmPreprocessor(WebRtcApmNative, aecOnly)
                val agc = WebRtcApmPreprocessor(
                    WebRtcApmNative,
                    WebRtcApmConfig(
                        echoCancellation = false, noiseSuppression = false, gainControl = true, highPass = false,
                    ),
                )
                val stages = listOf(aec, RnnoisePreprocessor(RnnoiseNative, Float.POSITIVE_INFINITY), agc)
                CaptureChain(ChainedPreprocessor(stages), aec, aec.farEndFrameSize)
            },
        )
    }
}
