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

package se.lublin.mumla.channel

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MaterialR
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.FragmentChannelBinding
import se.lublin.mumla.session.SelfState

/**
 * The channel screen's mute, deafen and audio actions. At the bottom, push-to-talk is the large
 * centre between them, or where it is not offered, whether we transmit; at the top they sit under
 * the tabs, and push-to-talk spans the bottom alone.
 */
internal class ControlBar(private val binding: FragmentChannelBinding) {
    private val resources get() = binding.root.resources

    /** Whether the bar is at the bottom, as last placed; null before the first placement. */
    private var placedAtBottom: Boolean? = null

    fun place(atBottom: Boolean) {
        if (placedAtBottom == atBottom) return
        placedAtBottom = atBottom
        val voice = listOf(binding.controlMute, binding.controlDeafen)
        if (atBottom) {
            voice.forEach { moveTo(it, binding.controlBarStart) }
            moveTo(binding.controlAudio, binding.controlBarEnd)
        } else {
            // Before the spacer that pushes the audio action to the end.
            voice.forEachIndexed { index, button -> moveTo(button, binding.controlBarTop, index) }
            moveTo(binding.controlAudio, binding.controlBarTop)
        }
        binding.controlBarTop.isVisible = !atBottom
        binding.controlBarStart.isVisible = atBottom
        binding.controlBarEnd.isVisible = atBottom
        val bar = binding.controlBar
        val padding = if (atBottom) resources.getDimensionPixelSize(R.dimen.padding_small) else 0
        bar.updatePadding(top = padding, bottom = padding)
        bar.setBackgroundColor(
            if (atBottom) MaterialColors.getColor(bar, MaterialR.attr.colorSurfaceContainer) else Color.TRANSPARENT,
        )
        (binding.pushtotalk as? MaterialButton)?.cornerRadius =
            if (atBottom) resources.getDimensionPixelSize(R.dimen.control_bar_ptt_corner_radius) else 0
    }

    private fun moveTo(view: View, parent: ViewGroup, index: Int = -1) {
        if (view.parent === parent) return
        (view.parent as ViewGroup).removeView(view)
        parent.addView(view, index)
    }

    /** Our mute and deafen state; while not synchronized there is nothing to toggle. */
    fun showSelf(self: SelfState?) {
        show(binding.controlMute, Toggle.MUTE, self?.isSelfMuted == true, enabled = self != null)
        show(binding.controlDeafen, Toggle.DEAFEN, self?.isSelfDeafened == true, enabled = self != null)
    }

    private fun show(button: MaterialButton, toggle: Toggle, on: Boolean, enabled: Boolean) {
        button.isEnabled = enabled
        button.setIconResource(if (on) toggle.onIcon else toggle.offIcon)
        // The action a tap takes, and the state it would change.
        val action = resources.getString(if (on) toggle.offAction else toggle.onAction)
        button.contentDescription = action
        button.tooltipText = action
        ViewCompat.setStateDescription(button, if (on) resources.getString(toggle.onState) else null)
    }

    /** Shows [inputMethod], and whether we transmit, where push-to-talk would be. */
    fun showTalkState(inputMethod: String, talking: Boolean) {
        val view = binding.controlTalkState
        view.setText(
            when (inputMethod) {
                Settings.ARRAY_INPUT_METHOD_PTT -> R.string.inputMethodPtt
                Settings.ARRAY_INPUT_METHOD_CONTINUOUS -> R.string.inputMethodContinuous
                else -> R.string.inputMethodVoice
            },
        )
        view.isActivated = talking
        ViewCompat.setStateDescription(
            view,
            resources.getString(if (talking) R.string.a11y_transmitting else R.string.a11y_not_transmitting),
        )
    }

    private enum class Toggle(
        @param:DrawableRes val offIcon: Int,
        @param:DrawableRes val onIcon: Int,
        @param:StringRes val onAction: Int,
        @param:StringRes val offAction: Int,
        @param:StringRes val onState: Int,
    ) {
        MUTE(
            R.drawable.ic_action_microphone, R.drawable.ic_action_microphone_muted,
            R.string.mute, R.string.unmute, R.string.a11y_state_muted,
        ),
        DEAFEN(
            R.drawable.ic_action_audio, R.drawable.ic_action_audio_muted,
            R.string.deafen, R.string.undeafen, R.string.a11y_state_deafened,
        ),
    }
}
