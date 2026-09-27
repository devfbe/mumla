/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

import android.annotation.SuppressLint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.FragmentChannelBinding
import se.lublin.mumla.session.PushToTalk
import se.lublin.mumla.session.SelfState
import se.lublin.mumla.session.SelfSummary
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.session.SessionViewModel
import se.lublin.mumla.util.activityAppViewModels
import se.lublin.mumla.util.changes
import java.util.Locale

/**
 * Holds a [ChannelListFragment] and a [ChannelChatFragment], as tabs or side by side, which share
 * the chat target through this fragment's [ChatViewModel].
 */
@Suppress("TooManyFunctions") // Framework callbacks, each delegating.
class ChannelFragment :
    Fragment(),
    MenuProvider {

    private val session by activityAppViewModels { SessionViewModel(SessionManager.get(it)) }
    private val chat by viewModels<ChatViewModel> { ChatViewModel.Factory }
    private val pushToTalk get() = PushToTalk(requireContext())

    private var binding: FragmentChannelBinding? = null

    /** True while a touch is down on the talk button, i.e. while this fragment holds transmission. */
    private var talkButtonHeld = false

    /** True while a touch is down on the hold-to-whisper button. */
    private var whisperHeld = false

    /** Our own state as last shown; null while not synchronized. */
    private var shownSelf: SelfState? = null

    /** The whisper target as last shown; null while not whispering. Drives the start/stop a11y announce. */
    private var shownWhisperTarget: String? = null

    private val settings get() = Settings.getInstance(requireActivity())

    private val announcer = SelfStateAnnouncer { text ->
        // No view shows these states for a live region to carry; a transient announcement it is.
        @Suppress("DEPRECATION")
        binding?.root?.announceForAccessibility(getString(text))
    }

    /** True if only the user's pinned channels are shown. */
    private val isShowingPinnedChannels get() = arguments?.getBoolean(ARG_PINNED) == true

    /** Shows our own state; each synchronization starts the spoken announcements over. */
    private fun onSelf(self: SelfState?) {
        val previous = shownSelf
        shownSelf = self
        configureInput()
        if (previous?.isSelfMuted != self?.isSelfMuted || previous?.isSelfDeafened != self?.isSelfDeafened) {
            requireActivity().invalidateMenu()
        }
        if (self == null) return
        if (previous == null) announcer.reset()
        announcer.onMuteState(self.isSelfMuted, self.isSelfDeafened)
        if (previous?.isTalking != self.isTalking) {
            binding?.pushtotalk?.isPressed = self.isTalking
            showTalking(self.isTalking)
        }
    }

    /**
     * Gives the talk button our talk state for accessibility services, and speaks changes in
     * push-to-talk mode, where the user makes them; voice activation would chatter.
     */
    private fun showTalking(talking: Boolean) {
        val talkButton = binding?.pushtotalk ?: return
        ViewCompat.setStateDescription(talkButton, if (talking) getString(R.string.a11y_transmitting) else null)
        announcer.onTalking(talking, announce = settings.inputMethod == Settings.ARRAY_INPUT_METHOD_PTT)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentChannelBinding.inflate(inflater, container, false)
        this.binding = binding
        setUpTalkButton(binding.pushtotalk)
        setUpWhisperHoldButton(binding.targetPanelHold)
        binding.targetPanelCancel.setOnClickListener { session.stopWhispering() }
        configureInput()
        return binding.root
    }

    private fun styleTabs(tabs: TabLayout) {
        val context = requireActivity()
        val background = context.getColor(R.color.app_bar_background)
        val text = context.getColor(R.color.on_app_bar)
        tabs.setBackgroundColor(background)
        tabs.setTabTextColors(text, text)
        tabs.setSelectedTabIndicatorColor(text)
    }

    /**
     * Wires [button] for a press-and-hold action: [onDown] on touch down, [onUp] on touch up, and
     * on cancel (a parent taking the gesture over, e.g. a drawer drag or the system back gesture)
     * [onUp] too, unless [releaseOnCancel] says otherwise — a toggle mode where release is the
     * whole action must not perform it on an aborted gesture, just as a Button does not click on
     * cancel. An accessibility service cannot hold, so [onAccessibilityClick] replaces the single
     * click it sends in place of one.
     */
    @SuppressLint("ClickableViewAccessibility") // A hold is not a click; the click action is below.
    private fun setUpHoldButton(
        button: View,
        releaseOnCancel: () -> Boolean = { true },
        onDown: () -> Unit,
        onUp: () -> Unit,
        onAccessibilityClick: () -> Unit,
    ) {
        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> onDown()
                MotionEvent.ACTION_UP -> onUp()
                MotionEvent.ACTION_CANCEL -> if (releaseOnCancel()) onUp()
            }
            true
        }
        ViewCompat.replaceAccessibilityAction(button, AccessibilityActionCompat.ACTION_CLICK, null) { _, _ ->
            onAccessibilityClick()
            true
        }
    }

    /** Talks while held; a pause releases what it started. */
    private fun setUpTalkButton(button: View) = setUpHoldButton(
        button,
        releaseOnCancel = { !settings.isPushToTalkToggle },
        onDown = { talkButtonHeld = true; pushToTalk.onKeyDown() },
        onUp = { talkButtonHeld = false; pushToTalk.onKeyUp() },
        onAccessibilityClick = ::toggleTalkingForAccessibility,
    )

    private fun toggleTalkingForAccessibility() {
        if (shownSelf == null) return
        when {
            settings.isPushToTalkToggle -> pushToTalk.onKeyUp()
            session.isTalking -> {
                talkButtonHeld = false
                pushToTalk.onKeyUp()
            }
            else -> {
                talkButtonHeld = true
                pushToTalk.onKeyDown()
            }
        }
    }

    /** Transmits to the armed whisper target while held; a pause releases what it started. */
    private fun setUpWhisperHoldButton(button: View) = setUpHoldButton(
        button,
        onDown = ::startWhisperHold,
        onUp = ::stopWhisperHold,
        onAccessibilityClick = { if (whisperHeld) stopWhisperHold() else startWhisperHold() },
    )

    private fun startWhisperHold() {
        whisperHeld = true
        session.setWhisperActive(true)
        session.setTalking(true)
    }

    private fun stopWhisperHold() {
        whisperHeld = false
        session.setTalking(false)
        session.setWhisperActive(false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).changes(INPUT_PREFERENCES)
                .collect { configureInput() }
        }

        val binding = requireNotNull(binding)
        val pager = binding.channelViewPager
        val tabs = binding.channelTabs
        if (pager != null && tabs != null) {
            styleTabs(tabs)
            pager.adapter = TabsAdapter()
            TabLayoutMediator(tabs, pager) { tab, position -> tab.text = tabTitle(position) }.attach()
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    chat.unread.collect { showUnread(tabs, it) }
                }
            }
        } else {
            childFragmentManager.beginTransaction()
                .replace(R.id.list_fragment, newListFragment())
                .replace(R.id.chat_fragment, ChannelChatFragment())
                .commit()
        }
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { session.self.collect(::onSelf) }
                launch { session.selfSummary.collect(::showInAppBar) }
                session.whisperTarget.collect(::showWhisperTarget)
            }
        }
    }

    override fun onDestroyView() {
        announcer.reset()
        shownSelf = null
        binding = null
        super.onDestroyView()
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.channel_menu, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        val self = shownSelf
        val muteItem = menu.findItem(R.id.menu_mute_button)
        val deafenItem = menu.findItem(R.id.menu_deafen_button)
        muteItem.isVisible = self != null
        deafenItem.isVisible = self != null
        if (self == null) return
        muteItem.setIcon(
            if (self.isSelfMuted) R.drawable.ic_action_microphone_muted else R.drawable.ic_action_microphone,
        )
        deafenItem.setIcon(if (self.isSelfDeafened) R.drawable.ic_action_audio_muted else R.drawable.ic_action_audio)
        // The action a tap takes, which is also what accessibility services read.
        muteItem.setTitle(if (self.isSelfMuted) R.string.unmute else R.string.mute)
        deafenItem.setTitle(if (self.isSelfDeafened) R.string.undeafen else R.string.deafen)
        // Tinted like the app bar title.
        val tint = PorterDuffColorFilter(requireActivity().getColor(R.color.on_app_bar), PorterDuff.Mode.MULTIPLY)
        muteItem.icon?.mutate()?.colorFilter = tint
        deafenItem.icon?.mutate()?.colorFilter = tint
    }

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        when (menuItem.itemId) {
            R.id.menu_mute_button -> session.toggleMute()
            R.id.menu_deafen_button -> session.toggleDeafen()
            else -> return false
        }
        return true
    }

    /** The server as the title, where we are and our own mute state as the subtitle. */
    private fun showInAppBar(summary: SelfSummary?) {
        val appBar = (requireActivity() as AppCompatActivity).supportActionBar ?: return
        session.serverName?.let { appBar.title = it }
        appBar.subtitle = summary?.text(requireContext())
    }

    override fun onStop() {
        super.onStop()
        (requireActivity() as AppCompatActivity).supportActionBar?.subtitle = null
    }

    override fun onPause() {
        super.onPause()
        // Release only what this fragment's button holds, so a pause cannot leave it transmitting.
        // A talk state set elsewhere (e.g. a headset key with the screen off) is not ours to clear.
        if (talkButtonHeld && !settings.isPushToTalkToggle) session.setTalking(false)
        talkButtonHeld = false
        if (whisperHeld) stopWhisperHold()
    }

    /** Shows the panel, and announces it starting or stopping so a screen reader notices either. */
    private fun showWhisperTarget(name: String?) {
        val binding = binding ?: return
        val started = shownWhisperTarget == null && name != null
        val stopped = shownWhisperTarget != null && name == null
        shownWhisperTarget = name
        binding.targetPanel.visibility = if (name != null) View.VISIBLE else View.GONE
        if (name != null) binding.targetPanelWarning.text = getString(R.string.shout_target, name)
        configureInput()
        @Suppress("DEPRECATION") // No view shows this for a live region to carry.
        when {
            started -> binding.root.announceForAccessibility(getString(R.string.shout_target, name))
            stopped -> binding.root.announceForAccessibility(getString(R.string.a11y_stop_shouting))
        }
    }

    /** Applies the user's interface preferences and mute state to the push-to-talk button. */
    private fun configureInput() {
        val binding = binding ?: return
        val settings = settings
        val params = binding.pushtotalkView.layoutParams
        params.height = settings.pttButtonHeight
        binding.pushtotalk.layoutParams = params

        val muted = shownSelf?.cannotTalk == true
        val showPttButton = !muted &&
            settings.isPushToTalkButtonShown &&
            settings.inputMethod == Settings.ARRAY_INPUT_METHOD_PTT
        binding.pushtotalkView.visibility = if (showPttButton) View.VISIBLE else View.GONE

        val showHoldButton = !muted && shownWhisperTarget != null && settings.isHoldToWhisper
        binding.targetPanelHold.visibility = if (showHoldButton) View.VISIBLE else View.GONE
    }

    private fun showUnread(tabs: TabLayout, count: Int) {
        val tab = tabs.getTabAt(TAB_CHAT) ?: return
        if (count == 0) {
            tab.removeBadge()
            return
        }
        tab.orCreateBadge.apply {
            number = count
            setContentDescriptionQuantityStringsResource(R.plurals.unread_messages)
        }
    }

    private fun newListFragment() = ChannelListFragment.newInstance(isShowingPinnedChannels)

    private fun tabTitle(position: Int): String =
        getString(if (position == TAB_CHANNEL) R.string.channel else R.string.chat).uppercase(Locale.getDefault())

    /** The channel list and the chat, as the pager's two pages. */
    private inner class TabsAdapter : FragmentStateAdapter(childFragmentManager, viewLifecycleOwner.lifecycle) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment =
            if (position == TAB_CHANNEL) newListFragment() else ChannelChatFragment()
    }

    companion object {
        /** The channel list and chat, with [pinned] showing only the pinned channels. */
        fun newInstance(pinned: Boolean = false) =
            ChannelFragment().apply { arguments = bundleOf(ARG_PINNED to pinned) }

        private const val TAB_CHANNEL = 0
        private const val TAB_CHAT = 1
        private const val ARG_PINNED = "pinned"
        private val INPUT_PREFERENCES = setOf(
            Settings.INPUT_METHOD.key,
            Settings.PUSH_BUTTON_HIDE.key,
            Settings.PTT_BUTTON_HEIGHT.key,
            Settings.HOLD_TO_WHISPER.key,
        )
    }
}
