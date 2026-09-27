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
import android.os.Bundle
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
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import se.lublin.humla.model.ChannelState
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.channel.comment.ChannelDescriptionFragment
import se.lublin.mumla.channel.comment.UserCommentFragment
import se.lublin.mumla.databinding.FragmentChannelListBinding
import se.lublin.mumla.ui.showSnackbar
import se.lublin.mumla.util.appViewModels

/**
 * The channel tree of the connected server, or with [ARG_PINNED] only the pinned channels. Rows,
 * talk states and every action go through [ChannelTreeViewModel].
 */
@Suppress("TooManyFunctions") // The list's, the menus' and the app bar's callbacks, each delegating.
class ChannelListFragment :
    Fragment(),
    MenuProvider,
    ChannelListAdapter.Listener,
    ChannelMenu.Actions,
    UserActionsSheet.Actions {

    private val tree by appViewModels { ChannelTreeViewModel.create(it, requireArguments().getBoolean(ARG_PINNED)) }
    private val chat by parentChatViewModel()

    private lateinit var channelView: RecyclerView
    private var adapter: ChannelListAdapter? = null
    private var actionMode: ActionMode? = null
    private lateinit var settings: Settings

    /** The channel we were in when the list last showed it; the list follows us when it changes. */
    private var shownOwnChannel: Int? = null

    override fun onAttach(context: Context) {
        super.onAttach(context)
        settings = Settings.getInstance(context)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val binding = FragmentChannelListBinding.inflate(inflater, container, false)
        channelView = binding.channelUsers
        channelView.layoutManager = LinearLayoutManager(activity)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = ChannelListAdapter(requireContext(), this).also { this.adapter = it }
        channelView.adapter = adapter
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { tree.talkStates.collect(adapter::setTalkStates) }
                tree.tree.collect(::show)
            }
        }
    }

    override fun onDestroyView() {
        channelView.adapter = null
        adapter = null
        shownOwnChannel = null
        super.onDestroyView()
    }

    /** Shows [tree], and follows us to our new channel once its rows are in. */
    private fun show(tree: ChannelTree?) {
        val adapter = adapter ?: return
        val own = tree?.ownChannel
        val follow = own.takeIf { shownOwnChannel != null && it != shownOwnChannel }
        shownOwnChannel = own
        adapter.submitList(tree?.rows.orEmpty()) { follow?.let(::scrollToChannel) }
    }

    fun scrollToChannel(channel: Int) = scrollTo(ChannelRow.CHANNEL_ID_MASK or channel.toLong())

    fun scrollToUser(session: Int) = scrollTo(ChannelRow.USER_ID_MASK or session.toLong())

    private fun scrollTo(id: Long) {
        val position = adapter?.positionOf(id) ?: return
        if (position >= 0) channelView.scrollToPosition(position)
    }

    override fun onPrepareMenu(menu: Menu) {
        // Writing the preference reconfigures the session's audio live.
        when (settings.noiseSuppressionMethod) {
            "speex" -> menu.findItem(R.id.menu_noise_speex)
            "none" -> menu.findItem(R.id.menu_noise_none)
            else -> menu.findItem(R.id.menu_noise_rnnoise)
        }?.isChecked = true

    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.fragment_channel_list, menu)

        val searchItem = menu.findItem(R.id.menu_search)
        val searchManager = requireActivity().getSystemService(Context.SEARCH_SERVICE) as SearchManager
        val searchView = searchItem.actionView as SearchView
        searchView.setSearchableInfo(searchManager.getSearchableInfo(requireActivity().componentName))
        searchView.setOnSuggestionListener(object : SearchView.OnSuggestionListener {
            override fun onSuggestionSelect(i: Int): Boolean = false

            override fun onSuggestionClick(i: Int): Boolean {
                val cursor = searchView.suggestionsAdapter.getItem(i) as CursorWrapper
                val itemType = cursor.getString(cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA))
                val itemId = cursor.getInt(cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_INTENT_DATA))
                return when (itemType) {
                    ChannelSearchProvider.INTENT_DATA_CHANNEL -> {
                        if (tree.tree.value?.ownChannel != itemId) join(itemId) else scrollToChannel(itemId)
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

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = when (menuItem.itemId) {
        in NOISE_METHODS -> {
            settings.noiseSuppressionMethod = NOISE_METHODS.getValue(menuItem.itemId)
            menuItem.isChecked = true
            true
        }
        else -> false
    }

    // The rows

    /** Makes the channel the chat target, or closes the target if it is that channel already. */
    override fun onChannelClick(row: ChannelRow.Channel) = toggleTarget(ChatTarget.Channel(row.channel, row.name))

    /** Makes the user the chat target, or closes the target if it is that user already. */
    override fun onUserClick(row: ChannelRow.User) = toggleTarget(ChatTarget.User(row.session, row.name))

    override fun onExpandClick(row: ChannelRow.Channel) = tree.setExpanded(row.channel, !row.expanded)

    override fun onJoinClick(row: ChannelRow.Channel) = join(row.channel)

    override fun onChannelMore(anchor: View, row: ChannelRow.Channel) =
        ChannelMenu(requireContext(), row.channel, { tree.channelMenuState(row.channel) }, this).showPopup(anchor)

    override fun onUserMore(anchor: View, row: ChannelRow.User) =
        UserActionsSheet.newInstance(row.session).show(childFragmentManager, "UserActions")

    override fun onStopListening(row: ChannelRow.Listener) = tree.setListening(row.channel, false)

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

    // The menus

    override fun permissions(channel: Int): Flow<Int> = tree.permissions(channel)

    override fun requestPermissions(channel: Int) = tree.requestPermissions(channel)

    /** Joins [channel], or tells the user why not when the server has said they may not enter it. */
    override fun join(channel: Int) {
        if (!tree.join(channel)) {
            showSnackbar(getString(R.string.channel_enter_denied, tree.channelName(channel)))
        }
    }

    override fun addChannel(parent: Int) =
        ChannelEditFragment.newInstance(parent, adding = true).show(childFragmentManager, "ChannelAdd")

    override fun editChannel(channel: Int) =
        ChannelEditFragment.newInstance(channel, adding = false).show(childFragmentManager, "ChannelAdd")

    override fun removeChannel(channel: Int) = tree.removeChannel(channel)

    override fun showDescription(channel: Int) =
        ChannelDescriptionFragment.newInstance(channel, tree.description(channel))
            .show(childFragmentManager, ChannelDescriptionFragment::class.java.name)

    override fun setPinned(channel: Int, pinned: Boolean) = tree.setPinned(channel, pinned)

    override fun setLinked(channel: Int, linked: Boolean) = tree.setLinked(channel, linked)

    override fun setListening(channel: Int, listen: Boolean) = tree.setListening(channel, listen)

    override fun unlinkAll(channel: Int) = tree.unlinkAll(channel)

    override fun channelName(channel: Int): String? = tree.channelName(channel)

    override fun shout(channel: Int, includeLinked: Boolean, includeSubchannels: Boolean) {
        if (!tree.shout(channel, includeLinked, includeSubchannels)) {
            showSnackbar(R.string.shout_failed)
        }
    }

    override fun kickBan(session: Int, reason: String, ban: Boolean) = tree.kickBan(session, reason, ban)

    override fun setMuteDeaf(session: Int, mute: Boolean, deaf: Boolean) = tree.setMuteDeaf(session, mute, deaf)

    override fun setPrioritySpeaker(session: Int, priority: Boolean) = tree.setPrioritySpeaker(session, priority)

    override fun channels(): List<ChannelState> = tree.channels()

    override fun moveUser(session: Int, channel: Int) = tree.moveUser(session, channel)

    override fun showComment(session: Int, comment: String?, edit: Boolean) =
        UserCommentFragment.newInstance(session, comment, edit)
            .show(childFragmentManager, UserCommentFragment::class.java.name)

    override fun resetComment(session: Int) = tree.resetComment(session)

    override fun register(session: Int) = tree.register(session)

    override fun whisperTo(session: Int) {
        if (!tree.whisperToUser(session)) showSnackbar(R.string.shout_failed)
    }

    override fun setLocalMuted(session: Int, muted: Boolean) = tree.setLocalMuted(session, muted)

    override fun setLocalIgnored(session: Int, ignored: Boolean) = tree.setLocalIgnored(session, ignored)

    override fun previewLocalVolume(session: Int, volume: Float) = tree.previewLocalVolume(session, volume)

    override fun setLocalVolume(session: Int, volume: Float) = tree.setLocalVolume(session, volume)

    override fun userMenuState(session: Int): UserMenuState? = tree.userMenuState(session)

    override fun userMenuStates(session: Int): Flow<UserMenuState?> = tree.userMenuStates(session)

    override fun showInfo(session: Int, name: String?) {
        showUserInfoDialog(requireContext(), name, tree.userStats(session))
    }

    companion object {
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
