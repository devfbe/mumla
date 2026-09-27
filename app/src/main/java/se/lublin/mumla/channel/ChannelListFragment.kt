/*
 * Copyright (C) 2014 Andrew Comminos
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

import android.app.SearchManager
import android.content.Context
import android.database.CursorWrapper
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.FragmentChannelListBinding
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.service.toggleSelfMute
import se.lublin.mumla.session.SessionClient
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.session.bindClient
import se.lublin.mumla.util.changes

class ChannelListFragment :
    Fragment(),
    SessionClient,
    MenuProvider {

    private val sessions get() = SessionManager.get(requireContext())
    private var bound = false

    override fun onSessionState(state: SessionState) {
        val session = sessions.session.value ?: return
        if (state == SessionState.Connected) {
            channelListAdapter?.setSession(session) ?: setupChannelList(session)
        } else if (state !is SessionState.Connecting) {
            channelView.adapter = null
            // And forget it: a new connection builds a fresh adapter, as the pinned channels are
            // per server.
            channelListAdapter = null
        }
    }

    override fun onSessionEvent(event: HumlaEvent) {
        when (event) {
            is HumlaEvent.UserJoinedChannel -> onUserJoinedChannel(event.user, event.newChannel)
            is HumlaEvent.ChannelAdded,
            is HumlaEvent.ChannelRemoved,
            is HumlaEvent.ChannelStateUpdated,
            is HumlaEvent.UserConnected,
            is HumlaEvent.UserListeningUpdated,
            -> channelListAdapter?.updateChannels()
            is HumlaEvent.UserRemoved -> {
                // If we are the user being removed, don't update the channel list.
                // We won't be in a synchronized state.
                if (sessions.connected != null) channelListAdapter?.updateChannels()
            }
            is HumlaEvent.UserStateUpdated -> {
                channelListAdapter?.updateUserStates(event.user, channelView)
                requireActivity().invalidateMenu() // Update self mute/deafen state
            }
            is HumlaEvent.UserTalkStateUpdated -> channelListAdapter?.updateUserStates(event.user, channelView)
            else -> Unit
        }
    }

    private fun onUserJoinedChannel(user: IUser, newChannel: IChannel) {
        channelListAdapter?.updateChannels()

        val session = sessions.connected ?: return
        val selfSession = try {
            session.sessionId
        } catch (e: IllegalStateException) {
            Log.d(TAG, "exception in onUserJoinedChannel: $e")
            null
        }

        if (selfSession != null && user.session == selfSession) {
            scrollToChannel(newChannel.id)
        }
    }

    private lateinit var channelView: RecyclerView
    private var channelListAdapter: ChannelListAdapter? = null
    private val chat by parentChatViewModel()
    private var actionMode: ActionMode? = null
    private lateinit var settings: Settings

    override fun onAttach(context: Context) {
        super.onAttach(context)
        settings = Settings.getInstance(context)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = FragmentChannelListBinding.inflate(inflater, container, false)
        channelView = binding.channelUsers
        channelView.layoutManager = LinearLayoutManager(activity)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        viewLifecycleOwner.lifecycleScope.launch {
            PreferenceManager.getDefaultSharedPreferences(requireContext()).changes(Settings.SHOW_USER_COUNT.key)
                .collect { channelListAdapter?.setShowChannelUserCount(settings.shouldShowUserCount) }
        }
        if (!bound) {
            bound = true
            sessions.bindClient(this, this)
        }
    }

    override fun onPrepareMenu(menu: Menu) {
        // Writing the preference reconfigures the session's audio live.
        when (settings.noiseSuppressionMethod) {
            "speex" -> menu.findItem(R.id.menu_noise_speex)
            "none" -> menu.findItem(R.id.menu_noise_none)
            else -> menu.findItem(R.id.menu_noise_rnnoise)
        }?.isChecked = true

        val muteItem = menu.findItem(R.id.menu_mute_button)
        val deafenItem = menu.findItem(R.id.menu_deafen_button)

        val session = sessions.connected
        if (session != null) {
            // Tinted like the app bar title.
            val foregroundColor = requireActivity().getColor(R.color.on_app_bar)

            val self = session.sessionUser
            if (self != null) {
                muteItem.setIcon(
                    if (self.isSelfMuted) R.drawable.ic_action_microphone_muted
                    else R.drawable.ic_action_microphone
                )
                deafenItem.setIcon(
                    if (self.isSelfDeafened) R.drawable.ic_action_audio_muted
                    else R.drawable.ic_action_audio
                )
                // The action a tap takes, which is also what accessibility services read.
                muteItem.setTitle(if (self.isSelfMuted) R.string.unmute else R.string.mute)
                deafenItem.setTitle(if (self.isSelfDeafened) R.string.undeafen else R.string.deafen)
                val tint = PorterDuffColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY)
                muteItem.icon?.mutate()?.colorFilter = tint
                deafenItem.icon?.mutate()?.colorFilter = tint
            }
        }
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.fragment_channel_list, menu)

        val searchItem = menu.findItem(R.id.menu_search)
        val searchManager =
            requireActivity().getSystemService(Context.SEARCH_SERVICE) as SearchManager

        val searchView = searchItem.actionView as SearchView
        searchView.setSearchableInfo(
            searchManager.getSearchableInfo(requireActivity().componentName)
        )
        searchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(i: Int): Boolean = false

            override fun onSuggestionClick(i: Int): Boolean {
                val session = sessions.connected ?: return false
                val cursor = searchView.suggestionsAdapter.getItem(i) as CursorWrapper
                val typeColumn =
                    cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA)
                val dataIdColumn = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA)
                val itemType = cursor.getString(typeColumn)
                val itemId = cursor.getInt(dataIdColumn)

                return when (itemType) {
                    ChannelSearchProvider.INTENT_DATA_CHANNEL -> {
                        if (session.sessionChannel?.id != itemId) {
                            val channel = session.getChannel(itemId)
                            if (channel != null) {
                                session.joinOrExplain(requireContext(), channel)
                            } else {
                                session.joinChannel(itemId)
                            }
                        } else {
                            scrollToChannel(itemId)
                        }
                        true
                    }
                    ChannelSearchProvider.INTENT_DATA_USER -> {
                        scrollToUser(itemId)
                        true
                    }
                    else -> false
                }
            }
        })
    }


    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when {
        menuItem.itemId in NOISE_METHODS -> {
            settings.noiseSuppressionMethod = NOISE_METHODS.getValue(menuItem.itemId)
            menuItem.isChecked = true
            true
        }
        menuItem.itemId == R.id.menu_mute_button || menuItem.itemId == R.id.menu_deafen_button ->
            toggleSelfMuteDeaf(deafen = menuItem.itemId == R.id.menu_deafen_button)
        else -> false
    }

    /** Flips our own mute, or deafness with [deafen]; returns false while not connected. */
    private fun toggleSelfMuteDeaf(deafen: Boolean): Boolean {
        val session = sessions.connected ?: return false
        if (deafen) {
            session.sessionUser?.let { self ->
                val deafened = !self.isSelfDeafened
                session.setSelfMuteDeafState(deafened, deafened)
            }
        } else {
            toggleSelfMute(session)
        }
        requireActivity().invalidateMenu()
        return true
    }

    private fun setupChannelList(session: IHumlaSession) {
        val repository = MumlaRepository.get(requireContext())
        // Read now, off the main thread, for the channel menus' pin toggle.
        session.targetServer?.let { repository.pinnedChannels.of(it.id) }
        val adapter = ChannelListAdapter(
            requireActivity(), session, repository, childFragmentManager,
            isShowingPinnedChannels, settings.shouldShowUserCount,
        )
        adapter.onChannelClick = ::onChannelClick
        adapter.onUserClick = ::onUserClick
        channelView.adapter = adapter
        adapter.notifyDataSetChanged()
        channelListAdapter = adapter
    }

    fun scrollToChannel(channelId: Int) {
        val adapter = channelListAdapter ?: return
        channelView.scrollToPosition(adapter.getChannelPosition(channelId))
    }

    fun scrollToUser(userId: Int) {
        val adapter = channelListAdapter ?: return
        channelView.scrollToPosition(adapter.getUserPosition(userId))
    }

    private val isShowingPinnedChannels: Boolean get() = requireArguments().getBoolean(ARG_PINNED)

    /** Makes [channel] the chat target, or closes the target if it is [channel] already. */
    fun onChannelClick(channel: IChannel) = toggleTarget(ChatTarget.Channel(channel.id, channel.name))

    /** Makes [user] the chat target, or closes the target if it is [user] already. */
    fun onUserClick(user: IUser) = toggleTarget(ChatTarget.User(user.session, user.name))

    private fun toggleTarget(target: ChatTarget) {
        val mode = actionMode
        if (mode != null && chat.target.value == target) {
            // Tapped the open target again.
            mode.finish()
        } else {
            val callback = ChatTargetActionModeCallback(chat::select, target) { actionMode = null }
            actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(callback)
        }
    }

    companion object {
        private val TAG: String = ChannelListFragment::class.java.name
        private const val ARG_PINNED = "pinned"

        /** The whole channel tree, or with [pinned] only the pinned channels. */
        fun newInstance(pinned: Boolean) = ChannelListFragment().apply { arguments = bundleOf(ARG_PINNED to pinned) }

        /** The noise suppression items and the methods they pick. */
        private val NOISE_METHODS = mapOf(
            R.id.menu_noise_none to "none",
            R.id.menu_noise_speex to "speex",
            R.id.menu_noise_rnnoise to "rnnoise",
        )
    }
}
