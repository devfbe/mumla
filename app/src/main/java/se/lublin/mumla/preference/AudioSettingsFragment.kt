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
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.launch
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadMode
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.audio.routing.listCommunicationDevices
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.audio.MicCheck
import se.lublin.mumla.session.SessionSettings
import se.lublin.mumla.util.changes

/** The audio settings screen; the decisions live in [AudioSettingsPolicy], this is the wiring. */
open class AudioSettingsFragment : MumlaPreferenceFragment(R.xml.settings_audio) {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        val inputPreference = requireNotNull(findPreference<ListPreference>(Settings.INPUT_METHOD.key))
        inputPreference.setOnPreferenceChangeListener { _, newValue ->
            updateAudioDependents(preferenceScreen, newValue as String)
            true
        }

        // Scan each sample rate and mark the ones this device cannot open.
        val inputQualityPreference = requireNotNull(findPreference<ListPreference>(Settings.INPUT_RATE.key))
        inputQualityPreference.entries = inputQualityPreference.entryValues.map { value ->
            val rate = value.toString().toInt()
            val supported =
                AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) > 0
            "${rate}Hz" + if (supported) "" else " (unsupported)"
        }.toTypedArray()

        findPreference<CheckBoxPreference>(Settings.ANDROID_NOISE_SUPPRESSOR.key)
            ?.let { markAvailability(it, NoiseSuppressor.isAvailable()) }
        findPreference<CheckBoxPreference>(Settings.ANDROID_AGC.key)
            ?.let { markAvailability(it, AutomaticGainControl.isAvailable()) }

        val noisePref = requireNotNull(findPreference<ListPreference>(Settings.NOISE_SUPPRESSION_METHOD.key))
        noisePref.setOnPreferenceChangeListener { _, newValue ->
            applyNoiseDependents(NoiseSuppressionMode.fromPreferenceValue(newValue as String))
            true
        }
        applyNoiseDependents(NoiseSuppressionMode.fromPreferenceValue(noisePref.value))

        val vadModePref = requireNotNull(findPreference<ListPreference>(Settings.VAD_MODE.key))
        vadModePref.setOnPreferenceChangeListener { _, newValue ->
            applyVadDependents(VadMode.fromPreferenceValue(newValue as String))
            true
        }
        findPreference<CheckBoxPreference>(Settings.VAD_ADAPTIVE_FLOOR.key)
            ?.setOnPreferenceChangeListener { _, newValue ->
                applyFloorDependents(currentVadMode(), newValue as Boolean)
                true
            }
        applyVadDependents(currentVadMode())

        // The preprocessor runs before the detector, so the classic slider now measures a
        // processed frame; the summary says so.
        findPreference<Preference>(Settings.THRESHOLD.key)?.summary =
            getString(R.string.detectionThresholdSum) + "\n\n" + getString(R.string.detectionThresholdMigration)

        findPreference<Preference>(KEY_RECALIBRATE)?.setOnPreferenceClickListener {
            micCheck?.recalibrate()
            true
        }

        findPreference<ListPreference>(Settings.AUDIO_DEVICE.key)?.apply {
            setOnPreferenceClickListener {
                refreshAudioDevices()
                false
            }
            setOnPreferenceChangeListener { _, newValue ->
                chooseAudioDevice(newValue as String)
                false
            }
        }
        refreshAudioDevices()

        updateAudioDependents(preferenceScreen, inputPreference.value)
    }

    private var audioDeviceChoices: AudioDeviceChoices? = null

    /** Lists the devices there now, read without routing, and shows the saved choice. */
    private fun refreshAudioDevices() {
        val preference = findPreference<ListPreference>(Settings.AUDIO_DEVICE.key) ?: return
        val context = context ?: return
        val choices = AudioDeviceChoices.of(
            resources,
            listCommunicationDevices(context.getSystemService(AudioManager::class.java)),
            Settings.getInstance(context).preferredAudioDevice,
        )
        audioDeviceChoices = choices
        preference.entries = choices.labels.toTypedArray()
        preference.entryValues = choices.labels.indices.map(Int::toString).toTypedArray()
        preference.value = choices.selected.toString()
        preference.summary = choices.labels[choices.selected]
    }

    /** Saves the entry at [index]; the service routes by it once a session runs. */
    private fun chooseAudioDevice(index: String) {
        val choices = audioDeviceChoices ?: return
        val position = index.toIntOrNull()?.takeIf { it in choices.devices.indices } ?: return
        Settings.getInstance(requireContext()).preferredAudioDevice = choices.devices[position]
        refreshAudioDevices()
    }

    private var micCheck: MicCheck? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        micCheck = MicCheck(requireContext()) { reading ->
            findPreference<InputLevelMeterPreference>(KEY_METER)?.setReading(reading)
        }
        // A running preview is rebuilt from the same settings the service reconfigures from.
        val preferences = preferenceManager.sharedPreferences ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            preferences.changes(SessionSettings.AUDIO_KEYS).collect { if (isTesting) restartPreview() }
        }
    }

    override fun onResume() {
        super.onResume()
        // A headset may have come or gone, or the toolbar chooser saved another device.
        refreshAudioDevices()
        // The test takes the microphone and may switch to communication mode, which quietens
        // other apps' audio, so it only runs while the user asks for it.
        findPreference<SwitchPreferenceCompat>(KEY_TEST)?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                restartPreview(loopback = false)
            } else {
                findPreference<SwitchPreferenceCompat>(KEY_LOOPBACK)?.isChecked = false
                stopPreview()
            }
            true
        }
        findPreference<SwitchPreferenceCompat>(KEY_LOOPBACK)?.setOnPreferenceChangeListener { _, newValue ->
            if (isTesting) restartPreview(loopback = newValue as Boolean)
            true
        }
        // Nothing runs until asked; this shows the idle hint.
        stopPreview()
    }

    override fun onPause() {
        // The preview's AudioRecord silences the service's capture, so release it first.
        stopPreview()
        // Neither switch is persisted; reset them so they agree with the stopped preview.
        findPreference<SwitchPreferenceCompat>(KEY_LOOPBACK)?.isChecked = false
        findPreference<SwitchPreferenceCompat>(KEY_TEST)?.isChecked = false
        super.onPause()
    }

    private val isTesting: Boolean get() = findPreference<SwitchPreferenceCompat>(KEY_TEST)?.isChecked ?: false

    private fun restartPreview(
        loopback: Boolean = findPreference<SwitchPreferenceCompat>(KEY_LOOPBACK)?.isChecked ?: false,
    ) {
        val meter = findPreference<InputLevelMeterPreference>(KEY_METER) ?: return
        // The loopback plays out loud, so apply the speaker's canceller as the user has it.
        val vad = micCheck?.start(loopback, AudioDeviceCategory.SPEAKER)
        if (vad == null) {
            meter.setMessage(getString(R.string.inputLevelMeterUnavailable))
        } else {
            meter.setHysteresisDb(vad.hysteresisDb)
            // Clear the idle hint; the first reading follows within a few frames.
            meter.setReading(null)
        }
    }

    private fun stopPreview() {
        micCheck?.stop()
        findPreference<InputLevelMeterPreference>(KEY_METER)?.setMessage(getString(R.string.inputLevelMeterIdle))
    }

    /** The mode as stored, which is what [Settings] will read. */
    protected fun currentVadMode(): VadMode =
        VadMode.fromPreferenceValue(findPreference<ListPreference>(Settings.VAD_MODE.key)?.value)

    private fun markAvailability(pref: CheckBoxPreference, available: Boolean) {
        if (available) return
        pref.isEnabled = false
        pref.isChecked = false
        pref.summary = getString(R.string.audioEffectUnavailable)
    }

    private fun applyNoiseDependents(mode: NoiseSuppressionMode) {
        findPreference<Preference>(Settings.SPEEX_NOISE_SUPPRESS_DB.key)?.isVisible =
            AudioSettingsPolicy.speexDepthVisible(mode)
    }

    /**
     * Shows exactly the controls [mode] reads and hides the rest. Called with the listener's value,
     * since the preference has not been written yet at that point.
     */
    protected open fun applyVadDependents(mode: VadMode) {
        val dependents = AudioSettingsPolicy.vadDependents(mode)
        findPreference<Preference>(Settings.VAD_SENSITIVITY.key)?.isVisible = dependents.adaptive
        findPreference<Preference>(Settings.VAD_ADAPTIVE_FLOOR.key)?.isVisible = dependents.adaptive
        findPreference<Preference>(Settings.THRESHOLD.key)?.isVisible = dependents.amplitude
        findPreference<Preference>(Settings.VAD_START.key)?.isVisible = dependents.probability
        findPreference<Preference>(Settings.VAD_STOP.key)?.isVisible = dependents.probability
        // "Measure again" only means something where something is being measured.
        findPreference<Preference>(KEY_RECALIBRATE)?.isVisible = dependents.adaptive
        applyFloorDependents(
            mode,
            findPreference<CheckBoxPreference>(Settings.VAD_ADAPTIVE_FLOOR.key)?.isChecked
                ?: Settings.VAD_ADAPTIVE_FLOOR.default,
        )
    }

    private fun applyFloorDependents(mode: VadMode, adaptiveFloor: Boolean) {
        findPreference<Preference>(Settings.VAD_FLOOR_DB.key)?.isVisible =
            AudioSettingsPolicy.manualFloorVisible(mode, adaptiveFloor)
    }

    private fun updateAudioDependents(screen: PreferenceScreen, inputMethod: String) {
        requireNotNull(screen.findPreference<PreferenceCategory>("ptt_settings")).isEnabled =
            Settings.ARRAY_INPUT_METHOD_PTT == inputMethod
        requireNotNull(screen.findPreference<PreferenceCategory>("vad_settings")).isEnabled =
            Settings.ARRAY_INPUT_METHOD_VOICE == inputMethod
    }

    private companion object {
        private const val KEY_METER = "input_level_meter"
        private const val KEY_TEST = "audio_test_microphone"
        private const val KEY_LOOPBACK = "audio_loopback_test"
        private const val KEY_RECALIBRATE = "vad_recalibrate"
    }
}
