/*
 * Copyright (C) 2014 Andrew Comminos
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

package se.lublin.mumla.preference

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadMode
import se.lublin.mumla.R
import se.lublin.mumla.Settings

/**
 * The audio settings screen (spec B3-B6, B9, B10).
 *
 * Everything it decides lives in [AudioSettingsPolicy]; what is left here is the wiring to the
 * preference objects, which no host test can reach without an Activity and a window.
 */
open class AudioSettingsFragment : MumlaPreferenceFragment() {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings_audio, rootKey)

        val inputPreference = requireNotNull(findPreference<ListPreference>(Settings.PREF_INPUT_METHOD))
        inputPreference.setOnPreferenceChangeListener { _, newValue ->
            updateAudioDependents(preferenceScreen, newValue as String)
            true
        }

        // Scan each sample rate and mark the ones this device cannot open.
        val inputQualityPreference = requireNotNull(findPreference<ListPreference>(Settings.PREF_INPUT_RATE))
        inputQualityPreference.entries = inputQualityPreference.entryValues.map { value ->
            val rate = value.toString().toInt()
            val supported =
                AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
            "${rate}Hz" + if (supported) "" else " (unsupported)"
        }.toTypedArray()

        findPreference<ListPreference>(Settings.PREF_ECHO_CANCELLATION_METHOD)?.let { echoPref ->
            val values = AudioSettingsPolicy.echoCancellationValues(AcousticEchoCanceler.isAvailable())
            val keep = echoPref.entryValues.indices.filter { echoPref.entryValues[it].toString() in values }
            echoPref.entries = keep.map { echoPref.entries[it] }.toTypedArray()
            echoPref.entryValues = keep.map { echoPref.entryValues[it] }.toTypedArray()
            echoPref.value = AudioSettingsPolicy.fallbackEchoValue(echoPref.value, values)
        }
        findPreference<CheckBoxPreference>(Settings.PREF_ANDROID_NOISE_SUPPRESSOR)
            ?.let { markAvailability(it, NoiseSuppressor.isAvailable()) }
        findPreference<CheckBoxPreference>(Settings.PREF_ANDROID_AGC)
            ?.let { markAvailability(it, AutomaticGainControl.isAvailable()) }

        val noisePref = requireNotNull(findPreference<ListPreference>(Settings.PREF_NOISE_SUPPRESSION_METHOD))
        noisePref.setOnPreferenceChangeListener { _, newValue ->
            applyNoiseDependents(NoiseSuppressionMode.fromPreferenceValue(newValue as String))
            true
        }
        applyNoiseDependents(NoiseSuppressionMode.fromPreferenceValue(noisePref.value))

        val vadModePref = requireNotNull(findPreference<ListPreference>(Settings.PREF_VAD_MODE))
        vadModePref.setOnPreferenceChangeListener { _, newValue ->
            applyVadDependents(VadMode.fromPreferenceValue(newValue as String))
            true
        }
        findPreference<CheckBoxPreference>(Settings.PREF_VAD_ADAPTIVE_FLOOR)
            ?.setOnPreferenceChangeListener { _, newValue ->
                applyFloorDependents(currentVadMode(), newValue as Boolean)
                true
            }
        applyVadDependents(currentVadMode())

        // Spec 4.1/B1, and it is the migration note the ledger addressed to this screen: since the
        // preprocessor moved in front of the detector, the classic slider measures a denoised and
        // possibly gain-controlled frame, so the same number means a different loudness than it did.
        findPreference<Preference>(Settings.PREF_THRESHOLD)?.summary =
            getString(R.string.detectionThresholdSum) + "\n\n" + getString(R.string.detectionThresholdMigration)

        updateAudioDependents(preferenceScreen, inputPreference.value)
    }

    /** The mode as the preference file has it, which is the value [Settings] will read too. */
    protected fun currentVadMode(): VadMode =
        VadMode.fromPreferenceValue(findPreference<ListPreference>(Settings.PREF_VAD_MODE)?.value)

    private fun markAvailability(pref: CheckBoxPreference, available: Boolean) {
        if (available) return
        pref.isEnabled = false
        pref.isChecked = false
        pref.summary = getString(R.string.audioEffectUnavailable)
    }

    private fun applyNoiseDependents(mode: NoiseSuppressionMode) {
        findPreference<Preference>(Settings.PREF_SPEEX_NOISE_SUPPRESS_DB)?.isVisible =
            AudioSettingsPolicy.speexDepthVisible(mode)
    }

    /**
     * Shows exactly the controls the chosen mode reads, and hides the rest rather than greying
     * them out. Called with the mode from the listener, not from the preference: at that moment
     * the preference has not been written yet.
     */
    protected open fun applyVadDependents(mode: VadMode) {
        val dependents = AudioSettingsPolicy.vadDependents(mode)
        findPreference<Preference>(Settings.PREF_VAD_SENSITIVITY)?.isVisible = dependents.adaptive
        findPreference<Preference>(Settings.PREF_VAD_ADAPTIVE_FLOOR)?.isVisible = dependents.adaptive
        findPreference<Preference>(Settings.PREF_THRESHOLD)?.isVisible = dependents.amplitude
        findPreference<Preference>(Settings.PREF_VAD_START)?.isVisible = dependents.probability
        findPreference<Preference>(Settings.PREF_VAD_STOP)?.isVisible = dependents.probability
        applyFloorDependents(
            mode,
            findPreference<CheckBoxPreference>(Settings.PREF_VAD_ADAPTIVE_FLOOR)?.isChecked
                ?: Settings.DEFAULT_VAD_ADAPTIVE_FLOOR,
        )
    }

    private fun applyFloorDependents(mode: VadMode, adaptiveFloor: Boolean) {
        findPreference<Preference>(Settings.PREF_VAD_FLOOR_DB)?.isVisible =
            AudioSettingsPolicy.manualFloorVisible(mode, adaptiveFloor)
    }

    private fun updateAudioDependents(screen: PreferenceScreen, inputMethod: String) {
        requireNotNull(screen.findPreference<PreferenceCategory>("ptt_settings")).isEnabled =
            Settings.ARRAY_INPUT_METHOD_PTT == inputMethod
        requireNotNull(screen.findPreference<PreferenceCategory>("vad_settings")).isEnabled =
            Settings.ARRAY_INPUT_METHOD_VOICE == inputMethod
    }
}
