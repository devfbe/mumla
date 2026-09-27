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

/**
 * Preference values are stable on-disk identifiers. Unknown values fall back to [AMPLITUDE], what
 * older installs ran, so their `detection_threshold` slider keeps working.
 */
public enum class VadMode(public val preferenceValue: String) {
    /** Legacy dBFS level detector; kept for devices where the models fail. */
    AMPLITUDE("amplitude"),

    /** Uses the preprocessor chain's voice probability. */
    PROBABILITY("probability"),

    /** Level detector with a threshold between tracked noise floor and speech peak ([AdaptiveVadTracker]). */
    ADAPTIVE("adaptive");

    public companion object {
        public fun fromPreferenceValue(value: String?): VadMode =
            entries.firstOrNull { it.preferenceValue == value } ?: AMPLITUDE
    }
}

/**
 * Start/stop hysteresis plus hold time. Thresholds are scores in [0, 1]: the amplitude score for
 * [VadMode.AMPLITUDE], the chain's probability for [VadMode.PROBABILITY]; the scales aren't comparable.
 */
public data class VadConfig(
    val mode: VadMode,
    val startThreshold: Float,
    val stopThreshold: Float,
    val holdTimeMs: Long,
    /** [VadMode.ADAPTIVE] only: fraction of the speech-to-floor gap a frame must clear. */
    val snrFraction: Float = AdaptiveVadTracker.DEFAULT_FRACTION,
    /** [VadMode.ADAPTIVE] only: stop threshold below start, in dB (6 dB = one doubling of distance). */
    val hysteresisDb: Float = DEFAULT_HYSTERESIS_DB,
    /** Consecutive frames over the start threshold needed to open the gate (guards against clicks). */
    val onsetFrames: Int = DEFAULT_ONSET_FRAMES,
    /** [VadMode.ADAPTIVE] only: false pins the floor at [manualFloorDbfs] instead of tracking it. */
    val adaptiveFloor: Boolean = true,
    val manualFloorDbfs: Float = AdaptiveVadTracker.DEFAULT_FLOOR_DBFS,
) {
    init {
        require(startThreshold in 0f..1f) { "startThreshold out of range: $startThreshold" }
        require(stopThreshold in 0f..startThreshold) {
            "stopThreshold must be within [0, startThreshold]: $stopThreshold"
        }
        require(holdTimeMs in 0L..MAX_HOLD_MS) { "holdTimeMs must be within [0, $MAX_HOLD_MS]: $holdTimeMs" }
        require(snrFraction in 0f..1f) { "snrFraction out of range: $snrFraction" }
        require(hysteresisDb in 0f..VoiceActivityDetector.SCORE_RANGE_DB.toFloat()) {
            "hysteresisDb out of range: $hysteresisDb"
        }
        require(onsetFrames >= 1) { "onsetFrames must be at least one: $onsetFrames" }
        require(manualFloorDbfs in AdaptiveVadTracker.MIN_FLOOR_DBFS..AdaptiveVadTracker.MAX_FLOOR_DBFS) {
            "manualFloorDbfs out of range: $manualFloorDbfs"
        }
    }

    public companion object {
        internal const val AMPLITUDE_HYSTERESIS: Float = 0.15f
        internal const val DEFAULT_HOLD_MS: Long = 250L
        public const val DEFAULT_HYSTERESIS_DB: Float = 6f

        public const val DEFAULT_ONSET_FRAMES: Int = 1

        /** Largest hold whose `holdTimeMs * 1_000_000` nanosecond deadline doesn't overflow. */
        internal const val MAX_HOLD_MS: Long = Long.MAX_VALUE / 1_000_000L

        public const val DEFAULT_SNR_FRACTION: Float = AdaptiveVadTracker.DEFAULT_FRACTION

        /** The bounds of [manualFloorDbfs], which is also where an adaptive floor stays. */
        public const val MIN_FLOOR_DBFS: Float = AdaptiveVadTracker.MIN_FLOOR_DBFS
        public const val MAX_FLOOR_DBFS: Float = AdaptiveVadTracker.MAX_FLOOR_DBFS
        public const val DEFAULT_FLOOR_DBFS: Float = AdaptiveVadTracker.DEFAULT_FLOOR_DBFS

        /** The level a silent frame is measured at. */
        public const val NO_SIGNAL_DBFS: Float = VoiceActivityDetector.NO_SIGNAL_DBFS

        /** Legacy single slider: stop = start - 0.15. */
        public fun amplitude(
            threshold: Float,
            holdTimeMs: Long = DEFAULT_HOLD_MS,
            onsetFrames: Int = DEFAULT_ONSET_FRAMES,
        ): VadConfig {
            val start = threshold.coerceIn(0f, 1f)
            return VadConfig(
                VadMode.AMPLITUDE, start, (start - AMPLITUDE_HYSTERESIS).coerceIn(0f, start), holdTimeMs,
                onsetFrames = onsetFrames,
            )
        }

        public fun probability(
            start: Float = 0.6f,
            stop: Float = 0.3f,
            holdTimeMs: Long = DEFAULT_HOLD_MS,
            onsetFrames: Int = DEFAULT_ONSET_FRAMES,
        ): VadConfig = VadConfig(VadMode.PROBABILITY, start, stop, holdTimeMs, onsetFrames = onsetFrames)

        public fun adaptive(
            snrFraction: Float = AdaptiveVadTracker.DEFAULT_FRACTION,
            holdTimeMs: Long = DEFAULT_HOLD_MS,
            onsetFrames: Int = DEFAULT_ONSET_FRAMES,
            hysteresisDb: Float = DEFAULT_HYSTERESIS_DB,
            adaptiveFloor: Boolean = true,
            manualFloorDbfs: Float = AdaptiveVadTracker.DEFAULT_FLOOR_DBFS,
        ): VadConfig = VadConfig(
            VadMode.ADAPTIVE, 0f, 0f, holdTimeMs,
            snrFraction = snrFraction,
            hysteresisDb = hysteresisDb,
            onsetFrames = onsetFrames,
            adaptiveFloor = adaptiveFloor,
            manualFloorDbfs = manualFloorDbfs,
        )

        public val DEFAULT: VadConfig = probability()
    }
}
