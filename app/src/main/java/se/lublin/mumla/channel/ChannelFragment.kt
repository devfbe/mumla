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

import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.preference.PreferenceManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.VoiceTargetMode
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.app.ServiceClient
import se.lublin.mumla.app.ServiceViewModel
import se.lublin.mumla.app.bindClient
import se.lublin.mumla.databinding.FragmentChannelBinding
import se.lublin.mumla.service.IMumlaService
import java.util.Locale

/**
 * Holds a [ChannelListFragment] and a [ChannelChatFragment], as tabs or side by side, which share
 * the chat target through this fragment's [ChatTargetViewModel].
 */
@Suppress("TooManyFunctions") // Framework callbacks, each delegating.
class ChannelFragment :
    Fragment(),
    ServiceClient,
    SharedPreferences.OnSharedPreferenceChangeListener,
    MenuProvider {

    private val serviceModel: ServiceViewModel by activityViewModels()
    private val service: IMumlaService? get() = serviceModel.service.value

    private var binding: FragmentChannelBinding? = null

    /** True while a touch is down on the talk button, i.e. while this fragment holds transmission. */
    private var talkButtonHeld = false
    private var bound = false

    private val settings get() = Settings.getInstance(requireActivity())

    /** True if only the user's pinned channels are shown. */
    private val isShowingPinnedChannels get() = arguments?.getBoolean("pinned") == true

    override fun onServiceEvent(event: HumlaEvent) {
        when (event) {
            is HumlaEvent.UserTalkStateUpdated -> onUserTalkStateUpdated(event.user)
            is HumlaEvent.UserStateUpdated -> if (isSelf(event.user)) configureInput()
            is HumlaEvent.VoiceTargetChanged -> configureTargetPanel()
            else -> Unit
        }
    }

    override fun onServiceBound(service: IMumlaService) {
        if (service.isConnected) {
            configureTargetPanel()
            configureInput()
        }
    }

    /** Shows our talk state on the button, also when set by hot corners or a PTT toggle. */
    private fun onUserTalkStateUpdated(user: IUser) {
        val talkButton = binding?.pushtotalk ?: return
        if (!isSelf(user)) return
        when (user.talkState) {
            TalkState.TALKING, TalkState.SHOUTING, TalkState.WHISPERING -> talkButton.isPressed = true
            TalkState.PASSIVE -> talkButton.isPressed = false
        }
    }

    private fun isSelf(user: IUser): Boolean {
        val service = service?.takeIf { it.isConnected } ?: return false
        return try {
            user.session == service.session.sessionId
        } catch (e: HumlaDisconnectedException) {
            Log.d(TAG, "exception in isSelf: $e")
            false
        } catch (e: IllegalStateException) {
            Log.d(TAG, "exception in isSelf: $e")
            false
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentChannelBinding.inflate(inflater, container, false)
        this.binding = binding
        binding.pushtotalk.setOnTouchListener { _, event -> onTalkButtonTouch(event) }
        binding.targetPanelCancel.setOnClickListener { cancelWhisper() }
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

    private fun onTalkButtonTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                talkButtonHeld = true
                service?.onTalkKeyDown()
            }
            MotionEvent.ACTION_UP -> {
                talkButtonHeld = false
                service?.onTalkKeyUp()
            }
            // A parent taking over the gesture (drawer drag, system back gesture) sends
            // ACTION_CANCEL. In hold mode that must still release the press or transmission
            // sticks; in toggle mode onTalkKeyUp() is the action itself, and an aborted
            // gesture must not perform it, just as a Button does not click on cancel.
            MotionEvent.ACTION_CANCEL -> {
                talkButtonHeld = false
                if (!settings.isPushToTalkToggle) service?.onTalkKeyUp()
            }
        }
        return true
    }

    private fun cancelWhisper() {
        val session = service?.takeIf { it.isConnected }?.session ?: return
        if (session.voiceTargetMode == VoiceTargetMode.WHISPER) {
            val target = session.voiceTargetId
            session.voiceTargetId = 0
            session.unregisterWhisperTarget(target)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        PreferenceManager.getDefaultSharedPreferences(requireActivity())
            .registerOnSharedPreferenceChangeListener(this)

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
        if (!bound) {
            bound = true
            serviceModel.bindClient(this, this)
        }
    }

    override fun onDestroyView() {
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
        val service = service?.takeIf { it.isConnected }
        if (talkButtonHeld && service != null && !settings.isPushToTalkToggle) {
            service.session.setTalkingState(false)
        }
        talkButtonHeld = false
    }

    override fun onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(requireActivity())
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    private fun configureTargetPanel() {
        val binding = binding ?: return
        val session = service?.takeIf { it.isConnected }?.session ?: return
        if (session.voiceTargetMode == VoiceTargetMode.WHISPER) {
            binding.targetPanel.visibility = View.VISIBLE
            binding.targetPanelWarning.text = getString(R.string.shout_target, session.whisperTarget?.name)
        } else {
            binding.targetPanel.visibility = View.GONE
        }
    }

    /** Applies the user's interface preferences and mute state to the push-to-talk button. */
    private fun configureInput() {
        val binding = binding ?: return
        val settings = settings
        val params = binding.pushtotalkView.layoutParams
        params.height = settings.pttButtonHeight
        binding.pushtotalk.layoutParams = params

        val service = service
        val muted = if (service != null && service.isConnected) {
            val self = try {
                service.session.sessionUser
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "exception in configureInput: $e")
                null
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in configureInput: $e")
                null
            }
            self == null || self.isMuted || self.isSuppressed || self.isSelfMuted
        } else {
            false
        }
        val showPttButton = !muted &&
            settings.isPushToTalkButtonShown &&
            settings.inputMethod == Settings.ARRAY_INPUT_METHOD_PTT
        binding.pushtotalkView.visibility = if (showPttButton) View.VISIBLE else View.GONE
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key in INPUT_PREFERENCES) configureInput()
    }

    private fun newListFragment() = ChannelListFragment().apply {
        arguments = Bundle().apply { putBoolean("pinned", isShowingPinnedChannels) }
    }

    private fun tabTitle(position: Int): String =
        getString(if (position == TAB_CHANNEL) R.string.channel else R.string.chat).uppercase(Locale.getDefault())

    /** The channel list and the chat, as the pager's two pages. */
    private inner class TabsAdapter : FragmentStateAdapter(childFragmentManager, viewLifecycleOwner.lifecycle) {
        override fun getItemCount(): Int = 2

        override fun createFragment(position: Int): Fragment =
            if (position == TAB_CHANNEL) newListFragment() else ChannelChatFragment().apply { arguments = Bundle() }
    }

    private companion object {
        val TAG: String = ChannelFragment::class.java.name
        const val TAB_CHANNEL = 0
        val INPUT_PREFERENCES = setOf(
            Settings.PREF_INPUT_METHOD,
            Settings.PREF_PUSH_BUTTON_HIDE_KEY,
            Settings.PREF_PTT_BUTTON_HEIGHT,
        )
    }
}
