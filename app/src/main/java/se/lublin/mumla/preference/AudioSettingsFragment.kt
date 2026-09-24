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
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import se.lublin.humla.audio.capture.AndroidAudioRecordSource
import se.lublin.humla.audio.capture.EchoCancellationMode
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.PcmCaptureSourceFactory
import se.lublin.humla.audio.capture.VadMode
import se.lublin.humla.exception.AudioInitializationException
import se.lublin.humla.session.AudioDeviceCategory
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.audio.AndroidAudioTrackSink
import se.lublin.mumla.audio.AudioTestSession
import se.lublin.mumla.audio.MeterReading
import se.lublin.mumla.audio.PcmPlaybackSinkFactory

/** The audio settings screen; the decisions live in [AudioSettingsPolicy], this is the wiring. */
open class AudioSettingsFragment : MumlaPreferenceFragment(R.xml.settings_audio) {
    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

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

        // The preprocessor runs before the detector, so the classic slider now measures a
        // processed frame; the summary says so.
        findPreference<Preference>(Settings.PREF_THRESHOLD)?.summary =
            getString(R.string.detectionThresholdSum) + "\n\n" + getString(R.string.detectionThresholdMigration)

        findPreference<Preference>(KEY_RECALIBRATE)?.setOnPreferenceClickListener {
            session?.recalibrate()
            true
        }

        updateAudioDependents(preferenceScreen, inputPreference.value)
    }

    // ---------------------------------------------------------------- the live meter

    private var session: AudioTestSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Restarts a running preview whenever a setting it was built from changes; keyed on
     * [SessionSettings.AUDIO_KEYS] so it tracks the same settings as the service.
     */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in se.lublin.mumla.service.SessionSettings.AUDIO_KEYS && isTesting()) restartSession()
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefsListener)
        // The test takes the microphone and may switch to communication mode, which quietens
        // other apps' audio, so it only runs while the user asks for it.
        findPreference<SwitchPreferenceCompat>(KEY_TEST)?.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                restartSession(loopback = false)
            } else {
                findPreference<SwitchPreferenceCompat>(KEY_LOOPBACK)?.isChecked = false
                stopSession()
            }
            true
        }
        findPreference<SwitchPreferenceCompat>(KEY_LOOPBACK)?.setOnPreferenceChangeListener { _, newValue ->
            if (isTesting()) restartSession(loopback = newValue as Boolean)
            true
        }
        // Nothing runs until asked; this shows the idle hint.
        stopSession()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        // This session's AudioRecord silences the service's capture, so release it first.
        stopSession()
        // Neither switch is persisted; reset them so they agree with the stopped session.
        findPreference<SwitchPreferenceCompat>(KEY_LOOPBACK)?.isChecked = false
        findPreference<SwitchPreferenceCompat>(KEY_TEST)?.isChecked = false
        super.onPause()
    }

    private fun isTesting(): Boolean = findPreference<SwitchPreferenceCompat>(KEY_TEST)?.isChecked ?: false

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
        val vad = settings.vadConfig
        meter.setHysteresisDb(vad.hysteresisDb)
        val started = AudioTestSession(
            context.getSystemService(Context.AUDIO_SERVICE) as AudioManager,
            vad,
            settings.noiseSuppressionMode,
            settings.speexNoiseSuppressDb,
            // The loopback plays out loud, so apply the speaker's canceller as the user has it.
            if (settings.isEchoCancellationEnabled(AudioDeviceCategory.SPEAKER)) EchoCancellationMode.WEBRTC
            else EchoCancellationMode.NONE,
            settings.androidAudioEffects,
            loopback,
            onReading = { reading -> mainHandler.post { meter.setReading(reading) } },
            captureFactory = captureFactory,
            sinkFactory = sinkFactory,
        )
        try {
            started.start()
            session = started
            // Clear the idle hint; the first reading follows within a few frames.
            meter.setReading(null)
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
        findPreference<InputLevelMeterPreference>(KEY_METER)?.setMessage(getString(R.string.inputLevelMeterIdle))
    }

    /** The mode as stored, which is what [Settings] will read. */
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
     * Shows exactly the controls [mode] reads and hides the rest. Called with the listener's value,
     * since the preference has not been written yet at that point.
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

    internal companion object {
        private const val TAG = "AudioSettingsFragment"
        private const val KEY_METER = "input_level_meter"
        private const val KEY_TEST = "audio_test_microphone"
        private const val KEY_LOOPBACK = "audio_loopback_test"
        private const val KEY_RECALIBRATE = "vad_recalibrate"

        /** Where the test session gets its microphone and speaker; replaced in tests. */
        @VisibleForTesting
        var captureFactory: PcmCaptureSourceFactory = AndroidAudioRecordSource.Factory()

        @VisibleForTesting
        var sinkFactory: PcmPlaybackSinkFactory = AndroidAudioTrackSink.Factory()

        @VisibleForTesting
        fun resetFactories() {
            captureFactory = AndroidAudioRecordSource.Factory()
            sinkFactory = AndroidAudioTrackSink.Factory()
        }
    }
}
