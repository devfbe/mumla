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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.fragment.app.Fragment
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
    private val pushToTalk get() = PushToTalk(requireContext())

    private var binding: FragmentChannelBinding? = null

    /** True while a touch is down on the talk button, i.e. while this fragment holds transmission. */
    private var talkButtonHeld = false

    /** Our own state as last shown; null while not synchronized. */
    private var shownSelf: SelfState? = null

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
     * Talks while held. An accessibility service cannot hold, so its click toggles transmission
     * instead, and a pause releases what it started.
     */
    @SuppressLint("ClickableViewAccessibility") // A hold is not a click; the click action is below.
    private fun setUpTalkButton(button: View) {
        button.setOnTouchListener { _, event -> onTalkButtonTouch(event) }
        ViewCompat.replaceAccessibilityAction(button, AccessibilityActionCompat.ACTION_CLICK, null) { _, _ ->
            toggleTalkingForAccessibility()
            true
        }
    }

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

    private fun onTalkButtonTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                talkButtonHeld = true
                pushToTalk.onKeyDown()
            }
            MotionEvent.ACTION_UP -> {
                talkButtonHeld = false
                pushToTalk.onKeyUp()
            }
            // A parent taking over the gesture (drawer drag, system back gesture) sends
            // ACTION_CANCEL. In hold mode that must still release the press or transmission
            // sticks; in toggle mode the release is the action itself, and an aborted
            // gesture must not perform it, just as a Button does not click on cancel.
            MotionEvent.ACTION_CANCEL -> {
                talkButtonHeld = false
                if (!settings.isPushToTalkToggle) pushToTalk.onKeyUp()
            }
        }
        return true
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

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        settings.inputMethod = when (menuItem.itemId) {
            R.id.menu_input_voice -> Settings.ARRAY_INPUT_METHOD_VOICE
            R.id.menu_input_ptt -> Settings.ARRAY_INPUT_METHOD_PTT
            R.id.menu_input_continuous -> Settings.ARRAY_INPUT_METHOD_CONTINUOUS
            else -> return false
        }
        return true
    }

    override fun onPause() {
        super.onPause()
        // Release only what this fragment's button holds, so a pause cannot leave it transmitting.
        // A talk state set elsewhere (e.g. a headset key with the screen off) is not ours to clear.
        if (talkButtonHeld && !settings.isPushToTalkToggle) session.setTalking(false)
        talkButtonHeld = false
    }

    private fun showWhisperTarget(name: String?) {
        val binding = binding ?: return
        binding.targetPanel.visibility = if (name != null) View.VISIBLE else View.GONE
        if (name != null) binding.targetPanelWarning.text = getString(R.string.shout_target, name)
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
        private const val ARG_PINNED = "pinned"
        private val INPUT_PREFERENCES = setOf(
            Settings.INPUT_METHOD.key,
            Settings.PUSH_BUTTON_HIDE.key,
            Settings.PTT_BUTTON_HEIGHT.key,
        )
    }
}
