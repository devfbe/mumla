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
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.fragment.app.activityViewModels
import androidx.preference.PreferenceManager
import androidx.viewpager.widget.PagerTabStrip
import androidx.viewpager.widget.ViewPager
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
import se.lublin.mumla.service.IMumlaService
import java.util.Locale

/** Holds a [ChannelListFragment] and a [ChannelChatFragment], as tabs or side by side. */
@Suppress("TooManyFunctions") // Framework callbacks plus the ChatTargetProvider methods.
class ChannelFragment :
    Fragment(),
    ServiceClient,
    SharedPreferences.OnSharedPreferenceChangeListener,
    ChatTargetProvider {

    private val serviceModel: ServiceViewModel by activityViewModels()
    private val service: IMumlaService? get() = serviceModel.service.value

    private var viewPager: ViewPager? = null
    private lateinit var talkButton: Button
    private lateinit var talkView: View
    private lateinit var targetPanel: View
    private lateinit var targetPanelText: TextView

    private var chatTarget: ChatTargetProvider.ChatTarget? = null
    private val chatTargetListeners = mutableListOf<ChatTargetProvider.OnChatTargetSelectedListener>()

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION") // The channel screen's menus move to MenuProvider together.
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_channel, container, false)
        viewPager = view.findViewById(R.id.channel_view_pager)
        view.findViewById<PagerTabStrip?>(R.id.channel_tab_strip)?.let(::styleTabStrip)

        talkView = view.findViewById(R.id.pushtotalk_view)
        talkButton = view.findViewById(R.id.pushtotalk)
        talkButton.setOnTouchListener { _, event -> onTalkButtonTouch(event) }
        targetPanel = view.findViewById(R.id.target_panel)
        view.findViewById<ImageView>(R.id.target_panel_cancel).setOnClickListener { cancelWhisper() }
        targetPanelText = view.findViewById(R.id.target_panel_warning)
        configureInput()
        return view
    }

    private fun styleTabStrip(tabStrip: PagerTabStrip) {
        val attrs = requireActivity().obtainStyledAttributes(
            intArrayOf(android.R.attr.colorPrimary, android.R.attr.textColorPrimaryInverse),
        )
        val background = attrs.getColor(0, -1)
        val text = attrs.getColor(1, -1)
        attrs.recycle()
        tabStrip.setTextColor(text)
        tabStrip.tabIndicatorColor = text
        tabStrip.setBackgroundColor(background)
        tabStrip.setTextSize(TypedValue.COMPLEX_UNIT_SP, TAB_TEXT_SIZE_SP)
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
                if (!settings.isPushToTalkToggle()) service?.onTalkKeyUp()
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

        val pager = viewPager
        if (pager != null) {
            pager.adapter = ChannelFragmentPagerAdapter(childFragmentManager)
        } else {
            childFragmentManager.beginTransaction()
                .replace(R.id.list_fragment, newListFragment())
                .replace(R.id.chat_fragment, ChannelChatFragment())
                .commit()
        }
        if (!bound) {
            bound = true
            serviceModel.bindClient(this, this)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        @Suppress("DEPRECATION")
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.channel_menu, menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val method = when (item.itemId) {
            R.id.menu_input_voice -> Settings.ARRAY_INPUT_METHOD_VOICE
            R.id.menu_input_ptt -> Settings.ARRAY_INPUT_METHOD_PTT
            R.id.menu_input_continuous -> Settings.ARRAY_INPUT_METHOD_CONTINUOUS
            else -> {
                @Suppress("DEPRECATION")
                return super.onOptionsItemSelected(item)
            }
        }
        settings.setInputMethod(method)
        return true
    }

    override fun onPause() {
        super.onPause()
        // Release only what this fragment's button holds, so a pause cannot leave it transmitting.
        // A talk state set elsewhere (e.g. a headset key with the screen off) is not ours to clear.
        val service = service?.takeIf { it.isConnected }
        if (talkButtonHeld && service != null && !settings.isPushToTalkToggle()) {
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
        val session = service?.takeIf { it.isConnected }?.session ?: return
        if (session.voiceTargetMode == VoiceTargetMode.WHISPER) {
            targetPanel.visibility = View.VISIBLE
            targetPanelText.text = getString(R.string.shout_target, session.whisperTarget?.name)
        } else {
            targetPanel.visibility = View.GONE
        }
    }

    /** Applies the user's interface preferences and mute state to the push-to-talk button. */
    private fun configureInput() {
        val settings = settings
        val params = talkView.layoutParams
        params.height = settings.getPTTButtonHeight()
        talkButton.layoutParams = params

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
            settings.isPushToTalkButtonShown() &&
            settings.getInputMethod() == Settings.ARRAY_INPUT_METHOD_PTT
        talkView.visibility = if (showPttButton) View.VISIBLE else View.GONE
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (key in INPUT_PREFERENCES) configureInput()
    }

    override fun getChatTarget(): ChatTargetProvider.ChatTarget? = chatTarget

    override fun setChatTarget(target: ChatTargetProvider.ChatTarget?) {
        chatTarget = target
        chatTargetListeners.forEach { it.onChatTargetSelected(target) }
    }

    override fun registerChatTargetListener(listener: ChatTargetProvider.OnChatTargetSelectedListener) {
        chatTargetListeners += listener
    }

    override fun unregisterChatTargetListener(listener: ChatTargetProvider.OnChatTargetSelectedListener) {
        chatTargetListeners -= listener
    }

    private fun newListFragment() = ChannelListFragment().apply {
        arguments = Bundle().apply { putBoolean("pinned", isShowingPinnedChannels) }
    }

    @Suppress("DEPRECATION") // ViewPager2 replaces this with the channel screen's tab rework.
    private inner class ChannelFragmentPagerAdapter(fm: FragmentManager) : FragmentPagerAdapter(fm) {
        override fun getItem(position: Int): Fragment = when (position) {
            0 -> newListFragment()
            else -> ChannelChatFragment().apply { arguments = Bundle() }
        }

        override fun getPageTitle(position: Int): CharSequence? = when (position) {
            0 -> getString(R.string.channel).uppercase(Locale.getDefault())
            1 -> getString(R.string.chat).uppercase(Locale.getDefault())
            else -> null
        }

        override fun getCount(): Int = 2
    }

    private companion object {
        val TAG: String = ChannelFragment::class.java.name
        const val TAB_TEXT_SIZE_SP = 12f
        val INPUT_PREFERENCES = setOf(
            Settings.PREF_INPUT_METHOD,
            Settings.PREF_PUSH_BUTTON_HIDE_KEY,
            Settings.PREF_PTT_BUTTON_HEIGHT,
        )
    }
}
