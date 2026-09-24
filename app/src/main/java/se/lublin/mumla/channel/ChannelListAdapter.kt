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

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import se.lublin.humla.HumlaService
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.LocalVolumes
import se.lublin.humla.model.TalkState
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.mumla.R
import se.lublin.mumla.databinding.ChannelListenerRowBinding
import se.lublin.mumla.databinding.ChannelRowBinding
import se.lublin.mumla.databinding.ChannelUserRowBinding
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.drawable.CircleDrawable

/**
 * Flattens the channel tree into rows. Main thread only, like every `RecyclerView.Adapter`.
 *
 * - [updateChannels] only schedules: a burst of model events in one main-thread turn collapses
 *   into a single rebuild. Model events never carry deltas, so a rebuild just reads the model.
 * - One pass per rebuild, including collapsed subtrees: [constructNodes] carries subtree user
 *   counts back up instead of calling `IChannel.subchannelUserCount` (O(n*depth)), and the
 *   counts land on the [Node] so binding a row reads no model at all.
 *
 * The recursion needs no depth check: `ModelHandler` refuses parent cycles and trees deeper than
 * 256 below the root.
 *
 * With [showPinnedOnly], the tree is rooted at the channels pinned when the adapter was created,
 * which it shows once they are read.
 */
class ChannelListAdapter(
    private val context: Context,
    service: IHumlaService,
    private val repository: MumlaRepository,
    private val fragmentManager: FragmentManager,
    showPinnedOnly: Boolean,
    showUserCount: Boolean,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var humlaService: IHumlaService = service
    private var rootChannels: List<Int> = if (showPinnedOnly) emptyList() else listOf(0)
    private val nodes: MutableList<Node> = ArrayList()

    /**
     * A mapping of user-set channel expansions.
     * If a key is not mapped, default to hiding empty channels.
     */
    private val expandedChannels = HashMap<Int, Boolean>()

    /** Called when a channel's row is tapped. */
    var onChannelClick: ((IChannel) -> Unit)? = null

    /** Called when a user's row is tapped. */
    var onUserClick: ((IUser) -> Unit)? = null
    private var showChannelUserCount: Boolean = showUserCount

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rebuildScheduled = false
    private val rebuildRunnable = Runnable {
        rebuildScheduled = false
        rebuildNodes()
        notifyDataSetChanged()
    }

    init {
        setHasStableIds(true)
        var constructed = false
        val server = humlaService.targetServer
        if (showPinnedOnly && server != null) {
            repository.pinnedChannels.whenLoaded(server.id) { pinned ->
                rootChannels = pinned.toList()
                if (constructed) updateChannels()
            }
        }
        rebuildNodes()
        constructed = true
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(context)
        return when (viewType) {
            R.layout.channel_row -> ChannelViewHolder(ChannelRowBinding.inflate(inflater, viewGroup, false))
            R.layout.channel_user_row -> UserViewHolder(ChannelUserRowBinding.inflate(inflater, viewGroup, false))
            R.layout.channel_listener_row ->
                ListenerViewHolder(ChannelListenerRowBinding.inflate(inflater, viewGroup, false))
            else -> throw IllegalArgumentException("unknown view type $viewType")
        }
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val node = nodes[position]
        val channel = node.channel
        val user = node.user
        val listener = node.listener
        if (listener != null) {
            bindListener(viewHolder as ListenerViewHolder, node, listener)
        } else if (channel != null) {
            val cvh = viewHolder as ChannelViewHolder
            cvh.itemView.setOnClickListener {
                onChannelClick?.invoke(channel)
            }

            val expandUsable = node.hasSubchannels || node.subtreeUserCount > 0 || node.subtreeListenerCount > 0
            cvh.channelExpandToggle.setImageResource(
                if (node.isExpanded) R.drawable.ic_action_expanded
                else R.drawable.ic_action_collapsed
            )
            cvh.channelExpandToggle.contentDescription =
                context.getString(if (node.isExpanded) R.string.a11y_collapse else R.string.expand)
            cvh.channelExpandToggle.setOnClickListener {
                expandedChannels[channel.id] = !node.isExpanded
                updateChannels()
            }
            // Dim channel expand toggle when no subchannels exist
            cvh.channelExpandToggle.isEnabled = expandUsable
            cvh.channelExpandToggle.visibility = if (expandUsable) View.VISIBLE else View.INVISIBLE

            cvh.channelName.text = channel.name

            // Named flags rather than `or`: lint refuses combined Typeface @IntDef styles.
            var bold = false
            var italic = false
            val service = humlaService
            if (service.isConnected) {
                val session = service.session
                var ourChan: IChannel? = null
                try {
                    ourChan = session.sessionChannel
                } catch (e: IllegalStateException) {
                    Log.d(TAG, "exception in onBindViewHolder: $e")
                }
                if (ourChan != null) {
                    val links = channel.links
                    if (channel == ourChan) {
                        bold = true
                        // Always italicize our current channel if it has a link.
                        if (links.isNotEmpty()) {
                            italic = true
                        }
                    }
                    // Italicize channels in a link with our current channel.
                    if (links.contains(ourChan)) {
                        italic = true
                    }
                }
            }
            cvh.channelName.setTypeface(
                null,
                when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                },
            )

            if (showChannelUserCount) {
                cvh.channelUserCount.visibility = View.VISIBLE
                cvh.channelUserCount.text = String.format("%d", node.subtreeUserCount)
            } else {
                cvh.channelUserCount.visibility = View.GONE
            }

            // Pad the view depending on channel's nested level.
            val metrics = context.resources.displayMetrics
            val margin = node.depth *
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 25f, metrics)
            cvh.channelHolder.setPadding(
                margin.toInt(),
                cvh.channelHolder.paddingTop,
                cvh.channelHolder.paddingRight,
                cvh.channelHolder.paddingBottom,
            )

            bindEnterRestriction(cvh, channel)
            cvh.joinButton.setOnClickListener {
                val current = humlaService
                if (current.isConnected) {
                    current.session.joinOrExplain(context, channel)
                }
            }

            cvh.moreButton.setOnClickListener { v ->
                ChannelMenu(context, channel, humlaService, repository.pinnedChannels, fragmentManager).showPopup(v)
            }

            cvh.itemView.setOnLongClickListener {
                cvh.moreButton.performClick()
                true
            }
        } else if (user != null) {
            val uvh = viewHolder as UserViewHolder
            uvh.itemView.setOnClickListener {
                onUserClick?.invoke(user)
            }

            uvh.userName.text = user.name

            var selfSession = -1
            val service = humlaService
            try {
                selfSession = service.session.sessionId
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "exception in onBindViewHolder: $e")
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onBindViewHolder: $e")
            }

            uvh.userName.setTypeface(
                null,
                if (service.isConnected && user.session == selfSession) {
                    Typeface.BOLD
                } else {
                    Typeface.NORMAL
                },
            )

            bindTalkState(uvh, user)

            // Pad the view depending on channel's nested level.
            val metrics = context.resources.displayMetrics
            val margin = (node.depth + 1) *
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 25f, metrics)
            uvh.userHolder.setPadding(
                margin.toInt(),
                uvh.userHolder.paddingTop,
                uvh.userHolder.paddingRight,
                uvh.userHolder.paddingBottom,
            )

            uvh.moreButton.setOnClickListener { v ->
                UserMenu(context, user, humlaService, fragmentManager, ::onLocalUserStateUpdated).showPopup(v)
            }

            uvh.itemView.setOnLongClickListener {
                uvh.moreButton.performClick()
                true
            }
        }
    }

    /** A listener's row: the user's name, and for the local user's own listener a stop button. */
    private fun bindListener(lvh: ListenerViewHolder, node: Node, listener: IUser) {
        val channel = checkNotNull(node.parent?.channel) { "A listener row always hangs under its channel" }
        lvh.name.text = listener.name
        lvh.itemView.contentDescription = context.getString(R.string.a11y_listener, listener.name)
        val service = humlaService
        val own = service.isConnected && try {
            service.session.sessionId == listener.session
        } catch (e: IllegalStateException) {
            Log.d(TAG, "exception in bindListener: $e")
            false
        }
        lvh.stop.visibility = if (own) View.VISIBLE else View.GONE
        lvh.stop.setOnClickListener {
            val current = humlaService
            if (current.isConnected) current.session.setListening(channel.id, false)
        }
        val metrics = context.resources.displayMetrics
        val margin = (node.depth + 1) * TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 25f, metrics)
        lvh.holder.setPadding(margin.toInt(), lvh.holder.paddingTop, lvh.holder.paddingRight, lvh.holder.paddingBottom)
    }

    override fun getItemCount(): Int = nodes.size

    override fun getItemViewType(position: Int): Int {
        val node = nodes[position]
        return when {
            node.listener != null -> R.layout.channel_listener_row
            node.channel != null -> R.layout.channel_row
            node.user != null -> R.layout.channel_user_row
            else -> 0
        }
    }

    override fun getItemId(position: Int): Long = nodes[position].nodeId ?: -1L

    /**
     * Schedules a rebuild of the channel tree. Runs once at the end of the current main-thread
     * turn, however often this is called meanwhile.
     */
    fun updateChannels() {
        if (rebuildScheduled) {
            return
        }
        rebuildScheduled = true
        mainHandler.post(rebuildRunnable)
    }

    /** Runs a scheduled rebuild now, so that a caller cannot read a tree the model has left. */
    private fun rebuildIfScheduled() {
        if (!rebuildScheduled) {
            return
        }
        mainHandler.removeCallbacks(rebuildRunnable)
        rebuildRunnable.run()
    }

    private fun rebuildNodes() {
        val service = humlaService
        if (!service.isConnected) {
            return
        }

        val session = service.session
        nodes.clear()
        try {
            for (cid in rootChannels) {
                val channel = session.getChannel(cid)
                if (channel != null) {
                    constructNodes(null, channel, 0, nodes)
                }
            }
        } catch (e: IllegalStateException) {
            Log.d(TAG, "exception in updateChannels: $e")
        }
    }

    /**
     * Repaints one user's state icon in place, without rebuilding the tree (the hot path for talk
     * and mute changes). Users without a laid-out row are ignored. No "icon changed?" guard:
     * the icons are layer lists whose `ConstantState` is per instance, so comparing it never
     * matches; a working guard would have to compare the resource.
     */
    fun updateUserStates(user: IUser, view: RecyclerView) {
        val itemId = user.session.toLong() or USER_ID_MASK
        val uvh = view.findViewHolderForItemId(itemId) as? UserViewHolder ?: return
        bindTalkState(uvh, user)
    }

    /** The lock of a channel with enter restrictions, closed if the local user may not enter. */
    private fun bindEnterRestriction(cvh: ChannelViewHolder, channel: IChannel) {
        if (!channel.isEnterRestricted && channel.canEnter) {
            cvh.lock.visibility = View.GONE
            return
        }
        cvh.lock.visibility = View.VISIBLE
        cvh.lock.setImageResource(if (channel.canEnter) R.drawable.ic_lock_open else R.drawable.ic_lock)
        cvh.lock.contentDescription =
            context.getString(if (channel.canEnter) R.string.a11y_channel_restricted else R.string.a11y_channel_locked)
    }

    /** The talk-state icon, and for accessibility services the row's state in words. */
    private fun bindTalkState(uvh: UserViewHolder, user: IUser) {
        uvh.userTalkHighlight.setImageDrawable(getTalkStateDrawable(user))
        ViewCompat.setStateDescription(uvh.itemView, talkStateDescription(context, user))
    }

    private fun getTalkStateDrawable(user: IUser): Drawable {
        val resources = context.resources
        val id = when {
            user.isSelfDeafened -> R.drawable.outline_circle_deafened
            user.isDeafened -> R.drawable.outline_circle_server_deafened
            user.isSelfMuted -> R.drawable.outline_circle_muted
            user.isMuted -> R.drawable.outline_circle_server_muted
            user.isSuppressed -> R.drawable.outline_circle_suppressed
            user.talkState == TalkState.TALKING ||
                    user.talkState == TalkState.SHOUTING ||
                    user.talkState == TalkState.WHISPERING ->
                // TODO whisper and shouting?
                R.drawable.outline_circle_talking_on
            else -> {
                // Passive drawables
                val texture = user.texture
                if (texture != null) {
                    // FIXME: cache bitmaps
                    val bitmap = BitmapFactory.decodeByteArray(texture, 0, texture.size)
                    // yes, decoding can fail
                    if (bitmap != null) {
                        return CircleDrawable(resources, bitmap)
                    }
                }
                // "default" symbol, used also if bitmap decoding fails
                R.drawable.outline_circle_talking_off
            }
        }
        return ResourcesCompat.getDrawable(resources, id, null)!!
    }

    /**
     * The list position of a user's row, or -1.
     *
     * Runs a rebuild scheduled in this turn first, which notifies. Do not call this during layout,
     * item animation or scrolling: `notifyDataSetChanged()` throws `IllegalStateException` there.
     */
    fun getUserPosition(session: Int): Int {
        rebuildIfScheduled()
        val itemId = session.toLong() or USER_ID_MASK
        return nodes.indexOfFirst { it.nodeId == itemId }
    }

    /**
     * The list position of a channel's row, or -1. Same caveat as [getUserPosition]: not during
     * layout or scrolling.
     */
    fun getChannelPosition(channelId: Int): Int {
        rebuildIfScheduled()
        val itemId = channelId.toLong() or CHANNEL_ID_MASK
        return nodes.indexOfFirst { it.nodeId == itemId }
    }

    /** The list position of [session]'s listener row under [channelId], or -1. Same caveat as [getUserPosition]. */
    fun getListenerPosition(channelId: Int, session: Int): Int {
        rebuildIfScheduled()
        val itemId = listenerId(channelId, session)
        return nodes.indexOfFirst { it.nodeId == itemId }
    }

    /** Sets whether to show the channel user count in a channel row. */
    fun setShowChannelUserCount(showUserCount: Boolean) {
        showChannelUserCount = showUserCount
        notifyDataSetChanged()
    }

    /**
     * Appends the [Node]s for [channel] and its subtree to [nodes] and returns the channel's node,
     * which carries the subtree counts. The subtree is appended first and dropped again if the
     * channel is contracted, because that is only known once its users have been counted. A user
     * the model has not filled in yet gets no row but is counted, matching
     * `Channel.subchannelUserCount`. Listeners get rows after the users; they are not counted as
     * users, but keep a channel expanded by default.
     */
    private fun constructNodes(
        parent: Node?,
        channel: IChannel,
        depth: Int,
        nodes: MutableList<Node>,
    ): Node {
        val channelNode = Node(parent, depth, channel)
        nodes.add(channelNode)
        val subtreeStart = nodes.size

        var userCount = 0
        for (user in channel.users) {
            userCount++
            if (user == null) {
                continue
            }
            nodes.add(Node(channelNode, depth, user))
        }
        val listeners = channel.listeners
        for (listener in listeners) {
            nodes.add(Node.listener(channelNode, depth, listener))
        }
        var listenerCount = listeners.size
        val subchannels = channel.subchannels
        channelNode.hasSubchannels = subchannels.isNotEmpty()
        for (subc in subchannels) {
            val subNode = constructNodes(channelNode, subc, depth + 1, nodes)
            userCount += subNode.subtreeUserCount
            listenerCount += subNode.subtreeListenerCount
        }
        channelNode.subtreeUserCount = userCount
        channelNode.subtreeListenerCount = listenerCount

        val expandSetting = expandedChannels[channel.id]
        if (expandSetting ?: (userCount != 0 || listenerCount != 0)) {
            return channelNode
        }
        channelNode.isExpanded = false
        // Contracted or empty: the subtree was walked to count it, but it is not shown.
        nodes.subList(subtreeStart, nodes.size).clear()
        return channelNode
    }

    /** Changes the service backing the adapter and updates the list. */
    fun setService(service: IHumlaService) {
        humlaService = service
        if (service.connectionState == HumlaService.ConnectionState.CONNECTED) {
            updateChannels()
        }
    }

    /**
     * Redraws [user]'s local mute and ignore, and stores them for a registered user of a saved
     * server; stores the local volume for anyone [LocalVolumes.keyOf] can identify.
     */
    fun onLocalUserStateUpdated(user: IUser) {
        notifyDataSetChanged()

        val server = humlaService.targetServer
        LocalVolumes.keyOf(user, server)?.let { key ->
            val volume = user.localVolume
            repository.launchIo { setLocalVolume(key, volume) }
        }

        // Add or remove registered user from local mute history

        if (server != null && user.userId >= 0 && server.isSaved) {
            repository.launchIo {
                if (user.isLocalMuted) {
                    addLocalMutedUser(server.id, user.userId)
                } else {
                    removeLocalMutedUser(server.id, user.userId)
                }
                if (user.isLocalIgnored) {
                    addLocalIgnoredUser(server.id, user.userId)
                } else {
                    removeLocalIgnoredUser(server.id, user.userId)
                }
            }
        }
    }

    private class UserViewHolder(binding: ChannelUserRowBinding) : RecyclerView.ViewHolder(binding.root) {
        val userHolder: LinearLayout = binding.userRowTitle
        val userTalkHighlight: ImageView = binding.userRowTalkHighlight
        val userName: TextView = binding.userRowName
        val moreButton: ImageView = binding.userRowMore
    }

    private class ChannelViewHolder(binding: ChannelRowBinding) : RecyclerView.ViewHolder(binding.root) {
        val channelHolder: LinearLayout = binding.channelRowTitle
        val channelExpandToggle: ImageView = binding.channelRowExpand
        val channelName: TextView = binding.channelRowName
        val channelUserCount: TextView = binding.channelRowCount
        val lock: ImageView = binding.channelRowLock
        val joinButton: ImageView = binding.channelRowJoin
        val moreButton: ImageView = binding.channelRowMore
    }

    private class ListenerViewHolder(binding: ChannelListenerRowBinding) : RecyclerView.ViewHolder(binding.root) {
        val holder: LinearLayout = binding.listenerRowTitle
        val name: TextView = binding.listenerRowName
        val stop: ImageView = binding.listenerRowStop
    }

    /** A channel, user or listener row in the flattened hierarchy. A listener's parent is its channel. */
    private class Node private constructor(
        val parent: Node?,
        val depth: Int,
        val channel: IChannel?,
        val user: IUser?,
        val listener: IUser?,
    ) {
        var isExpanded: Boolean = channel != null

        /** Users in this channel and everything below it, as of the rebuild that made this node. */
        var subtreeUserCount: Int = 0

        /** Listeners in this channel and everything below it. */
        var subtreeListenerCount: Int = 0
        var hasSubchannels: Boolean = false

        constructor(parent: Node?, depth: Int, channel: IChannel) : this(parent, depth, channel, null, null)

        constructor(parent: Node?, depth: Int, user: IUser) : this(parent, depth, null, user, null)

        /** Applies flags to differentiate integer-length identifiers. */
        val nodeId: Long?
            get() = when {
                listener != null -> listenerId(checkNotNull(parent?.channel).id, listener.session)
                channel != null -> CHANNEL_ID_MASK or channel.id.toLong()
                user != null -> USER_ID_MASK or user.session.toLong()
                else -> null
            }

        companion object {
            fun listener(channelNode: Node, depth: Int, listener: IUser) =
                Node(channelNode, depth, null, null, listener)
        }
    }

    companion object {
        private val TAG: String = ChannelListAdapter::class.java.name

        // Set particular bits to make the integer-based model item ids unique.
        const val CHANNEL_ID_MASK = 0x1L shl 32
        const val USER_ID_MASK = 0x1L shl 33
        private const val LISTENER_ID_MASK = 0x1L shl 62
        private const val ID_BITS = 31
        private const val ID_MASK = (1L shl ID_BITS) - 1

        /** A listener row's id: channel ids and sessions below 2^31 never collide. */
        private fun listenerId(channelId: Int, session: Int): Long =
            LISTENER_ID_MASK or ((channelId.toLong() and ID_MASK) shl ID_BITS) or (session.toLong() and ID_MASK)
    }
}
