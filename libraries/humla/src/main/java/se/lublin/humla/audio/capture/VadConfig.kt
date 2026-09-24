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
 * Preference values are stable on-disk identifiers (see app Settings.kt). Unknown or missing values
 * fall back to [AMPLITUDE], what older installs were running: the `detection_threshold` slider is a
 * no-op in [PROBABILITY] mode, so any other fallback would silently disable it.
 */
enum class VadMode(val preferenceValue: String) {
    /** Legacy dBFS level detector; kept for devices where the models fail. */
    AMPLITUDE("amplitude"),

    /** Uses the preprocessor chain's voice probability. */
    PROBABILITY("probability"),

    /**
     * Level detector with a threshold between a tracked noise floor and speech peak (see
     * [AdaptiveVadTracker]). Recommended: robust to talker distance and independent of the chain
     * configuration, since it measures the chain's own output.
     */
    ADAPTIVE("adaptive");

    companion object {
        @JvmStatic
        fun fromPreferenceValue(value: String?): VadMode =
            entries.firstOrNull { it.preferenceValue == value } ?: AMPLITUDE
    }
}

/**
 * Start/stop hysteresis plus hold time. Thresholds are scores in [0, 1] whose meaning depends on
 * [mode]: [VoiceActivityDetector.amplitudeScore] for [VadMode.AMPLITUDE], the chain's last opinion
 * for [VadMode.PROBABILITY] (a speech probability, or [LevelToProbability]'s loudness mapping when
 * only the APM has an opinion). The scales are not comparable, hence separate settings.
 */
data class VadConfig(
    val mode: VadMode,
    val startThreshold: Float,
    val stopThreshold: Float,
    val holdTimeMs: Long,
    /** [VadMode.ADAPTIVE] only: fraction of the speech-to-floor gap a frame must clear. */
    val snrFraction: Float = AdaptiveVadTracker.DEFAULT_FRACTION,
    /**
     * [VadMode.ADAPTIVE] only: how far below the start threshold the stop threshold sits, in dB. 6 dB
     * is one doubling of distance, so stepping away mid-sentence keeps the gate open.
     */
    val hysteresisDb: Float = DEFAULT_HYSTERESIS_DB,
    /**
     * Consecutive frames over the start threshold needed to open the gate: the transient guard (e.g.
     * a single keyboard click; the WebRTC transient suppressor is not built). Costs
     * `(onsetFrames - 1) * 10 ms` at the start of a word.
     */
    val onsetFrames: Int = DEFAULT_ONSET_FRAMES,
    /** [VadMode.ADAPTIVE] only: false pins the floor at [manualFloorDbfs] instead of tracking it. */
    val adaptiveFloor: Boolean = true,
    /** [VadMode.ADAPTIVE] only: the floor to use while [adaptiveFloor] is false. */
    val manualFloorDbfs: Float = AdaptiveVadTracker.DEFAULT_FLOOR_DBFS,
) {
    init {
        require(startThreshold in 0f..1f) { "startThreshold out of range: $startThreshold" }
        require(stopThreshold in 0f..startThreshold) { "stopThreshold must be within [0, startThreshold]: $stopThreshold" }
        require(holdTimeMs in 0L..MAX_HOLD_MS) { "holdTimeMs must be within [0, $MAX_HOLD_MS]: $holdTimeMs" }
        require(snrFraction in 0f..1f) { "snrFraction out of range: $snrFraction" }
        require(hysteresisDb in 0f..96f) { "hysteresisDb out of range: $hysteresisDb" }
        require(onsetFrames >= 1) { "onsetFrames must be at least one: $onsetFrames" }
        require(manualFloorDbfs in AdaptiveVadTracker.MIN_FLOOR_DBFS..AdaptiveVadTracker.MAX_FLOOR_DBFS) {
            "manualFloorDbfs out of range: $manualFloorDbfs"
        }
    }

    companion object {
        const val AMPLITUDE_HYSTERESIS = 0.15f
        const val DEFAULT_HOLD_MS = 250L
        const val DEFAULT_HYSTERESIS_DB = 6f

        /**
         * Library default: the first frame over the threshold opens the gate. The app sets its own
         * user-facing default (`Settings.DEFAULT_VAD_ONSET_FRAMES`).
         */
        const val DEFAULT_ONSET_FRAMES = 1

        /** Largest hold whose `holdTimeMs * 1_000_000` nanosecond deadline doesn't overflow. */
        const val MAX_HOLD_MS = Long.MAX_VALUE / 1_000_000L

        /** Legacy single slider: stop = start - 0.15. */
        @JvmStatic
        @JvmOverloads
        fun amplitude(
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

        @JvmStatic
        @JvmOverloads
        fun probability(
            start: Float = 0.6f,
            stop: Float = 0.3f,
            holdTimeMs: Long = DEFAULT_HOLD_MS,
            onsetFrames: Int = DEFAULT_ONSET_FRAMES,
        ): VadConfig = VadConfig(VadMode.PROBABILITY, start, stop, holdTimeMs, onsetFrames = onsetFrames)

        @JvmStatic
        @JvmOverloads
        fun adaptive(
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

        @JvmField
        val DEFAULT: VadConfig = probability()
    }
}
