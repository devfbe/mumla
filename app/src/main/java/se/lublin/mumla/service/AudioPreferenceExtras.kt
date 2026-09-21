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

package se.lublin.mumla.service

import android.media.AudioManager
import android.os.Bundle
import se.lublin.humla.HumlaService
import se.lublin.humla.audio.capture.VadConfigBundle
import se.lublin.mumla.Settings

/**
 * Turns one changed preference key into the extras the audio pipeline is reconfigured with.
 *
 * It is a function rather than another arm of `MumlaService`'s `switch` so that the question *"is
 * this switch on the settings screen connected to anything?"* has an answer a test can read.
 * `MumlaServiceAudioPreferencesTest` enumerates the keys straight out of `res/xml/settings_audio.xml`
 * and demands that each is either in [KEYS] or named as an exemption -- which is the only shape
 * that catches the next switch somebody adds and forgets to wire, rather than the ones already in
 * the diff.
 *
 * @return an empty bundle for a key this mapper does not own. `MumlaService` keeps the cases whose
 *   effect is not an extra (text to speech, the hot corner, the PTT sound, Bluetooth, and the four
 *   keys that force a reconnect).
 */
object AudioPreferenceExtras {
    /**
     * Every voice-gate preference produces the same extra: the whole [VadConfigBundle]. One key per
     * slider would be one `configureExtras` per slider, and before the live/rebuild split each of
     * those tore down and rebuilt the capture chain -- 110 ms with the microphone dead.
     */
    @JvmField
    val VAD_KEYS: Set<String> = setOf(
        Settings.PREF_VAD_MODE,
        Settings.PREF_VAD_SENSITIVITY,
        Settings.PREF_VAD_ADAPTIVE_FLOOR,
        Settings.PREF_VAD_FLOOR_DB,
        Settings.PREF_THRESHOLD,
        Settings.PREF_VAD_START,
        Settings.PREF_VAD_STOP,
        Settings.PREF_VAD_HOLD_MS,
        Settings.PREF_VAD_ONSET_FRAMES,
    )

    /** Every preference key this mapper turns into an extra. */
    @JvmField
    val KEYS: Set<String> = VAD_KEYS + setOf(
        Settings.PREF_INPUT_METHOD,
        Settings.PREF_HANDSET_MODE,
        Settings.PREF_AMPLITUDE_BOOST,
        Settings.PREF_HALF_DUPLEX,
        Settings.PREF_NOISE_SUPPRESSION_METHOD,
        Settings.PREF_SPEEX_NOISE_SUPPRESS_DB,
        Settings.PREF_ECHO_CANCELLATION_METHOD,
        Settings.PREF_ANDROID_NOISE_SUPPRESSOR,
        Settings.PREF_ANDROID_AGC,
        Settings.PREF_INPUT_QUALITY,
        Settings.PREF_INPUT_RATE,
        Settings.PREF_FRAMES_PER_PACKET,
    )

    @JvmStatic
    fun extrasFor(key: String, settings: Settings): Bundle {
        val extras = Bundle()
        if (key in VAD_KEYS) {
            extras.putBundle(HumlaService.EXTRAS_VAD_CONFIG, VadConfigBundle.toBundle(settings.getVadConfig()))
            return extras
        }
        when (key) {
            Settings.PREF_INPUT_METHOD ->
                extras.putInt(HumlaService.EXTRAS_TRANSMIT_MODE, settings.getHumlaInputMethod())
            Settings.PREF_HANDSET_MODE -> extras.putInt(
                HumlaService.EXTRAS_AUDIO_STREAM,
                if (settings.isHandsetMode()) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC,
            )
            Settings.PREF_AMPLITUDE_BOOST ->
                extras.putFloat(HumlaService.EXTRAS_AMPLITUDE_BOOST, settings.getAmplitudeBoostMultiplier())
            Settings.PREF_HALF_DUPLEX ->
                extras.putBoolean(HumlaService.EXTRAS_HALF_DUPLEX, settings.isHalfDuplex())
            Settings.PREF_NOISE_SUPPRESSION_METHOD ->
                extras.putString(HumlaService.EXTRAS_NOISE_SUPPRESSION_METHOD, settings.getNoiseSuppressionMethod())
            Settings.PREF_SPEEX_NOISE_SUPPRESS_DB ->
                extras.putInt(HumlaService.EXTRAS_SPEEX_NOISE_SUPPRESS_DB, settings.getSpeexNoiseSuppressDb())
            Settings.PREF_ECHO_CANCELLATION_METHOD ->
                extras.putString(HumlaService.EXTRAS_ECHO_CANCELLATION_METHOD, settings.getEchoCancellationMethod())
            Settings.PREF_ANDROID_NOISE_SUPPRESSOR -> extras.putBoolean(
                HumlaService.EXTRAS_ANDROID_NOISE_SUPPRESSOR,
                settings.getAndroidAudioEffects().noiseSuppressor,
            )
            Settings.PREF_ANDROID_AGC -> extras.putBoolean(
                HumlaService.EXTRAS_ANDROID_AGC,
                settings.getAndroidAudioEffects().automaticGainControl,
            )
            Settings.PREF_INPUT_QUALITY ->
                extras.putInt(HumlaService.EXTRAS_INPUT_QUALITY, settings.getInputQuality())
            Settings.PREF_INPUT_RATE ->
                extras.putInt(HumlaService.EXTRAS_INPUT_RATE, settings.getInputSampleRate())
            Settings.PREF_FRAMES_PER_PACKET ->
                extras.putInt(HumlaService.EXTRAS_FRAMES_PER_PACKET, settings.getFramesPerPacket())
        }
        return extras
    }
}
