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
    PROBABILITY("probability"),

    /**
     * Level detector whose threshold is placed between a tracked noise floor and a tracked speech
     * peak (see [AdaptiveVadTracker]). The recommended mode: it is the only one whose calibration
     * survives the user changing his distance to the phone, and the only one that means the same
     * thing in every noise-suppression and echo-cancellation configuration, because it measures
     * the chain's own output rather than assuming a level for it.
     */
    ADAPTIVE("adaptive");

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
    /**
     * [VadMode.ADAPTIVE] only: what fraction of the measured speech-to-floor gap a frame has to
     * clear. Not a loudness -- see [AdaptiveVadTracker]. The default reproduces the 13.0 dB demand
     * spec 4.1 adopted for the fixed window, at the gap a fresh tracker assumes.
     */
    val snrFraction: Float = AdaptiveVadTracker.DEFAULT_FRACTION,
    /**
     * [VadMode.ADAPTIVE] only: how far below the start threshold the stop threshold sits.
     *
     * 6 dB, which is one doubling of the distance to the phone: a talker who steps away in the
     * middle of a sentence keeps the gate open. The legacy amplitude mode's [AMPLITUDE_HYSTERESIS]
     * of 0.15 is the same quantity on the 96 dB score scale, i.e. **14.4 dB**, which is wide
     * enough that the gate stays open through a pause the hold was supposed to cover.
     */
    val hysteresisDb: Float = DEFAULT_HYSTERESIS_DB,
    /**
     * How many consecutive frames over the start threshold it takes to open the gate. This is the
     * transient guard, and it is the only lever left for it: the WebRTC transient suppressor is
     * **not in the build** (`humla_apm.cpp` configures high-pass, AEC3, NS and AGC2 and nothing
     * else), which is why a keyboard click that lands after a typing pause goes out today while
     * continuous typing does not -- the denoiser adapts to the steady noise and cannot adapt to
     * one impulse. A click is one frame; a syllable is not. The price is exactly
     * `(onsetFrames - 1) * 10 ms` of the start of a word at 48 kHz, pinned by a test that counts
     * frames rather than claimed here.
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
         * One, i.e. **today's behaviour**: the first frame over the threshold opens the gate.
         *
         * The transient guard is not defaulted on here on purpose. A library default that changes
         * what every existing caller does is the silent migration this project keeps refusing; the
         * user-facing default lives one layer up, in the app's `Settings.DEFAULT_VAD_ONSET_FRAMES`,
         * which is **2**, next to the settings screen that explains it. Same split as the echo
         * cancellation method, whose on-disk default is the app's and whose fallback is the enum's.
         */
        const val DEFAULT_ONSET_FRAMES = 1

        /**
         * The largest hold [VoiceActivityDetector] can convert: it computes its deadline as
         * `holdTimeMs * 1_000_000`, and above this the product wraps negative, the deadline lands
         * in the past and the hold silently stops holding. It is also exactly the precondition of
         * the `now - deadline < 0` idiom the detector uses, so the two bounds are the same bound.
         */
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
