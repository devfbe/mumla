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
 * The `webrtc::EchoCanceller3Config` fields the native glue can set, with webrtc's defaults.
 *
 * Names and order are the `HUMLA_AEC3_*` enum in `webrtc_apm/humla_apm.h` (the ordinal is the
 * index; `Aec3ParamHeaderTest` pins both). Integer fields are rounded, booleans are 0 or 1.
 *
 * AEC3 works on 64-sample blocks (4 ms) of the 0-8 kHz band at 16 kHz, in 65 frequency bins of
 * 125 Hz. "ENR" is the echo-to-near-end power ratio per bin, "EMR" echo-to-masker (noise) ratio.
 */
@Suppress("MagicNumber")
internal enum class Aec3Param(val default: Float) {
    // suppressor.normal_tuning: the suppressor while no dominant near end is detected. A bin passes
    // unchanged while ENR <= enr_transparent (or EMR <= emr_transparent), is fully suppressed at
    // enr_suppress, and in between the gain falls linearly. "lf" applies up to LAST_LF_BAND, "hf"
    // from FIRST_HF_BAND up, interpolated between.
    NORMAL_LF_ENR_TRANSPARENT(0.3f),
    NORMAL_LF_ENR_SUPPRESS(0.4f),
    NORMAL_LF_EMR_TRANSPARENT(0.3f),
    NORMAL_HF_ENR_TRANSPARENT(0.07f),
    NORMAL_HF_ENR_SUPPRESS(0.1f),
    NORMAL_HF_EMR_TRANSPARENT(0.3f),

    /** How fast a bin's gain may rise per block (factor). */
    NORMAL_MAX_INC_FACTOR(2.0f),

    /** How fast a low-band gain may fall per block after near-end speech (factor). */
    NORMAL_MAX_DEC_FACTOR_LF(0.25f),

    // suppressor.nearend_tuning: the same while the near end is detected as dominant.
    NEAREND_LF_ENR_TRANSPARENT(1.09f),
    NEAREND_LF_ENR_SUPPRESS(1.1f),
    NEAREND_LF_EMR_TRANSPARENT(0.3f),
    NEAREND_HF_ENR_TRANSPARENT(0.1f),
    NEAREND_HF_ENR_SUPPRESS(0.3f),
    NEAREND_HF_EMR_TRANSPARENT(0.3f),
    NEAREND_MAX_INC_FACTOR(2.0f),
    NEAREND_MAX_DEC_FACTOR_LF(0.25f),

    /** Blocks the near-end spectrum is averaged over before the gain is computed. */
    NEAREND_AVERAGE_BLOCKS(4f),

    /** Last bin using the "lf" masks (5: up to 625 Hz). */
    LAST_LF_BAND(5f),

    /** First bin using the "hf" masks (8: from 1 kHz). */
    FIRST_HF_BAND(8f),

    /** The lowest gain a bin may rise from in one step. */
    FLOOR_FIRST_INCREASE(0.00001f),

    /** Conservative high-frequency gains even in near-end state. */
    CONSERVATIVE_HF_SUPPRESSION(0f),

    // suppressor.dominant_nearend_detection, on the 125-2000 Hz energy per block: the near end is
    // dominant after TRIGGER_THRESHOLD blocks with residual echo < ENR_THRESHOLD * near end and near
    // end > SNR_THRESHOLD * noise; it stays so for HOLD_DURATION blocks, and leaves at once when the
    // echo exceeds ENR_EXIT_THRESHOLD * near end. A higher ENR_THRESHOLD enters it more easily.
    DNE_ENR_THRESHOLD(0.25f),
    DNE_ENR_EXIT_THRESHOLD(10f),
    DNE_SNR_THRESHOLD(30f),
    DNE_HOLD_DURATION(50f),
    DNE_TRIGGER_THRESHOLD(12f),
    DNE_USE_DURING_INITIAL_PHASE(1f),

    /** Detect against the residual echo estimate before it is bounded by the capture power. */
    DNE_USE_UNBOUNDED_ECHO_SPECTRUM(1f),

    // Replaces the detector above with one that ignores the echo estimate: near end when the
    // power of SUBBAND1 < NEAREND_THRESHOLD * power of SUBBAND2 and SUBBAND1 > SNR_THRESHOLD *
    // noise. A spectral-shape heuristic for a known loudspeaker; the defaults never trigger.
    USE_SUBBAND_NEAREND_DETECTION(0f),
    SUBBAND_NEAREND_AVERAGE_BLOCKS(1f),
    SUBBAND1_LOW(1f),
    SUBBAND1_HIGH(1f),
    SUBBAND2_LOW(1f),
    SUBBAND2_HIGH(1f),
    SUBBAND_NEAREND_THRESHOLD(1f),
    SUBBAND_SNR_THRESHOLD(1f),

    // suppressor.high_bands_suppression: the single gain above 8 kHz (48 kHz capture only).
    HIGH_BANDS_ENR_THRESHOLD(1f),
    HIGH_BANDS_MAX_GAIN_DURING_ECHO(1f),
    ANTI_HOWLING_ACTIVATION_THRESHOLD(400f),
    ANTI_HOWLING_GAIN(1f),

    // ep_strength: the echo path model behind the residual echo estimate.
    /** Echo path gain assumed where the linear filter is not trusted. */
    EP_DEFAULT_GAIN(1f),

    /** Reverb tail decay per block, normal state (0.83: about 18 dB per 100 ms). */
    EP_DEFAULT_LEN(0.83f),

    /** Reverb tail decay per block in near-end state. */
    EP_NEAREND_LEN(0.83f),

    /** Assume the echo can saturate the microphone (then suppress everything while it might). */
    EP_ECHO_CAN_SATURATE(1f),

    /** Bound the echo return loss estimate, i.e. do not trust very quiet echo paths. */
    EP_BOUNDED_ERL(0f),
    EP_ERLE_ONSET_COMPENSATION_IN_DOMINANT_NEAREND(0f),
    EP_USE_CONSERVATIVE_TAIL_FREQUENCY_RESPONSE(1f),

    // erle: bounds on how much the linear filter is believed to remove (power ratio); a higher
    // maximum lowers the residual echo estimate and so the suppression.
    ERLE_MIN(1f),
    ERLE_MAX_L(4f),
    ERLE_MAX_H(1.5f),
    ERLE_ONSET_DETECTION(1f),
    ERLE_NUM_SECTIONS(1f),

    // echo_audibility: residual echo below these powers counts as inaudible and is not suppressed.
    AUDIBILITY_LOW_RENDER_LIMIT(256f),
    AUDIBILITY_NORMAL_RENDER_LIMIT(64f),
    AUDIBILITY_FLOOR_POWER(128f),
    AUDIBILITY_THRESHOLD_LF(10f),
    AUDIBILITY_THRESHOLD_MF(10f),
    AUDIBILITY_THRESHOLD_HF(10f),
    AUDIBILITY_USE_STATIONARITY_PROPERTIES(0f),
    AUDIBILITY_USE_STATIONARITY_PROPERTIES_AT_INIT(0f),

    // filter: linear filter lengths in blocks (13: 52 ms of echo tail after the delay). Longer
    // filters model longer rooms but cost CPU in proportion and converge more slowly.
    FILTER_REFINED_LENGTH_BLOCKS(13f),
    FILTER_COARSE_LENGTH_BLOCKS(13f),
    FILTER_REFINED_INITIAL_LENGTH_BLOCKS(12f),
    FILTER_COARSE_INITIAL_LENGTH_BLOCKS(12f),

    /** Level of the comfort noise AEC3 fills suppressed bins with. */
    COMFORT_NOISE_FLOOR_DBFS(-96.03406f),
}

/**
 * A complete AEC3 configuration as [Aec3Param] values; see [WebRtcApmConfig.aec3]. [DEFAULT] is
 * webrtc's own configuration, and handing it to the APM gives the same canceller as handing none
 * (the device test `DoubleTalkAec3DeviceTest` checks this bit for bit).
 */
internal class Aec3Tuning private constructor(private val values: FloatArray) {

    operator fun get(param: Aec3Param): Float = values[param.ordinal]

    /** A copy with [changes] applied. */
    fun with(vararg changes: Pair<Aec3Param, Float>): Aec3Tuning {
        val copy = values.copyOf()
        for ((param, value) in changes) copy[param.ordinal] = value
        return Aec3Tuning(copy)
    }

    /** The values in [Aec3Param] order, as `humla_apm_create` reads them. */
    fun toArray(): FloatArray = values.copyOf()

    override fun equals(other: Any?): Boolean = other is Aec3Tuning && values.contentEquals(other.values)

    override fun hashCode(): Int = values.contentHashCode()

    /** Only what differs from webrtc's defaults. */
    override fun toString(): String = Aec3Param.entries
        .filter { values[it.ordinal] != it.default }
        .joinToString(prefix = "Aec3Tuning(", postfix = ")") { "${it.name}=${values[it.ordinal]}" }

    companion object {
        val DEFAULT = Aec3Tuning(FloatArray(Aec3Param.entries.size) { Aec3Param.entries[it].default })

        /** Reads what [toArray] wrote; the size must match [Aec3Param]. */
        fun fromArray(values: FloatArray): Aec3Tuning {
            require(values.size == Aec3Param.entries.size) {
                "an AEC3 tuning has ${Aec3Param.entries.size} values, not ${values.size}"
            }
            return Aec3Tuning(values.copyOf())
        }
    }
}
