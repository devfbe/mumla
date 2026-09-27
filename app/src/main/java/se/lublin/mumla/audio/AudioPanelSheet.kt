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
package se.lublin.mumla.audio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import se.lublin.humla.audio.MeterReading
import se.lublin.humla.audio.capture.NoiseSuppressionMode
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.audio.routing.AudioDeviceCategory
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.BottomSheetAudioPanelBinding
import se.lublin.mumla.databinding.ItemAudioDeviceBinding
import se.lublin.mumla.preference.MeterScaleText
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.session.SessionSettings
import se.lublin.mumla.util.changes

/**
 * The quick audio panel: device, echo cancellation, transmit mode and noise suppression, and a
 * microphone check that shows, before connecting, whether the user would transmit.
 *
 * The check runs only after a tap on its button and stops when the sheet pauses. While a session
 * holds a connection it is not offered: it would take the microphone from the call.
 */
@Suppress("TooManyFunctions") // One rendering step or user action per function.
class AudioPanelSheet : BottomSheetDialogFragment() {

    private val settings get() = Settings.getInstance(requireContext())
    private val sessions get() = SessionManager.get(requireContext())

    private var binding: BottomSheetAudioPanelBinding? = null
    private var controls: AudioDeviceControls? = null
    private var micCheck: MicCheck? = null

    /** Guards the moments the sheet sets its controls itself, so only a user's change is written. */
    private var applyingSettings = false

    /** The device choices as last shown, so the radio buttons are rebuilt only when they change. */
    private var shownChoices: List<AudioDeviceChoice>? = null
    private var hysteresisDb = VadConfig.DEFAULT_HYSTERESIS_DB
    private var stateDescribedAt = 0L

    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMicCheck() else showMicUnavailable()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        BottomSheetAudioPanelBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = BottomSheetAudioPanelBinding.bind(view)
        this.binding = binding
        controls = AudioDeviceControls(requireContext(), settings) { sessions.connected }
        micCheck = MicCheck(requireContext(), ::showReading)
        for (heading in listOf(
            binding.audioPanelTitle, binding.audioPanelDevicesLabel, binding.audioPanelTransmitLabel,
            binding.audioPanelNoiseLabel, binding.audioPanelMicLabel,
        )) {
            ViewCompat.setAccessibilityHeading(heading, true)
        }
        bindControls(binding)
        render()
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    preferences.changes(SessionSettings.AUDIO_KEYS).collect { key ->
                        render()
                        // A running check is rebuilt from the settings, as a session reconfigures.
                        if (key != Settings.INPUT_METHOD.key && micCheck?.isRunning == true) startMicCheck()
                    }
                }
                sessions.state.collect { state ->
                    if (state !is SessionState.Disconnected) stopMicCheck()
                    render()
                }
            }
        }
    }

    private fun bindControls(binding: BottomSheetAudioPanelBinding) {
        binding.audioPanelEcho.setOnCheckedChangeListener { _, checked ->
            if (!applyingSettings) controls?.setEchoCancellationEnabled(checked)
        }
        binding.audioPanelTransmit.addOnButtonCheckedListener { _, id, checked ->
            if (checked && !applyingSettings) TRANSMIT_MODES[id]?.let { settings.inputMethod = it }
        }
        binding.audioPanelNoise.addOnButtonCheckedListener { _, id, checked ->
            val mode = NOISE_MODES[id]
            if (checked && !applyingSettings && mode != null) settings.noiseSuppressionMethod = mode.preferenceValue
        }
        binding.audioPanelMicToggle.setOnClickListener {
            if (micCheck?.isRunning == true) stopMicCheck() else requestMicCheck()
        }
    }

    override fun onPause() {
        // The check holds the microphone and maybe communication mode; neither outlives the sheet.
        stopMicCheck()
        super.onPause()
    }

    override fun onDestroyView() {
        micCheck = null
        controls = null
        binding = null
        shownChoices = null
        super.onDestroyView()
    }

    /** Shows the settings as they are now. */
    private fun render() {
        val binding = binding ?: return
        val controls = controls ?: return
        applyingSettings = true
        renderDevices(binding, controls.choices())
        binding.audioPanelEcho.isVisible = controls.echoCategory != null
        binding.audioPanelEcho.isChecked = controls.isEchoCancellationEnabled
        TRANSMIT_MODES.entries.firstOrNull { it.value == settings.inputMethod }
            ?.let { binding.audioPanelTransmit.check(it.key) }
        NOISE_MODES.entries.firstOrNull { it.value == settings.noiseSuppressionMode }
            ?.let { binding.audioPanelNoise.check(it.key) }
        applyingSettings = false
        renderMicCheck(binding)
    }

    private fun renderDevices(binding: BottomSheetAudioPanelBinding, choices: List<AudioDeviceChoice>) {
        if (choices == shownChoices) return
        shownChoices = choices
        val group = binding.audioPanelDevices
        group.setOnCheckedChangeListener(null)
        group.removeAllViews()
        val choiceByView = mutableMapOf<Int, Int?>()
        for (choice in choices) {
            val button = ItemAudioDeviceBinding.inflate(layoutInflater, group, false).root
            button.id = View.generateViewId()
            button.text = choice.label
            choiceByView[button.id] = choice.id
            group.addView(button)
            if (choice.selected) group.check(button.id)
        }
        group.setOnCheckedChangeListener { _, viewId ->
            if (viewId in choiceByView) controls?.choose(choiceByView[viewId])
        }
    }

    private fun renderMicCheck(binding: BottomSheetAudioPanelBinding) {
        val available = sessions.currentState is SessionState.Disconnected
        val running = micCheck?.isRunning == true
        binding.audioPanelMicHint.setText(if (available) R.string.mic_check_hint else R.string.mic_check_connected)
        for (view in listOf(
            binding.audioPanelMeter, binding.audioPanelTransmitState, binding.audioPanelMeterCaption,
            binding.audioPanelMicToggle,
        )) {
            view.isVisible = available
        }
        binding.audioPanelMicToggle.setText(if (running) R.string.mic_check_stop else R.string.mic_check_start)
    }

    /** Starts the check, asking for the microphone first if the user has not granted it yet. */
    private fun requestMicCheck() {
        val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startMicCheck() else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startMicCheck() {
        val binding = binding ?: return
        if (sessions.currentState !is SessionState.Disconnected) return
        // No session: the switch shows the echo canceller of the saved device's kind, which is this.
        val echoCategory = controls?.echoCategory ?: AudioDeviceCategory.SPEAKER
        val vad = micCheck?.start(loopback = false, echoCategory = echoCategory)
        if (vad == null) {
            showMicUnavailable()
        } else {
            hysteresisDb = vad.hysteresisDb
            stateDescribedAt = 0L
            binding.audioPanelMeterCaption.text = null
            renderMicCheck(binding)
        }
    }

    private fun stopMicCheck() {
        micCheck?.stop()
        val binding = binding ?: return
        binding.audioPanelMeter.show(null, hysteresisDb)
        ViewCompat.setStateDescription(binding.audioPanelMeter, null)
        binding.audioPanelTransmitState.text = null
        binding.audioPanelMeterCaption.text = null
        renderMicCheck(binding)
    }

    private fun showMicUnavailable() {
        val binding = binding ?: return
        binding.audioPanelMeterCaption.setText(R.string.inputLevelMeterUnavailable)
        renderMicCheck(binding)
    }

    private fun showReading(reading: MeterReading) {
        val binding = binding ?: return
        val transmit = getString(transmitState(reading))
        binding.audioPanelMeter.show(reading, hysteresisDb)
        binding.audioPanelTransmitState.text = transmit
        binding.audioPanelMeterCaption.text = MeterScaleText.caption(requireContext(), reading)
        // Twenty readings a second would drown a screen reader; one description a second is plenty.
        val now = SystemClock.uptimeMillis()
        if (stateDescribedAt == 0L || now - stateDescribedAt >= STATE_DESCRIPTION_INTERVAL_MS) {
            stateDescribedAt = now
            ViewCompat.setStateDescription(
                binding.audioPanelMeter,
                getString(R.string.mic_check_level_state, MeterScaleText.db(reading.levelDbfs), transmit),
            )
        }
    }

    /** What the user's transmit mode would do with this frame. */
    private fun transmitState(reading: MeterReading): Int = when (settings.inputMethod) {
        Settings.ARRAY_INPUT_METHOD_PTT -> R.string.mic_check_ptt
        Settings.ARRAY_INPUT_METHOD_CONTINUOUS -> R.string.mic_check_continuous
        else -> if (reading.voice) R.string.mic_check_would_transmit else R.string.mic_check_would_not_transmit
    }

    companion object {
        const val TAG = "AudioPanel"
        private const val STATE_DESCRIPTION_INTERVAL_MS = 1000L

        /** Opens the panel in [fragmentManager], unless it is open already. */
        fun show(fragmentManager: FragmentManager) {
            if (fragmentManager.findFragmentByTag(TAG) == null) AudioPanelSheet().show(fragmentManager, TAG)
        }

        private val TRANSMIT_MODES = mapOf(
            R.id.audio_panel_transmit_voice to Settings.ARRAY_INPUT_METHOD_VOICE,
            R.id.audio_panel_transmit_ptt to Settings.ARRAY_INPUT_METHOD_PTT,
            R.id.audio_panel_transmit_continuous to Settings.ARRAY_INPUT_METHOD_CONTINUOUS,
        )

        private val NOISE_MODES = mapOf(
            R.id.audio_panel_noise_none to NoiseSuppressionMode.NONE,
            R.id.audio_panel_noise_speex to NoiseSuppressionMode.SPEEX,
            R.id.audio_panel_noise_rnnoise to NoiseSuppressionMode.RNNOISE,
        )
    }
}
