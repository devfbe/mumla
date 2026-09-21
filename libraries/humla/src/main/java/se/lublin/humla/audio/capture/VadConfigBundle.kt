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

import android.os.Bundle

/**
 * Carries a whole [VadConfig] through `HumlaService.configureExtras`, which speaks `Bundle`.
 *
 * It exists so that a settings change is **one** extra rather than one extra per slider. Every
 * extra that reaches `configureExtras` used to rebuild the audio chain; nine separate keys for one
 * screen would be nine rebuilds, which is the defect the quick-access menu shipped with, multiplied.
 *
 * Reading is total: a missing key is that field's default and an out-of-range value is clamped,
 * because [VadConfig]'s constructor throws and the caller is a `Service` handling a settings write.
 */
object VadConfigBundle {
    private const val MODE = "vad_mode"
    private const val START = "vad_start"
    private const val STOP = "vad_stop"
    private const val HOLD = "vad_hold"
    private const val FRACTION = "vad_fraction"
    private const val HYSTERESIS = "vad_hysteresis_db"
    private const val ONSET = "vad_onset"
    private const val ADAPTIVE_FLOOR = "vad_adaptive_floor"
    private const val MANUAL_FLOOR = "vad_manual_floor_dbfs"

    /** The property names this codec carries, so a new field on [VadConfig] fails a test here. */
    @JvmField
    val CARRIED_FIELDS: List<String> = listOf(
        "mode", "startThreshold", "stopThreshold", "holdTimeMs",
        "snrFraction", "hysteresisDb", "onsetFrames", "adaptiveFloor", "manualFloorDbfs",
    )

    @JvmStatic
    fun toBundle(config: VadConfig): Bundle = Bundle().apply {
        putString(MODE, config.mode.preferenceValue)
        putFloat(START, config.startThreshold)
        putFloat(STOP, config.stopThreshold)
        putLong(HOLD, config.holdTimeMs)
        putFloat(FRACTION, config.snrFraction)
        putFloat(HYSTERESIS, config.hysteresisDb)
        putInt(ONSET, config.onsetFrames)
        putBoolean(ADAPTIVE_FLOOR, config.adaptiveFloor)
        putFloat(MANUAL_FLOOR, config.manualFloorDbfs)
    }

    @JvmStatic
    fun fromBundle(bundle: Bundle): VadConfig {
        val default = VadConfig.DEFAULT
        val start = bundle.getFloat(START, default.startThreshold).coerceIn(0f, 1f)
        return VadConfig(
            mode = VadMode.fromPreferenceValue(bundle.getString(MODE, default.mode.preferenceValue)),
            startThreshold = start,
            stopThreshold = bundle.getFloat(STOP, default.stopThreshold).coerceIn(0f, start),
            holdTimeMs = bundle.getLong(HOLD, default.holdTimeMs).coerceIn(0L, VadConfig.MAX_HOLD_MS),
            snrFraction = bundle.getFloat(FRACTION, default.snrFraction).coerceIn(0f, 1f),
            hysteresisDb = bundle.getFloat(HYSTERESIS, default.hysteresisDb).coerceIn(0f, 96f),
            onsetFrames = bundle.getInt(ONSET, default.onsetFrames).coerceAtLeast(1),
            adaptiveFloor = bundle.getBoolean(ADAPTIVE_FLOOR, default.adaptiveFloor),
            manualFloorDbfs = bundle.getFloat(MANUAL_FLOOR, default.manualFloorDbfs)
                .coerceIn(AdaptiveVadTracker.MIN_FLOOR_DBFS, AdaptiveVadTracker.MAX_FLOOR_DBFS),
        )
    }
}
