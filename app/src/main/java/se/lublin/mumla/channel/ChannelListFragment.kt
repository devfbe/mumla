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

import android.app.Activity
import android.app.SearchManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.CursorWrapper
import android.graphics.PorterDuff
import android.media.AudioManager
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
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.humla.util.HumlaException
import se.lublin.humla.util.HumlaObserver
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.db.DatabaseProvider
import se.lublin.mumla.util.HumlaServiceFragment

class ChannelListFragment : HumlaServiceFragment(), OnChannelClickListener, OnUserClickListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val serviceObserver: IHumlaObserver = object : HumlaObserver() {
        override fun onDisconnected(e: HumlaException?) {
            channelView.adapter = null
            // And forget it, or the rebind that follows a reconnection takes onServiceBound's
            // setService branch and never puts an adapter back on the list -- an empty channel
            // list for the rest of the process, with a connected server behind it. Dropping the
            // adapter is also the only thing that re-reads the pinned channels, which are rooted
            // per server and were baked in when it was built.
            channelListAdapter = null
        }

        override fun onUserJoinedChannel(user: IUser, newChannel: IChannel, oldChannel: IChannel?) {
            channelListAdapter?.updateChannels()

            val service = service
            if (service == null || !service.isConnected) {
                return
            }

            val selfSession = try {
                service.HumlaSession().sessionId
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "exception in onUserJoinedChannel: $e")
                return
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onUserJoinedChannel: $e")
                return
            }

            if (user.session == selfSession) {
                scrollToChannel(newChannel.id)
            }
        }

        override fun onChannelAdded(channel: IChannel) {
            channelListAdapter?.updateChannels()
        }

        override fun onChannelRemoved(channel: IChannel) {
            channelListAdapter?.updateChannels()
        }

        override fun onChannelStateUpdated(channel: IChannel) {
            channelListAdapter?.updateChannels()
        }

        override fun onUserConnected(user: IUser) {
            channelListAdapter?.updateChannels()
        }

        override fun onUserRemoved(user: IUser, reason: String?) {
            // If we are the user being removed, don't update the channel list.
            // We won't be in a synchronized state.
            val service = service
            if (service == null || !service.isConnected) {
                return
            }

            channelListAdapter?.updateChannels()
        }

        override fun onUserStateUpdated(user: IUser) {
            channelListAdapter?.updateUserStates(user, channelView)
            requireActivity().invalidateOptionsMenu() // Update self mute/deafen state
        }

        override fun onUserTalkStateUpdated(user: IUser) {
            channelListAdapter?.updateUserStates(user, channelView)
        }
    }

    private val bluetoothReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            activity?.invalidateOptionsMenu() // Update bluetooth menu item
        }
    }

    private lateinit var channelView: RecyclerView
    private var channelListAdapter: ChannelListAdapter? = null
    private lateinit var targetProvider: ChatTargetProvider
    private lateinit var databaseProvider: DatabaseProvider
    private var actionMode: ActionMode? = null
    private lateinit var settings: Settings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    @Suppress("DEPRECATION")
    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        targetProvider = parentFragment as? ChatTargetProvider
            ?: throw ClassCastException("$parentFragment must implement ChatTargetProvider")
        databaseProvider = activity as? DatabaseProvider
            ?: throw ClassCastException("$activity must implement DatabaseProvider")
        settings = Settings.getInstance(activity)
        PreferenceManager.getDefaultSharedPreferences(activity)
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.fragment_channel_list, container, false)
        channelView = view.findViewById(R.id.channelUsers)
        channelView.layoutManager = LinearLayoutManager(activity)
        return view
    }

    @Suppress("DEPRECATION")
    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        registerForContextMenu(channelView)
        // Replaces a SDK_INT >= UPSIDE_DOWN_CAKE branch. What this buys is one code path and a
        // silenced UnspecifiedRegisterReceiverFlag -- not a not-exported receiver everywhere:
        // ContextCompat hands RECEIVER_NOT_EXPORTED to a platform that only started honouring it
        // in API 33, so on 31 and 32, which this app still supports, the receiver stays exported.
        // That is safe here rather than merely tolerated, because
        // ACTION_SCO_AUDIO_STATE_CHANGED is a protected system broadcast: no other app can send
        // it, whatever the export flag says. A receiver for a non-protected action would need a
        // real branch.
        ContextCompat.registerReceiver(
            requireActivity(),
            bluetoothReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDetach() {
        requireActivity().unregisterReceiver(bluetoothReceiver)
        super.onDetach()
    }

    override fun onDestroy() {
        super.onDestroy()
        PreferenceManager.getDefaultSharedPreferences(requireActivity())
            .unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun getServiceObserver(): IHumlaObserver = serviceObserver

    override fun onServiceBound(service: IHumlaService) {
        val adapter = channelListAdapter
        if (adapter == null) {
            setupChannelList(service)
        } else {
            adapter.setService(service)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)

        val muteItem = menu.findItem(R.id.menu_mute_button)
        val deafenItem = menu.findItem(R.id.menu_deafen_button)

        val service = service
        if (service != null && service.isConnected) {
            val session = service.HumlaSession()

            // Color the action bar icons to the primary text color of the theme, TODO move this elsewhere
            val foregroundColor = requireActivity().theme
                .obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimaryInverse))
                .getColor(0, -1)

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
                muteItem.icon?.mutate()?.setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY)
                deafenItem.icon?.mutate()?.setColorFilter(foregroundColor, PorterDuff.Mode.MULTIPLY)
            }

            val bluetoothItem = menu.findItem(R.id.menu_bluetooth)
            bluetoothItem.isChecked = session.usingBluetoothSco()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.fragment_channel_list, menu)

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
                val service = service
                if (service == null || !service.isConnected) {
                    return false
                }
                val cursor = searchView.suggestionsAdapter.getItem(i) as CursorWrapper
                val typeColumn =
                    cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA)
                val dataIdColumn = cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA)
                val itemType = cursor.getString(typeColumn)
                val itemId = cursor.getInt(dataIdColumn)

                val session = service.HumlaSession()
                return when (itemType) {
                    ChannelSearchProvider.INTENT_DATA_CHANNEL -> {
                        if (session.sessionChannel.id != itemId) {
                            session.joinChannel(itemId)
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val service = service
        if (service == null || !service.isConnected) {
            return super.onOptionsItemSelected(item)
        }
        val session = service.HumlaSession()

        return when (item.itemId) {
            R.id.menu_mute_button -> {
                session.sessionUser?.let { self ->
                    val muted = !self.isSelfMuted
                    val deafened = self.isSelfDeafened && muted // Undeafen if mute is off
                    session.setSelfMuteDeafState(muted, deafened)
                }
                requireActivity().invalidateOptionsMenu()
                true
            }
            R.id.menu_deafen_button -> {
                session.sessionUser?.let { self ->
                    val deafened = !self.isSelfDeafened
                    session.setSelfMuteDeafState(deafened, deafened)
                }
                requireActivity().invalidateOptionsMenu()
                true
            }
            R.id.menu_search -> false
            R.id.menu_bluetooth -> {
                item.isChecked = !item.isChecked
                if (item.isChecked) {
                    session.enableBluetoothSco()
                } else {
                    session.disableBluetoothSco()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupChannelList(service: IHumlaService) {
        val adapter = ChannelListAdapter(
            requireActivity(), service, databaseProvider.database, childFragmentManager,
            isShowingPinnedChannels(), settings.shouldShowUserCount(),
        )
        adapter.setOnChannelClickListener(this)
        adapter.setOnUserClickListener(this)
        channelView.adapter = adapter
        adapter.notifyDataSetChanged()
        channelListAdapter = adapter
    }

    /** Scrolls to the passed channel. */
    fun scrollToChannel(channelId: Int) {
        val adapter = channelListAdapter ?: return
        channelView.scrollToPosition(adapter.getChannelPosition(channelId))
    }

    /** Scrolls to the passed user. */
    fun scrollToUser(userId: Int) {
        val adapter = channelListAdapter ?: return
        channelView.scrollToPosition(adapter.getUserPosition(userId))
    }

    private fun isShowingPinnedChannels(): Boolean = requireArguments().getBoolean("pinned")

    override fun onChannelClick(channel: IChannel) {
        val current = targetProvider.chatTarget
        if (current != null && channel == current.channel && actionMode != null) {
            // Dismiss action mode if double pressed. FIXME: use list view selection instead?
            actionMode?.finish()
        } else {
            val cb = object :
                ChatTargetActionModeCallback(targetProvider, ChatTargetProvider.ChatTarget(channel)) {
                override fun onDestroyActionMode(actionMode: ActionMode) {
                    super.onDestroyActionMode(actionMode)
                    this@ChannelListFragment.actionMode = null
                }
            }
            actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(cb)
        }
    }

    override fun onUserClick(user: IUser) {
        val current = targetProvider.chatTarget
        if (current != null && user == current.user && actionMode != null) {
            // Dismiss action mode if double pressed. FIXME: use list view selection instead?
            actionMode?.finish()
        } else {
            val cb = object :
                ChatTargetActionModeCallback(targetProvider, ChatTargetProvider.ChatTarget(user)) {
                override fun onDestroyActionMode(actionMode: ActionMode) {
                    super.onDestroyActionMode(actionMode)
                    this@ChannelListFragment.actionMode = null
                }
            }
            actionMode = (requireActivity() as AppCompatActivity).startSupportActionMode(cb)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        if (Settings.PREF_SHOW_USER_COUNT == key) {
            channelListAdapter?.setShowChannelUserCount(settings.shouldShowUserCount())
        }
    }

    companion object {
        private val TAG: String = ChannelListFragment::class.java.name
    }
}
