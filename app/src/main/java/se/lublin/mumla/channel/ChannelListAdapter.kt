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
 * Created by andrew on 31/07/13.
 *
 * Every model observer answers a channel or user event with a full rebuild of this tree, and a
 * large server synchronisation delivers about a thousand events even after stream A's observer
 * queue bounds them. Two things keep that off the main thread's critical path:
 *
 * - **At most one rebuild per main-thread turn.** [updateChannels] only schedules; the burst of
 *   events that arrives in one turn collapses into a single walk. This is sound because a model
 *   event means "read the model again" and never carries a delta (spec 4.1) -- the rebuild
 *   answers whatever state the model is in when it runs.
 * - **One pass over the model per rebuild.** [constructNodes] carries the subtree user count back
 *   up instead of asking `IChannel.getSubchannelUserCount()`, which re-walks the whole subtree on
 *   every call and turned an O(n) walk into O(n*depth). Each channel's `getUsers()` and
 *   `getSubchannels()` are read exactly once, and the counts land on the [Node] so that binding a
 *   row reads no model at all.
 *
 * Measured on a 5 000-channel tree, desktop JVM, best of seven (see
 * `AdapterRebuildBenchmarkTest`): one rebuild cost 444 us and made 33 179 recursive-count node
 * visits, now 128 us and none. One synchronisation -- the 1 024 events that survive the observer
 * queue's cap -- cost 307 ms of main thread, now 0.2 ms. Binding one channel row walked the
 * channel's whole subtree twice; now it reads nothing from the model.
 *
 * The recursion needs no depth check: `ModelHandler` refuses a parent that is the channel itself
 * or one of its descendants, and one deeper than 256 below the root (spec 4.1).
 *
 * Main thread only, like every `RecyclerView.Adapter`.
 */
class ChannelListAdapter(
    private val context: Context,
    service: IHumlaService?,
    private val database: MumlaDatabase,
    private val fragmentManager: FragmentManager,
    showPinnedOnly: Boolean,
    showUserCount: Boolean,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), UserMenu.IUserLocalStateListener {

    private var humlaService: IHumlaService? = service
    private val rootChannels: List<Int>
    private val nodes: MutableList<Node> = ArrayList()

    /**
     * A mapping of user-set channel expansions.
     * If a key is not mapped, default to hiding empty channels.
     */
    private val expandedChannels = HashMap<Int, Boolean>()
    private var userClickListener: OnUserClickListener? = null
    private var channelClickListener: OnChannelClickListener? = null
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
            database.getPinnedChannels(humlaService!!.targetServer.id)
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
            // The Java version returned null here, which RecyclerView dereferences immediately.
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
                channelClickListener?.onChannelClick(channel)
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

            // Named flags rather than `or`: Typeface's styles are an @IntDef that lint refuses to
            // see combined, and BOLD_ITALIC is the constant for the one combination that exists.
            var bold = false
            var italic = false
            val service = humlaService
            if (service != null && service.isConnected) {
                val session = service.HumlaSession()
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
                if (current != null && current.isConnected) {
                    current.HumlaSession().joinChannel(channel.id)
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
                userClickListener?.onUserClick(user)
            }

            uvh.userName.text = user.name

            var selfSession = -1
            val service = humlaService
            try {
                if (service != null) {
                    selfSession = service.HumlaSession().sessionId
                }
            } catch (e: HumlaDisconnectedException) {
                Log.d(TAG, "exception in onBindViewHolder: $e")
            } catch (e: IllegalStateException) {
                Log.d(TAG, "exception in onBindViewHolder: $e")
            }

            uvh.userName.setTypeface(
                null,
                if (service != null && service.isConnected && user.session == selfSession) {
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
                    context, user, humlaService as MumlaService?, fragmentManager, this
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
     * Schedules a rebuild of the channel tree model.
     * To be used after any channel tree modifications.
     *
     * The rebuild runs once at the end of the current main-thread turn, however many times this
     * is called meanwhile. Nothing is lost by that: a rebuild reads the whole model, so it always
     * answers the newest state rather than the event that asked for it.
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
        if (service == null || !service.isConnected) {
            return
        }

        val session = service.HumlaSession()
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
     * Update a user's state icon
     * @param user The user to update.
     * @param view The view containing this adapter.
     */
    fun updateUserStates(user: IUser, view: RecyclerView) {
        val itemId = user.session.toLong() or USER_ID_MASK
        val uvh = view.findViewHolderForItemId(itemId) as? UserViewHolder ?: return
        val newState = getTalkStateDrawable(user)
        val state = uvh.userTalkHighlight.drawable.current.constantState
        if (state != null && state != newState.constantState) {
            uvh.userTalkHighlight.setImageDrawable(newState)
        }
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

    fun getUserPosition(session: Int): Int {
        rebuildIfScheduled()
        val itemId = session.toLong() or USER_ID_MASK
        return nodes.indexOfFirst { it.nodeId == itemId }
    }

    fun getChannelPosition(channelId: Int): Int {
        rebuildIfScheduled()
        val itemId = channelId.toLong() or CHANNEL_ID_MASK
        return nodes.indexOfFirst { it.nodeId == itemId }
    }

    fun setOnUserClickListener(listener: OnUserClickListener?) {
        userClickListener = listener
    }

    fun setOnChannelClickListener(listener: OnChannelClickListener?) {
        channelClickListener = listener
    }

    /**
     * Sets whether to show the channel user count in a channel row.
     */
    fun setShowChannelUserCount(showUserCount: Boolean) {
        showChannelUserCount = showUserCount
        notifyDataSetChanged()
    }

    /**
     * Recursively creates a list of [Node]s representing the channel hierarchy, and returns the
     * number of users in [channel] and everything below it.
     *
     * The subtree is appended first and dropped again if the channel turns out to be contracted,
     * because whether it is contracted is only known once its users have been counted. That keeps
     * the walk to one pass: `getUsers()` and `getSubchannels()` are read once per channel, and
     * nothing asks `IChannel.getSubchannelUserCount()`, which would re-walk the subtree.
     *
     * @param parent The parent node to propagate under.
     * @param channel The parent channel.
     * @param depth The current depth of the subtree.
     * @param nodes An accumulator to store generated nodes into.
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

    /**
     * Changes the service backing the adapter. Updates the list as well.
     * @param service The new service to retrieve channels from.
     */
    fun setService(service: IHumlaService) {
        humlaService = service
        if (service.connectionState == HumlaService.ConnectionState.CONNECTED) {
            updateChannels()
        }
    }

    override fun onLocalUserStateUpdated(user: IUser) {
        notifyDataSetChanged()

        // Add or remove registered user from local mute history
        val server = humlaService!!.targetServer

        if (user.userId >= 0 && server.isSaved) {
            Thread {
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
            }.start()
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

    /**
     * An arbitrary node in the channel-user hierarchy.
     * Can be either a channel or user.
     */
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
