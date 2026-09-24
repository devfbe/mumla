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
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executor
import se.lublin.humla.HumlaService
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState
import se.lublin.humla.util.HumlaDisconnectedException
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.drawable.CircleDrawable
import se.lublin.mumla.service.MumlaService

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
 */
class ChannelListAdapter(
    private val context: Context,
    service: IHumlaService,
    private val database: MumlaDatabase,
    private val fragmentManager: FragmentManager,
    showPinnedOnly: Boolean,
    showUserCount: Boolean,
    /** Where the local mute/ignore rows are written; injectable so tests can run it inline. */
    private val databaseExecutor: Executor = Executor { Thread(it).start() },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), UserMenu.IUserLocalStateListener {

    private var humlaService: IHumlaService = service
    private val rootChannels: List<Int>
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
        rootChannels = if (showPinnedOnly) {
            humlaService.targetServer?.let { database.getPinnedChannels(it.id) }.orEmpty()
        } else {
            listOf(0)
        }
        rebuildNodes()
    }

    override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val view = inflater.inflate(viewType, viewGroup, false)
        return when (viewType) {
            R.layout.channel_row -> ChannelViewHolder(view)
            R.layout.channel_user_row -> UserViewHolder(view)
            else -> throw IllegalArgumentException("unknown view type $viewType")
        }
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val node = nodes[position]
        val channel = node.channel
        val user = node.user
        if (channel != null) {
            val cvh = viewHolder as ChannelViewHolder
            cvh.itemView.setOnClickListener {
                onChannelClick?.invoke(channel)
            }

            val expandUsable = node.hasSubchannels || node.subtreeUserCount > 0
            cvh.channelExpandToggle.setImageResource(
                if (node.isExpanded) R.drawable.ic_action_expanded
                else R.drawable.ic_action_collapsed
            )
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

            cvh.joinButton.setOnClickListener {
                val current = humlaService
                if (current.isConnected) {
                    current.session.joinChannel(channel.id)
                }
            }

            cvh.moreButton.setOnClickListener { v ->
                ChannelMenu(context, channel, humlaService, database, fragmentManager).showPopup(v)
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

            uvh.userTalkHighlight.setImageDrawable(getTalkStateDrawable(user))

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
                UserMenu(
                    context, user, humlaService as MumlaService, fragmentManager, this
                ).showPopup(v)
            }

            uvh.itemView.setOnLongClickListener {
                uvh.moreButton.performClick()
                true
            }
        }
    }

    override fun getItemCount(): Int = nodes.size

    override fun getItemViewType(position: Int): Int {
        val node = nodes[position]
        return when {
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
        uvh.userTalkHighlight.setImageDrawable(getTalkStateDrawable(user))
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

    /** Sets whether to show the channel user count in a channel row. */
    fun setShowChannelUserCount(showUserCount: Boolean) {
        showChannelUserCount = showUserCount
        notifyDataSetChanged()
    }

    /**
     * Appends the [Node]s for [channel] and its subtree to [nodes] and returns the number of users
     * in it. The subtree is appended first and dropped again if the channel is contracted, because
     * that is only known once its users have been counted. A user the model has not filled in yet
     * gets no row but is counted, matching `Channel.subchannelUserCount`.
     */
    private fun constructNodes(
        parent: Node?,
        channel: IChannel,
        depth: Int,
        nodes: MutableList<Node>,
    ): Int {
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
        val subchannels = channel.subchannels
        channelNode.hasSubchannels = subchannels.isNotEmpty()
        for (subc in subchannels) {
            userCount += constructNodes(channelNode, subc, depth + 1, nodes)
        }
        channelNode.subtreeUserCount = userCount

        val expandSetting = expandedChannels[channel.id]
        if (expandSetting ?: (userCount != 0)) {
            return userCount
        }
        channelNode.isExpanded = false
        // Contracted or empty: the subtree was walked to count it, but it is not shown.
        nodes.subList(subtreeStart, nodes.size).clear()
        return userCount
    }

    /** Changes the service backing the adapter and updates the list. */
    fun setService(service: IHumlaService) {
        humlaService = service
        if (service.connectionState == HumlaService.ConnectionState.CONNECTED) {
            updateChannels()
        }
    }

    override fun onLocalUserStateUpdated(user: IUser) {
        notifyDataSetChanged()

        // Add or remove registered user from local mute history
        val server = humlaService.targetServer

        if (server != null && user.userId >= 0 && server.isSaved) {
            databaseExecutor.execute {
                if (user.isLocalMuted) {
                    database.addLocalMutedUser(server.id, user.userId)
                } else {
                    database.removeLocalMutedUser(server.id, user.userId)
                }
                if (user.isLocalIgnored) {
                    database.addLocalIgnoredUser(server.id, user.userId)
                } else {
                    database.removeLocalIgnoredUser(server.id, user.userId)
                }
            }
        }
    }

    private class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val userHolder: LinearLayout = itemView.findViewById(R.id.user_row_title)
        val userTalkHighlight: ImageView = itemView.findViewById(R.id.user_row_talk_highlight)
        val userName: TextView = itemView.findViewById(R.id.user_row_name)
        val moreButton: ImageView = itemView.findViewById(R.id.user_row_more)
    }

    private class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val channelHolder: LinearLayout = itemView.findViewById(R.id.channel_row_title)
        val channelExpandToggle: ImageView = itemView.findViewById(R.id.channel_row_expand)
        val channelName: TextView = itemView.findViewById(R.id.channel_row_name)
        val channelUserCount: TextView = itemView.findViewById(R.id.channel_row_count)
        val joinButton: ImageView = itemView.findViewById(R.id.channel_row_join)
        val moreButton: ImageView = itemView.findViewById(R.id.channel_row_more)
    }

    /** A channel or user row in the flattened hierarchy. */
    private class Node {
        val parent: Node?
        val channel: IChannel?
        val user: IUser?
        val depth: Int
        var isExpanded: Boolean

        /** Users in this channel and everything below it, as of the rebuild that made this node. */
        var subtreeUserCount: Int = 0
        var hasSubchannels: Boolean = false

        constructor(parent: Node?, depth: Int, channel: IChannel) {
            this.parent = parent
            this.channel = channel
            this.user = null
            this.depth = depth
            this.isExpanded = true
        }

        constructor(parent: Node?, depth: Int, user: IUser) {
            this.parent = parent
            this.channel = null
            this.user = user
            this.depth = depth
            this.isExpanded = false
        }

        /** Applies flags to differentiate integer-length identifiers. */
        val nodeId: Long?
            get() = when {
                channel != null -> CHANNEL_ID_MASK or channel.id.toLong()
                user != null -> USER_ID_MASK or user.session.toLong()
                else -> null
            }
    }

    companion object {
        private val TAG: String = ChannelListAdapter::class.java.name

        // Set particular bits to make the integer-based model item ids unique.
        const val CHANNEL_ID_MASK = 0x1L shl 32
        const val USER_ID_MASK = 0x1L shl 33
    }
}
