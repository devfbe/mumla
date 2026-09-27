/*
 * Copyright (C) 2026 The Mumla authors
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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.os.bundleOf
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.UserState
import se.lublin.mumla.R
import se.lublin.mumla.databinding.BottomSheetUserActionsBinding
import se.lublin.mumla.databinding.ItemUserActionBinding
import se.lublin.mumla.ui.showConfirmDialog
import se.lublin.mumla.util.HtmlUtils
import se.lublin.mumla.util.UserStatus
import kotlin.math.roundToInt

/** The slider's range in percent; 100 is unchanged. */
private const val LOCAL_VOLUME_MAX_PERCENT = 200
private const val PERCENT = 100f

/**
 * The bottom sheet of a user's row: their name, status and comment, an inline local-volume slider
 * for other users, and the moderation, comment and local actions the popup menu it replaces used
 * to offer. Only the session id is kept across rotation; the user's live state and every action go
 * through [Actions], which its parent fragment implements off its own view model — the sheet never
 * touches a view model of its own, so it cannot outlive the one it would have read from.
 */
@Suppress("TooManyFunctions") // One rendering step or action mapping per function.
class UserActionsSheet : BottomSheetDialogFragment() {

    /** What the sheet reads and does, for the user it was opened on. */
    @Suppress("TooManyFunctions") // One per action, plus the two state reads.
    interface Actions : MenuPermissions {
        fun userMenuState(session: Int): UserMenuState?

        /** [session]'s menu state, live; null once they are gone from the model. */
        fun userMenuStates(session: Int): Flow<UserMenuState?>

        fun kickBan(session: Int, reason: String, ban: Boolean)
        fun setMuteDeaf(session: Int, mute: Boolean, deaf: Boolean)
        fun setPrioritySpeaker(session: Int, priority: Boolean)

        /** The channels a user can be moved to, in tree order. */
        fun channels(): List<ChannelState>
        fun moveUser(session: Int, channel: Int)
        fun showComment(session: Int, comment: String?, edit: Boolean)
        fun resetComment(session: Int)
        fun register(session: Int)
        fun setLocalMuted(session: Int, muted: Boolean)
        fun setLocalIgnored(session: Int, ignored: Boolean)

        /** Live preview while dragging the local-volume slider; not stored. */
        fun previewLocalVolume(session: Int, volume: Float)

        /** Plays [volume] live like [previewLocalVolume], and stores it. */
        fun setLocalVolume(session: Int, volume: Float)
        fun showInfo(session: Int, name: String?)
    }

    private val session: Int get() = requireArguments().getInt(ARG_SESSION)
    private val actions: Actions get() = requireParentFragment() as Actions

    /** Guards the moments the sheet moves the slider itself, so only a real change calls back. */
    private var applyingModel = false

    /** Whether the slider is mid-drag: a drag only previews, release keeps the value once. */
    private var trackingTouch = false

    /** The rows currently bound, to rebuild them only when which actions show changed. */
    private var boundRows: List<UserMenuRow>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        BottomSheetUserActionsBinding.inflate(inflater, container, false).root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = BottomSheetUserActionsBinding.bind(view)
        ViewCompat.setAccessibilityHeading(binding.userActionsName, true)
        bindVolumeSlider(binding)

        val channel = actions.userMenuState(session)?.user?.channel
        if (channel == null) {
            dismissAllowingStateLoss()
            return
        }
        observeState(binding, channel)
    }

    private fun bindVolumeSlider(binding: BottomSheetUserActionsBinding) {
        binding.userActionsVolumeSlider.addOnChangeListener { _, value, fromUser ->
            setVolumeLabel(binding, value.roundToInt())
            if (applyingModel || !fromUser) return@addOnChangeListener
            if (trackingTouch) {
                actions.previewLocalVolume(session, value / PERCENT)
            } else {
                // A keyboard or accessibility move: no touch release will follow to keep it.
                actions.setLocalVolume(session, value / PERCENT)
            }
        }
        binding.userActionsVolumeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                trackingTouch = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                trackingTouch = false
                actions.setLocalVolume(session, slider.value / PERCENT)
            }
        })
        binding.userActionsVolumeReset.setOnClickListener {
            updateVolume(binding, 1f)
            actions.setLocalVolume(session, 1f)
        }
    }

    private fun setVolumeLabel(binding: BottomSheetUserActionsBinding, percent: Int) {
        binding.userActionsVolumeValue.text = getString(R.string.local_volume_percent, percent)
        ViewCompat.setStateDescription(binding.userActionsVolumeSlider, getString(R.string.a11y_local_volume, percent))
    }

    private fun updateVolume(binding: BottomSheetUserActionsBinding, volume: Float) {
        val slider = binding.userActionsVolumeSlider
        if (slider.isPressed) return
        val percent = (volume * PERCENT).roundToInt().coerceIn(0, LOCAL_VOLUME_MAX_PERCENT)
        applyingModel = true
        slider.value = percent.toFloat()
        applyingModel = false
        setVolumeLabel(binding, percent)
    }

    /** Renders every real state change, and asks once for the channel permissions if not known yet. */
    private fun observeState(binding: BottomSheetUserActionsBinding, channel: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { actions.userMenuStates(session).distinctUntilChanged().collect { render(binding, it) } }
                launch { requestPermissionsIfUnknown(channel) }
            }
        }
    }

    private suspend fun requestPermissionsIfUnknown(channel: Int) {
        var known = false
        actions.permissions(channel).distinctUntilChanged().collect { permissions ->
            if (!known && permissions == 0) actions.requestPermissions(channel)
            known = true
        }
    }

    private fun render(binding: BottomSheetUserActionsBinding, state: UserMenuState?) {
        if (state == null) {
            dismissAllowingStateLoss()
            return
        }
        val user = state.user
        binding.userActionsName.text = user.name
        val status = statusLine(user)
        binding.userActionsStatus.isVisible = status != null
        binding.userActionsStatus.text = status
        val comment = user.comment?.takeIf { it.isNotEmpty() }?.let(HtmlUtils::toPlainText)
        binding.userActionsComment.isVisible = !comment.isNullOrEmpty()
        binding.userActionsComment.text = comment
        binding.userActionsVolumeSection.isVisible = !state.isSelf
        if (!state.isSelf) updateVolume(binding, user.localVolume)
        bindRows(binding, userMenuRows(state))
    }

    /** Rebuilds the rows only when which actions show changed; otherwise just updates their state. */
    private fun bindRows(binding: BottomSheetUserActionsBinding, rows: List<UserMenuRow>) {
        val previous = boundRows
        val container = binding.userActionsRows
        if (previous == null || previous.map { it.action } != rows.map { it.action }) {
            container.removeAllViews()
            for (row in rows) {
                val item = ItemUserActionBinding.inflate(layoutInflater, container, false)
                item.userActionIcon.setImageResource(iconOf(row.action))
                item.userActionTitle.setText(titleOf(row.action))
                item.root.setOnClickListener { onAction(row.action) }
                bindRowState(item, row)
                container.addView(item.root)
            }
        } else if (previous != rows) {
            for (index in rows.indices) {
                bindRowState(ItemUserActionBinding.bind(container.getChildAt(index)), rows[index])
            }
        }
        boundRows = rows
    }

    /** The row's switch, and for TalkBack its state as one node: e.g. "Mute, switch, on". */
    private fun bindRowState(item: ItemUserActionBinding, row: UserMenuRow) {
        item.userActionSwitch.isVisible = row.action.checkable
        item.userActionSwitch.isChecked = row.checked
        if (row.action.checkable) ViewCompat.setAccessibilityDelegate(item.root, switchDelegate(row.checked))
    }

    private fun switchDelegate(checked: Boolean) = object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = SWITCH_CLASS_NAME
            info.isCheckable = true
            info.isChecked = checked
        }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per action.
    private fun onAction(action: UserAction) {
        val user = actions.userMenuState(session)?.user ?: return
        when (action) {
            UserAction.KICK -> showKickDialog(ban = false)
            UserAction.BAN -> showKickDialog(ban = true)
            UserAction.MUTE -> actions.setMuteDeaf(session, !(user.isMuted || user.isSuppressed), user.isDeafened)
            UserAction.DEAFEN -> actions.setMuteDeaf(session, user.isMuted, !user.isDeafened)
            UserAction.MOVE -> showChannelMoveDialog()
            UserAction.PRIORITY -> actions.setPrioritySpeaker(session, !user.isPrioritySpeaker)
            UserAction.LOCAL_MUTE -> actions.setLocalMuted(session, !user.isLocalMuted)
            UserAction.IGNORE_MESSAGES -> actions.setLocalIgnored(session, !user.isLocalIgnored)
            UserAction.VIEW_COMMENT -> actions.showComment(session, user.comment, edit = false)
            UserAction.CHANGE_COMMENT -> actions.showComment(session, user.comment, edit = true)
            UserAction.RESET_COMMENT -> requireContext().showConfirmDialog(
                getString(R.string.confirm_reset_comment, user.name),
                R.string.confirm,
            ) { actions.resetComment(session) }
            UserAction.INFO -> actions.showInfo(session, user.name)
            UserAction.REGISTER -> actions.register(session)
        }
    }

    /** The status shown below the name: mute/deafen state first, then priority speaker. */
    private fun statusLine(user: UserState): String? {
        val status = UserStatus.of(user)
        val parts = buildList {
            if (status != UserStatus.NONE) add(getString(statusStringOf(status)))
            if (user.isPrioritySpeaker) add(getString(R.string.user_menu_priority_speaker))
        }
        return parts.joinToString(" · ").ifEmpty { null }
    }

    private fun statusStringOf(status: UserStatus): Int = when (status) {
        UserStatus.SELF_DEAFENED -> R.string.a11y_state_deafened
        UserStatus.DEAFENED -> R.string.a11y_state_server_deafened
        UserStatus.SELF_MUTED -> R.string.a11y_state_muted
        UserStatus.MUTED -> R.string.a11y_state_server_muted
        UserStatus.SUPPRESSED -> R.string.a11y_state_suppressed
        UserStatus.NONE -> error("NONE has no status string")
    }

    private fun showKickDialog(ban: Boolean) {
        val reasonField = EditText(requireContext()).apply { setHint(R.string.hint_reason) }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.user_menu_kick)
            .setView(reasonField)
            .setPositiveButton(R.string.user_menu_kick) { _, _ ->
                actions.kickBan(session, reasonField.text.toString(), ban)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showChannelMoveDialog() {
        val channels = actions.channels()
        if (channels.isEmpty()) return
        showChannelMoveDialog(requireContext(), channels) { actions.moveUser(session, it) }
    }

    private fun iconOf(action: UserAction): Int = when (action) {
        UserAction.KICK -> R.drawable.ic_action_delete_dark
        UserAction.BAN -> R.drawable.ic_action_error
        UserAction.MUTE -> R.drawable.ic_action_microphone
        UserAction.DEAFEN -> R.drawable.ic_action_headphones
        UserAction.MOVE -> R.drawable.ic_action_move
        UserAction.PRIORITY -> R.drawable.ic_action_audio
        UserAction.LOCAL_MUTE -> R.drawable.ic_action_audio_muted
        UserAction.IGNORE_MESSAGES -> R.drawable.ic_action_bad
        UserAction.VIEW_COMMENT, UserAction.CHANGE_COMMENT, UserAction.RESET_COMMENT -> R.drawable.ic_action_comment
        UserAction.INFO -> R.drawable.ic_action_info_dark
        UserAction.REGISTER -> R.drawable.ic_registered
    }

    private fun titleOf(action: UserAction): Int = when (action) {
        UserAction.KICK -> R.string.user_menu_kick
        UserAction.BAN -> R.string.user_menu_ban
        UserAction.MUTE -> R.string.user_menu_mute
        UserAction.DEAFEN -> R.string.user_menu_deafen
        UserAction.MOVE -> R.string.user_menu_move
        UserAction.PRIORITY -> R.string.user_menu_priority_speaker
        UserAction.LOCAL_MUTE -> R.string.user_menu_local_mute
        UserAction.IGNORE_MESSAGES -> R.string.user_menu_ignore_messages
        UserAction.VIEW_COMMENT -> R.string.user_menu_view_comment
        UserAction.CHANGE_COMMENT -> R.string.user_menu_change_comment
        UserAction.RESET_COMMENT -> R.string.user_menu_reset_comment
        UserAction.INFO -> R.string.user_menu_information
        UserAction.REGISTER -> R.string.user_menu_register
    }

    companion object {
        private const val ARG_SESSION = "session"
        private const val SWITCH_CLASS_NAME = "android.widget.Switch"

        fun newInstance(session: Int) = UserActionsSheet().apply { arguments = bundleOf(ARG_SESSION to session) }
    }
}
