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
 * Preference values are stable on-disk identifiers (see app Settings.kt).
 *
 * There is no on-disk value for this setting yet: today's preference is the boolean
 * `preprocessor_enabled`, default true (`Settings.kt:288-289`,
 * `res/xml/settings_audio.xml:139-143`). [SPEEX] is the fallback for that reason -- an
 * installation that has never seen the new key keeps the noise suppressor it has been running
 * all along.
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
 * "system" is the legacy on-disk value of the android.media.audiofx.AcousticEchoCanceler option
 * and stays the canonical one (existing preferences hold it -- `echo_cancellation_method` with the
 * values `none`/`system` from `res/values/preference_notranslate.xml:95-98`, default `none`);
 * "android" is accepted as an alias because spec §4 spells the `EXTRAS_ECHO_CANCELLATION` values
 * "none"/"android"/"webrtc".
 *
 * The default is [NONE] rather than a canceller, matching today's `DEFAULT_ECHO_CANCELLATION_METHOD`
 * (`Settings.kt:292`): echo cancellation costs battery and can hurt on a headset, so an upgrade
 * must not silently switch it on.
 */
enum class EchoCancellationMode(val preferenceValue: String) {
    NONE("none"),
    ANDROID("system"),
    WEBRTC("webrtc");

    companion object {
        private const val ANDROID_ALIAS = "android"

        @JvmStatic
        fun fromPreferenceValue(value: String?): EchoCancellationMode = when (value) {
            ANDROID_ALIAS -> ANDROID
            else -> entries.firstOrNull { it.preferenceValue == value } ?: NONE
        }
    }
}

/** android.media.audiofx effects attached to the AudioRecord session (spec B6). */
data class AndroidAudioEffects(
    val noiseSuppressor: Boolean = false,
    val automaticGainControl: Boolean = false,
) {
    val any: Boolean get() = noiseSuppressor || automaticGainControl
}
