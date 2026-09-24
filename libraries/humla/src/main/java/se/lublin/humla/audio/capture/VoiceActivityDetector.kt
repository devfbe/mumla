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

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A monotonic nanosecond clock, injectable for tests. A `fun interface` rather than `() -> Long`
 * because `Function0<Long>.invoke()` boxes the result on every audio frame.
 */
fun interface NanoClock {
    fun nanoTime(): Long
}

/**
 * Decides per frame whether the user is talking: start/stop hysteresis over a score, plus a hold
 * that keeps transmission open across gaps inside a word.
 *
 * Call [isVoice] from the capture thread only; [config] may be written from another thread.
 *
 * [clock] must be monotonic but may have any origin (including near `Long.MAX_VALUE`, as
 * `System.nanoTime` permits), hence the hold is a deadline compared via `now - deadline < 0`.
 */
class VoiceActivityDetector(
    config: VadConfig,
    /**
     * Frame duration in ms (10 ms in the pipeline); a parameter so previews at other rates don't
     * mis-scale [AdaptiveVadTracker]'s time constants. Placed before [clock] so a trailing lambda
     * still binds to the clock.
     */
    private val frameMs: Float = DEFAULT_FRAME_MS,
    private val clock: NanoClock = NanoClock(System::nanoTime),
) {
    @Volatile
    var config: VadConfig = config

    /** Written by the capture thread, read by the level meter on the main thread. */
    @Volatile
    private var talking = false

    /** Whether the gate is currently open, for the level meter. */
    val isTalking: Boolean get() = talking

    /** Set by [recalibrate] on any thread, acted on by the capture thread at the next frame. */
    @Volatile
    private var recalibrateRequested = false

    /** Consecutive frames over the threshold; see [VadConfig.onsetFrames]. */
    private var consecutive = 0

    private val tracker = AdaptiveVadTracker(
        initialFloorDbfs =
            if (config.adaptiveFloor) AdaptiveVadTracker.DEFAULT_FLOOR_DBFS else config.manualFloorDbfs,
    )

    /**
     * This frame's level in dBFS, for the level meter. Derived from the same [amplitudeScore] the
     * gate uses, so meter and gate cannot disagree.
     */
    @Volatile
    var lastLevelDbfs: Float = NO_SIGNAL_DBFS
        private set

    /** The tracked noise floor, for the meter's lower mark. */
    val floorDbfs: Float get() = tracker.floorDbfs

    /** The tracked speech peak, for the meter's upper mark. */
    val speechDbfs: Float get() = tracker.speechDbfs

    /** Where the gate opens, for the meter's threshold mark. */
    val thresholdDbfs: Float get() = tracker.thresholdDbfs(config.snrFraction)

    /** True while the talker and the room are too close together for the gate to do its job. */
    val tooClose: Boolean get() = tracker.tooClose

    /**
     * The user's "measure again": forgets both estimates. Safe from any thread; the capture thread
     * (which owns the tracker) performs the reset on the next frame.
     */
    fun recalibrate() {
        recalibrateRequested = true
    }

    /** When the hold expires; seeded from the clock so the first frame starts with the hold over. */
    private var holdUntilNanos: Long = clock.nanoTime()

    /**
     * @param pcm the (already preprocessed) frame
     * @param probability the preprocessor chain's probability for this frame, or null if none; null
     *   falls back to the frame's level rather than zero, so a chain without an opinion doesn't mute.
     */
    fun isVoice(pcm: ShortArray, length: Int, probability: Float?): Boolean {
        val c = config
        if (recalibrateRequested) {
            recalibrateRequested = false
            tracker.reset(if (c.adaptiveFloor) AdaptiveVadTracker.DEFAULT_FLOOR_DBFS else c.manualFloorDbfs)
        }
        val level = amplitudeScore(pcm, length)
        val levelDbfs = scoreToDbfs(level)
        lastLevelDbfs = levelDbfs
        if (!c.adaptiveFloor) tracker.setFloor(c.manualFloorDbfs)
        val detected = when (c.mode) {
            VadMode.AMPLITUDE -> level >= (if (talking) c.stopThreshold else c.startThreshold)
            VadMode.PROBABILITY ->
                (probability ?: level) >= (if (talking) c.stopThreshold else c.startThreshold)
            VadMode.ADAPTIVE -> {
                val start = tracker.thresholdDbfs(c.snrFraction)
                levelDbfs >= (if (talking) start - c.hysteresisDb else start)
            }
        }
        // Onset is required only while the gate is shut; otherwise syllables after a gap get clipped.
        consecutive = if (detected) consecutive + 1 else 0
        val accepted = detected && (talking || consecutive >= c.onsetFrames)
        val now = clock.nanoTime()
        if (accepted) holdUntilNanos = now + c.holdTimeMs * 1_000_000L
        talking = accepted || now - holdUntilNanos < 0L
        // After the decision, so this frame was judged against the previous frames' threshold.
        tracker.update(levelDbfs, talking, frameMs, learnFloor = c.adaptiveFloor)
        return talking
    }

    companion object {
        /**
         * Score curve `1 + 20*log10(rms/32768)/96`: full scale is 1.0, -96 dBFS is 0.0, digital
         * silence slightly negative (the `+1` keeps the log finite). Kept unchanged because the
         * user's `detection_threshold` slider is calibrated against it. An empty frame returns
         * [NO_SIGNAL] (the raw formula would give +infinity).
         */
        fun amplitudeScore(pcm: ShortArray, length: Int): Float {
            if (length <= 0) return NO_SIGNAL
            var sum = 1.0
            for (i in 0 until length) sum += pcm[i].toDouble() * pcm[i].toDouble()
            val rms = sqrt(sum / length)
            return (1.0 + 20.0 * log10(rms / 32768.0) / 96.0).toFloat()
        }

        /** [amplitudeScore] of an empty frame: below every [0, 1] threshold, and finite for the meter. */
        const val NO_SIGNAL = -1f

        /** [amplitudeScore] converted to dBFS, the scale of [VadMode.ADAPTIVE] and the level meter. */
        @JvmStatic
        fun levelDbfs(pcm: ShortArray, length: Int): Float = scoreToDbfs(amplitudeScore(pcm, length))

        private fun scoreToDbfs(score: Float): Float = (score - 1f) * 96f

        /** [NO_SIGNAL] in dBFS (-192): finite and below [AdaptiveVadTracker.MIN_FLOOR_DBFS]. */
        const val NO_SIGNAL_DBFS = (NO_SIGNAL - 1f) * 96f

        /** Ten milliseconds: `AudioHandler.FRAME_SIZE` samples at `AudioHandler.SAMPLE_RATE`. */
        const val DEFAULT_FRAME_MS = 10f
    }
}
