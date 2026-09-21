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

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadMode
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.audio.AudioTestSession
import se.lublin.mumla.audio.MeterReading

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

        findPreference<Preference>(KEY_RECALIBRATE)?.setOnPreferenceClickListener {
            session?.recalibrate()
            true
        }

        updateAudioDependents(preferenceScreen, inputPreference.value)
    }

    // ---------------------------------------------------------------- the live meter (spec B10)

    private var session: AudioTestSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Restarts the preview whenever a setting it was built from changes.
     *
     * It listens to [AudioPreferenceExtras.KEYS] rather than to a list of its own, so the preview
     * cannot end up tracking a different set of settings than the service does -- two lists over
     * one question is the shape this project keeps removing.
     */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in se.lublin.mumla.service.AudioPreferenceExtras.KEYS) restartSession()
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefsListener)
        findPreference<SwitchPreferenceCompat>(KEY_LOOPBACK)?.setOnPreferenceChangeListener { _, newValue ->
            restartSession(loopback = newValue as Boolean)
            true
        }
        restartSession()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        // The microphone goes back before the screen does, not after: this session holds an
        // AudioRecord that silences the service's own capture while it is open.
        stopSession()
        // The monitor is not persisted, so a screen that is left with it on comes back with it off;
        // reset the switch as well so the two agree.
        findPreference<SwitchPreferenceCompat>(KEY_LOOPBACK)?.isChecked = false
        super.onPause()
    }

    private fun restartSession(
        loopback: Boolean = findPreference<SwitchPreferenceCompat>(KEY_LOOPBACK)?.isChecked ?: false,
    ) {
        stopSession()
        val meter = findPreference<InputLevelMeterPreference>(KEY_METER) ?: return
        val context = context ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            meter.setMessage(getString(R.string.inputLevelMeterUnavailable))
            return
        }
        val settings = Settings.getInstance(context)
        val vad = settings.getVadConfig()
        meter.setHysteresisDb(vad.hysteresisDb)
        val started = AudioTestSession(
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            vad,
            settings.getNoiseSuppressionMode(),
            settings.getSpeexNoiseSuppressDb(),
            settings.getEchoCancellationMode(),
            settings.getAndroidAudioEffects(),
            loopback,
            onReading = { reading -> mainHandler.post { meter.setReading(reading) } },
        )
        try {
            started.start()
            session = started
        } catch (e: AudioInitializationException) {
            Log.w(TAG, "input level meter unavailable", e)
            meter.setMessage(getString(R.string.inputLevelMeterUnavailable))
        }
    }

    private fun stopSession() {
        session?.stop()
        session = null
        // Readings already posted must not land on a dead meter and leave a frozen bar behind.
        mainHandler.removeCallbacksAndMessages(null)
        findPreference<InputLevelMeterPreference>(KEY_METER)?.setReading(null)
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
        // "Measure again" only means something where something is being measured.
        findPreference<Preference>(KEY_RECALIBRATE)?.isVisible = dependents.adaptive
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

    private companion object {
        const val TAG = "AudioSettingsFragment"
        const val KEY_METER = "input_level_meter"
        const val KEY_LOOPBACK = "audio_loopback_test"
        const val KEY_RECALIBRATE = "vad_recalibrate"
    }
}
