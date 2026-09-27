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
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * CHARACTERIZATION (double-talk bug, hypothesis H2): does the adaptive gate, fed several seconds of
 * residual echo, still open on near-end speech? Frames are synthetic levels (the gate only measures
 * RMS), 10 ms each, with the app's default adaptive configuration. Prints its tables; asserts only
 * what follows from [AdaptiveVadTracker]'s time constants.
 */
class DoubleTalkVadCharacterizationTest {
    private var nowNanos = 0L

    private fun appDefaultGate() = VoiceActivityDetector(VadConfig.adaptive(onsetFrames = 2)) { nowNanos }

    /** One frame at [dbfs] RMS: a ±A square wave, whose RMS is A. */
    private fun frame(dbfs: Float): ShortArray {
        val a = (FULL_SCALE * 10.0.pow(dbfs / 20.0)).roundToInt().coerceIn(0, Short.MAX_VALUE.toInt())
        return ShortArray(FRAME) { if (it % 2 == 0) a.toShort() else (-a).toShort() }
    }

    private fun VoiceActivityDetector.feed(dbfs: Float): Boolean {
        nowNanos += FRAME_NANOS
        return isVoice(frame(dbfs), FRAME, null)
    }

    private enum class EchoShape { STEADY, SYLLABIC }

    /**
     * 2 s quiet room, [ECHO_SECONDS] of residual echo at [echoDbfs] (far end talking, we are
     * silent), then [NEAR_SECONDS] of near-end syllables at [nearDbfs], with the echo either going on
     * ([echoContinues], double talk) or stopping. [roomDbfs] is the level between syllables; a
     * [zeroedFraction] of each near-end syllable arrives as digital silence (a chain dropout).
     */
    private data class Case(
        val echoDbfs: Float,
        val shape: EchoShape,
        val nearDbfs: Float,
        val echoContinues: Boolean = true,
        val roomDbfs: Float = ROOM_DBFS,
        val zeroedFraction: Float = 0f,
    ) {
        fun echoOn(frame: Int) = shape == EchoShape.STEADY || syllableOn(frame, FAR_SYLLABLE_HZ)
    }

    private class GateState(val floor: Float, val threshold: Float, val speech: Float)

    private class Outcome(
        val echoOpenPercent: Float,
        val afterEcho: GateState,
        val latencyMs: Int?,
        val nearOpenPercent: Float,
    ) {
        val opened: Boolean get() = latencyMs != null
    }

    private fun scenario(case: Case): Outcome {
        nowNanos = 0L
        val gate = appDefaultGate()
        repeat(QUIET_FRAMES) { gate.feed(case.roomDbfs) }
        var echoOpen = 0
        for (f in 0 until ECHO_FRAMES) {
            if (gate.feed(if (case.echoOn(f)) plus(case.echoDbfs, case.roomDbfs) else case.roomDbfs)) echoOpen++
        }
        val afterEcho = GateState(gate.floorDbfs, gate.thresholdDbfs, gate.speechDbfs)
        val (latency, nearOpen) = nearPhase(gate, case)
        return Outcome(100f * echoOpen / ECHO_FRAMES, afterEcho, latency, nearOpen)
    }

    /** @return the first open frame's offset in ms, and the share of syllable frames transmitted. */
    private fun nearPhase(gate: VoiceActivityDetector, case: Case): Pair<Int?, Float> {
        var latency: Int? = null
        var nearOpen = 0
        var nearActive = 0
        for (f in 0 until NEAR_FRAMES) {
            val echoOn = case.echoContinues && case.echoOn(ECHO_FRAMES + f)
            val background = if (echoOn) plus(case.echoDbfs, case.roomDbfs) else case.roomDbfs
            val talking = syllableOn(f, NEAR_SYLLABLE_HZ)
            // A model, not a measurement: the dropout covers the start of each syllable.
            val zeroed = talking && syllablePhase(f, NEAR_SYLLABLE_HZ) < SYLLABLE_ON * case.zeroedFraction
            val level = when {
                zeroed -> DIGITAL_SILENCE_DBFS
                talking -> plus(case.nearDbfs, background)
                else -> background
            }
            val open = gate.feed(level)
            if (talking) nearActive++
            if (talking && open) nearOpen++
            if (open && latency == null) latency = f * FRAME_MS
        }
        return latency to 100f * nearOpen / nearActive
    }

    @Test
    fun `the adaptive gate after seconds of residual echo`() {
        println("H2: app-default adaptive gate (fraction 0.65, hold 250 ms, onset 2), room $ROOM_DBFS dBFS")
        println("echo      shape     near   echo on?  echo-open  floor   thr     speech  | opens  latency  near-open")
        val cases = EchoShape.entries.flatMap { shape ->
            ECHO_LEVELS.flatMap { echo ->
                NEAR_LEVELS.flatMap { near -> listOf(true, false).map { Case(echo, shape, near, echoContinues = it) } }
            }
        }
        for (case in cases) {
            val o = scenario(case)
            println(
                String.format(
                    Locale.ROOT,
                    "%5.0f  %-9s %5.0f   %-8s  %6.0f %%  %6.1f  %6.1f  %6.1f  | %-5s  %7s  %6.0f %%",
                    case.echoDbfs, case.shape, case.nearDbfs, if (case.echoContinues) "yes" else "no",
                    o.echoOpenPercent, o.afterEcho.floor, o.afterEcho.threshold, o.afterEcho.speech,
                    if (o.opened) "yes" else "NO", o.latencyMs?.let { "$it ms" } ?: "-", o.nearOpenPercent,
                ),
            )
        }
    }

    /**
     * The same gate on what the device test measured after the app's chains in double talk: the
     * residual echo (run B), the voiced near-end level (run A) and the share of voiced near-end
     * frames the chain wiped to below one LSB. Between syllables the RNNoise chain emits silence.
     */
    @Test
    fun `the adaptive gate on the near-end levels left after the chain`() {
        println("H2 x H1: near end after the WebRTC chain (levels measured by DoubleTalkAec3DeviceTest)")
        for ((label, case) in MEASURED_AFTER_CHAIN) {
            val o = scenario(case)
            println(
                String.format(
                    Locale.ROOT,
                    "%-26s echo %6.1f near %5.1f zeroed %3.0f %% -> floor %6.1f thr %6.1f echo-open %3.0f %% | " +
                        "opens %-3s %6s, voiced frames open %3.0f %%",
                    label, case.echoDbfs, case.nearDbfs, case.zeroedFraction * 100, o.afterEcho.floor,
                    o.afterEcho.threshold, o.echoOpenPercent, if (o.opened) "yes" else "NO",
                    o.latencyMs?.let { "$it ms" } ?: "-", o.nearOpenPercent,
                ),
            )
        }
    }

    /** Facts of the tracker's time constants, not of the bug. */
    @Test
    fun `a steady sub-threshold echo raises the floor to itself and keeps the margin at least 6 dB`() {
        val o = scenario(Case(echoDbfs = -50f, shape = EchoShape.STEADY, nearDbfs = -20f))
        assertThat(o.echoOpenPercent).isEqualTo(0f)
        // The quiet room pulled the floor to -65; 3 dB/s for 6 s allows 18 dB, the echo needs 15.
        assertThat(o.afterEcho.floor).isWithin(0.2f).of(-50f)
        assertThat(o.afterEcho.threshold - o.afterEcho.floor).isAtLeast(AdaptiveVadTracker.MIN_MARGIN_DB)
    }

    /** Fact: an echo loud enough to cross the fresh threshold opens the gate and holds it open. */
    @Test
    fun `a steady echo above the threshold keeps the gate open and the floor unlearned`() {
        val o = scenario(Case(echoDbfs = -40f, shape = EchoShape.STEADY, nearDbfs = -20f))
        // All but the first frame: onset needs two.
        assertThat(o.echoOpenPercent).isAtLeast(99f)
        assertThat(o.afterEcho.floor).isWithin(0.5f).of(ROOM_DBFS)
    }

    private companion object {
        const val FRAME = 480
        const val FRAME_MS = 10
        const val FRAME_NANOS = FRAME_MS * 1_000_000L
        const val FULL_SCALE = 32768.0
        const val ROOM_DBFS = -65f
        const val DIGITAL_SILENCE_DBFS = -120f
        const val SYLLABLE_ON = 0.7f
        const val QUIET_FRAMES = 200
        const val ECHO_SECONDS = 6
        const val ECHO_FRAMES = ECHO_SECONDS * 100
        const val NEAR_SECONDS = 2
        const val NEAR_FRAMES = NEAR_SECONDS * 100
        const val FAR_SYLLABLE_HZ = 4.1f
        const val NEAR_SYLLABLE_HZ = 3.3f
        val ECHO_LEVELS = listOf(-60f, -50f, -45f, -40f, -35f, -30f)
        val NEAR_LEVELS = listOf(-45f, -40f, -35f, -30f, -25f, -20f, -15f)

        /** Power sum of two levels in dBFS. */
        fun plus(a: Float, b: Float): Float = (10 * log10(10.0.pow(a / 10.0) + 10.0.pow(b / 10.0))).toFloat()

        /** Syllabic on/off at [hz]: on for the first 70 % of each cycle. */
        fun syllableOn(frame: Int, hz: Float): Boolean = syllablePhase(frame, hz) < SYLLABLE_ON

        fun syllablePhase(frame: Int, hz: Float): Float = (frame * FRAME_MS / 1000f * hz) % 1f

        /** After AGC2 the APM output idles near -50 dBFS; after RNNoise it is digital silence. */
        const val APM_ROOM = -50f
        const val RNNOISE_ROOM = -120f

        /** Voiced near-end frames in the device test's double-talk phase. */
        const val VOICED_FRAMES = 179f

        /** One app chain's measured output in double talk, as a gate input. */
        fun measured(echo: Float, near: Float, zeroed: Int = 0, room: Float = APM_ROOM) =
            Case(echo, EchoShape.SYLLABIC, near, roomDbfs = room, zeroedFraction = zeroed / VOICED_FRAMES)

        /**
         * DoubleTalkAec3DeviceTest on the SM-S938B, 2026-09-27, double-talk phase: residual echo
         * (run B), voiced near end (run A), voiced frames below one LSB.
         */
        val MEASURED_AFTER_CHAIN = listOf(
            "APM, echo -20 dB" to measured(echo = -53.8f, near = -16.9f),
            "APM, echo 0 dB" to measured(echo = -61.6f, near = -24.4f),
            "APM, echo +6 dB" to measured(echo = -56.8f, near = -26.5f),
            "APM+RNNoise, echo -20 dB" to measured(echo = -104.6f, near = -26.7f, zeroed = 80, room = RNNOISE_ROOM),
            "APM+RNNoise, echo -10 dB" to measured(echo = -120f, near = -27.9f, zeroed = 106, room = RNNOISE_ROOM),
            "APM+RNNoise, echo 0 dB" to measured(echo = -93.5f, near = -38.0f, zeroed = 121, room = RNNOISE_ROOM),
            "APM+RNNoise, echo +6 dB" to measured(echo = -107.0f, near = -36.8f, zeroed = 126, room = RNNOISE_ROOM),
        )
    }
}
