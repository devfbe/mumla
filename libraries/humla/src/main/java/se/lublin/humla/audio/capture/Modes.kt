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
 * fall back to [SPEEX], the suppressor older installs were already running.
 */
enum class NoiseSuppressionMode(val preferenceValue: String) {
    NONE("none"),
    SPEEX("speex"),
    RNNOISE("rnnoise");

    companion object {
        @JvmStatic
        fun fromPreferenceValue(value: String?): NoiseSuppressionMode =
            entries.firstOrNull { it.preferenceValue == value } ?: SPEEX
    }
}

/**
 * Which canceller runs: WebRTC's AEC3 or none. The platform `AcousticEchoCanceler` is not offered: in
 * front of AEC3 it would hand it an already-altered echo. Unknown values read as [NONE].
 */
enum class EchoCancellationMode(val preferenceValue: String) {
    NONE("none"),
    WEBRTC("webrtc");

    companion object {
        @JvmStatic
        fun fromPreferenceValue(value: String?): EchoCancellationMode =
            entries.firstOrNull { it.preferenceValue == value } ?: NONE
    }
}

/** android.media.audiofx effects attached to the AudioRecord session. */
data class AndroidAudioEffects(
    val noiseSuppressor: Boolean = false,
    val automaticGainControl: Boolean = false,
) {
    val any: Boolean get() = noiseSuppressor || automaticGainControl
}
