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
 * Preference values are stable on-disk identifiers (see app Settings.kt), as in [NoiseSuppressionMode] and
 * [EchoCancellationMode], and the fallback follows the same rule they do: **what the installation is already
 * running**.
 *
 * There is no on-disk value for this setting yet. Today every installation runs the amplitude
 * detector, driven by the `detection_threshold` slider that reaches
 * [se.lublin.humla.audio.inputmode.ActivityInputMode.setThreshold] from `HumlaService:547` -- and
 * that call is deliberately a no-op in [PROBABILITY] mode, because a slider calibrated in level
 * units means nothing against a model's probability. So a fallback of [PROBABILITY] would take
 * the slider away from everyone who has never seen the new key, silently: one more switch that
 * does not do what it says, which is the failure class this project exists to remove.
 *
 * **This overrides the plan, which specified a [PROBABILITY] fallback.** Task 12 owns the
 * preference and can make [PROBABILITY] the recommended choice in the UI -- by writing the key,
 * which is the difference between a default and a silent migration.
 */
enum class VadMode(val preferenceValue: String) {
    /** Legacy dBFS level detector; kept for devices where the models fail. */
    AMPLITUDE("amplitude"),

    /** Uses the preprocessor chain's voice probability. */
    PROBABILITY("probability");

    companion object {
        @JvmStatic
        fun fromPreferenceValue(value: String?): VadMode =
            entries.firstOrNull { it.preferenceValue == value } ?: AMPLITUDE
    }
}

/**
 * Start/stop hysteresis plus hold time (spec B5). Thresholds are scores in [0, 1].
 *
 * **What a threshold means depends on [mode], and the two scales are not comparable.** In
 * [VadMode.AMPLITUDE] the score is [VoiceActivityDetector.amplitudeScore], i.e. the legacy dBFS
 * curve; in [VadMode.PROBABILITY] it is whatever the last stage with an opinion returned --
 * RNNoise's or Speex's speech probability, or, with noise suppression NONE and echo cancellation
 * WEBRTC, [LevelToProbability], which is a loudness threshold wearing a probability's type. So a
 * number that is right in one configuration is not right in another, which is why the slider and
 * the probability thresholds are separate settings rather than one.
 */
data class VadConfig(
    val mode: VadMode,
    val startThreshold: Float,
    val stopThreshold: Float,
    val holdTimeMs: Long,
) {
    init {
        require(startThreshold in 0f..1f) { "startThreshold out of range: $startThreshold" }
        require(stopThreshold in 0f..startThreshold) { "stopThreshold must be within [0, startThreshold]: $stopThreshold" }
        require(holdTimeMs in 0L..MAX_HOLD_MS) { "holdTimeMs must be within [0, $MAX_HOLD_MS]: $holdTimeMs" }
    }

    companion object {
        const val AMPLITUDE_HYSTERESIS = 0.15f
        const val DEFAULT_HOLD_MS = 250L

        /**
         * The largest hold [VoiceActivityDetector] can convert: it computes its deadline as
         * `holdTimeMs * 1_000_000`, and above this the product wraps negative, the deadline lands
         * in the past and the hold silently stops holding. It is also exactly the precondition of
         * the `now - deadline < 0` idiom the detector uses, so the two bounds are the same bound.
         */
        const val MAX_HOLD_MS = Long.MAX_VALUE / 1_000_000L

        /** Legacy single slider: stop = start - 0.15. */
        @JvmStatic
        fun amplitude(threshold: Float, holdTimeMs: Long = DEFAULT_HOLD_MS): VadConfig {
            val start = threshold.coerceIn(0f, 1f)
            return VadConfig(VadMode.AMPLITUDE, start, (start - AMPLITUDE_HYSTERESIS).coerceIn(0f, start), holdTimeMs)
        }

        @JvmStatic
        fun probability(start: Float = 0.6f, stop: Float = 0.3f, holdTimeMs: Long = DEFAULT_HOLD_MS): VadConfig =
            VadConfig(VadMode.PROBABILITY, start, stop, holdTimeMs)

        @JvmField
        val DEFAULT: VadConfig = probability()
    }
}
