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
import android.content.res.ColorStateList
import android.os.Bundle
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
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import se.lublin.humla.audio.SelfTestPhase
import se.lublin.humla.audio.capture.VadConfig
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.BottomSheetDoubleTalkTestBinding
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.util.appViewModels
import se.lublin.mumla.util.changes
import kotlin.math.roundToInt

/**
 * The talk-over (double-talk) self-test: a test voice talks on the speaker the way a remote user
 * would, the user talks over it, and a lamp shows whether the app would transmit them. The noise
 * reduction strength can be tuned right here while the test runs.
 *
 * The test runs only after a tap on Start and stops when the sheet pauses. While a session holds a
 * connection it is not offered: it would take the microphone and the speaker from the call.
 */
@Suppress("TooManyFunctions") // One rendering step or user action per function.
class DoubleTalkTestSheet : BottomSheetDialogFragment() {
    private val model by appViewModels { DoubleTalkTestViewModel(Settings.getInstance(it), AndroidSelfTestEngines(it)) }
    private val sessions get() = SessionManager.get(requireContext())
    private var binding: BottomSheetDoubleTalkTestBinding? = null

    /** True while the user drags the slider: the value is applied, and stored when they let go. */
    private var dragging = false

    private val requestMicrophone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) model.start()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        BottomSheetDoubleTalkTestBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = BottomSheetDoubleTalkTestBinding.bind(view)
        this.binding = binding
        ViewCompat.setAccessibilityHeading(binding.doubleTalkTitle, true)
        ViewCompat.setAccessibilityHeading(binding.doubleTalkStrengthLabel, true)
        bindControls(binding)
        val preferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect(::render) }
                launch {
                    preferences.changes(setOf(Settings.RNNOISE_ATTENUATION_LIMIT_DB.key)).collect {
                        if (!dragging) model.syncStrength()
                    }
                }
                sessions.state.collect { state ->
                    if (state !is SessionState.Disconnected) model.stop()
                    render(model.state.value)
                }
            }
        }
    }

    private fun bindControls(binding: BottomSheetDoubleTalkTestBinding) {
        binding.doubleTalkToggle.setOnClickListener { if (model.isRunning) model.stop() else requestStart() }
        binding.doubleTalkRetry.setOnClickListener {
            if (hasMicrophone()) model.retry() else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
        }
        binding.doubleTalkStrengthReset.setOnClickListener { model.resetStrength() }
        binding.doubleTalkStrengthReset.contentDescription =
            getString(R.string.rnnoise_strength_reset_description, Settings.RNNOISE_ATTENUATION_LIMIT_DB.default)
        val slider = binding.doubleTalkStrength
        slider.valueFrom = Settings.RNNOISE_LIMIT_MIN_DB.toFloat()
        slider.valueTo = Settings.RNNOISE_LIMIT_UNLIMITED.toFloat()
        slider.stepSize = Settings.RNNOISE_LIMIT_STEP_DB.toFloat()
        slider.value = onStep(model.state.value.strength).toFloat()
        slider.setLabelFormatter { strengthLabel(it.roundToInt()) }
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            // Keys and accessibility actions change the value without a touch to end.
            if (dragging) model.previewStrength(value.roundToInt()) else model.commitStrength(value.roundToInt())
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                dragging = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                dragging = false
                model.commitStrength(slider.value.roundToInt())
            }
        })
    }

    override fun onPause() {
        // The test holds the microphone, the speaker and the audio mode; none outlives the sheet.
        model.stop()
        super.onPause()
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    private fun hasMicrophone(): Boolean =
        ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestStart() {
        if (hasMicrophone()) model.start() else requestMicrophone.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun render(state: DoubleTalkTestUiState) {
        val binding = binding ?: return
        val available = sessions.currentState is SessionState.Disconnected
        val running = state.status == DoubleTalkTestUiState.Status.RUNNING
        binding.doubleTalkInstruction.setText(instruction(state, available))
        binding.doubleTalkEchoWarning.isVisible = running && !state.echoCancelled
        binding.doubleTalkToggle.isEnabled = available
        binding.doubleTalkRetry.isEnabled = available && state.status != DoubleTalkTestUiState.Status.IDLE
        binding.doubleTalkToggle.setText(if (model.isRunning) R.string.double_talk_stop else R.string.double_talk_start)
        renderLamp(binding, state)
        binding.doubleTalkMeter.show(state.meter, VadConfig.DEFAULT_HYSTERESIS_DB)
        binding.doubleTalkHeard.text = state.heardPercent?.let { getString(R.string.double_talk_heard, it) }
            ?: getString(R.string.double_talk_heard_pending)
        binding.doubleTalkFalseOpen.text =
            state.falseOpenPercent?.let { getString(R.string.double_talk_false_open, it) }
                ?: getString(R.string.double_talk_false_open_pending)
        renderStrength(binding, state)
    }

    private fun instruction(state: DoubleTalkTestUiState, available: Boolean): Int = when {
        !available -> R.string.double_talk_connected
        state.status == DoubleTalkTestUiState.Status.IDLE -> R.string.double_talk_idle
        state.status == DoubleTalkTestUiState.Status.STARTING -> R.string.double_talk_starting
        state.status == DoubleTalkTestUiState.Status.STOPPED -> R.string.double_talk_stopped
        state.status == DoubleTalkTestUiState.Status.UNAVAILABLE -> R.string.double_talk_unavailable
        state.phase == SelfTestPhase.LISTEN -> R.string.double_talk_listen
        else -> R.string.double_talk_talk
    }

    private fun renderLamp(binding: BottomSheetDoubleTalkTestBinding, state: DoubleTalkTestUiState) {
        val on = state.lampOn
        val color = if (on) {
            ContextCompat.getColor(requireContext(), R.color.talk_lamp_on)
        } else {
            MaterialColors.getColor(binding.doubleTalkLamp, com.google.android.material.R.attr.colorSurfaceVariant)
        }
        binding.doubleTalkLamp.backgroundTintList = ColorStateList.valueOf(color)
        val label = if (state.status == DoubleTalkTestUiState.Status.RUNNING) {
            getString(if (on) R.string.double_talk_lamp_on else R.string.double_talk_lamp_off)
        } else {
            null
        }
        binding.doubleTalkLampLabel.text = label
        ViewCompat.setStateDescription(binding.doubleTalkLamp, label)
    }

    private fun renderStrength(binding: BottomSheetDoubleTalkTestBinding, state: DoubleTalkTestUiState) {
        val slider = binding.doubleTalkStrength
        val position = onStep(state.strength).toFloat()
        if (!dragging && slider.value != position) slider.value = position
        binding.doubleTalkStrengthValue.text = strengthLabel(state.strength)
        binding.doubleTalkStrengthReset.isEnabled = !state.strengthIsDefault
    }

    private fun strengthLabel(strength: Int): String =
        if (strength >= Settings.RNNOISE_LIMIT_UNLIMITED) getString(R.string.rnnoise_strength_unlimited)
        else getString(R.string.rnnoise_strength_value, strength)

    /** The slider throws on a value off its steps, which a hand-edited preference file may hold. */
    private fun onStep(strength: Int): Int {
        val min = Settings.RNNOISE_LIMIT_MIN_DB
        val step = Settings.RNNOISE_LIMIT_STEP_DB
        val clamped = strength.coerceIn(min, Settings.RNNOISE_LIMIT_UNLIMITED)
        return min + ((clamped - min).toFloat() / step).roundToInt() * step
    }

    companion object {
        const val TAG = "DoubleTalkTest"

        /** Opens the test in [fragmentManager], unless it is open already. */
        fun show(fragmentManager: FragmentManager) {
            if (fragmentManager.findFragmentByTag(TAG) == null) DoubleTalkTestSheet().show(fragmentManager, TAG)
        }
    }
}
