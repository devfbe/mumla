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
package se.lublin.humla.model

import java.util.Collections

/**
 * A channel of the server tree. Mutated on the protocol thread and read from the main and binder
 * threads, so scalar fields are volatile, list mutations are synchronized and list reads return
 * unmodifiable snapshot copies.
 *
 * The id is immutable: it is the [hashCode].
 *
 * A read snapshots one list, not the tree: a channel can exist while its subchannels are still
 * arriving. The UI redraws on the next `ChannelAdded` event, so a half-built subtree is only a frame
 * late.
 */
class Channel(id: Int = 0, temporary: Boolean = false) : IChannel, Comparable<Channel> {
    override val id: Int = id
    @Volatile override var position = 0
    @Volatile override var isTemporary = temporary
    @Volatile override var parent: Channel? = null
    @Volatile override var name: String? = null
    @Volatile override var description: String? = null
    @Volatile override var descriptionHash: ByteArray? = null
    @Volatile override var permissions = 0
    @Volatile override var isEnterRestricted = false
    @Volatile override var canEnter = true
    private val mSubchannels = ArrayList<Channel>() // guarded by this
    private val mUsers = ArrayList<User>() // guarded by this
    private val mLinks = ArrayList<Channel>() // guarded by this
    private val mListeners = ArrayList<User>() // guarded by this

    /** @see User.setChannel */
    @Synchronized
    internal fun addUser(user: User) {
        for (i in mUsers.indices) {
            if (user.compareTo(mUsers[i]) <= 0) {
                mUsers.add(i, user)
                return
            }
        }
        mUsers.add(user)
    }

    /** @see User.setChannel */
    @Synchronized
    internal fun removeUser(user: User) {
        mUsers.remove(user)
    }

    override val users: List<User>
        @Synchronized get() = Collections.unmodifiableList(ArrayList(mUsers))

    override val listeners: List<User>
        @Synchronized get() = Collections.unmodifiableList(ArrayList(mListeners))

    /** Adds [user] as a listener at its sorted position; a present listener is not added twice. */
    @Synchronized
    fun addListener(user: User) {
        if (user in mListeners) return
        val index = mListeners.indexOfFirst { user <= it }
        if (index < 0) mListeners.add(user) else mListeners.add(index, user)
    }

    @Synchronized
    fun removeListener(user: User): Boolean = mListeners.remove(user)

    override val subchannels: List<Channel>
        @Synchronized get() = Collections.unmodifiableList(ArrayList(mSubchannels))

    /**
     * Inserts [channel] at its sorted position. A null channel is ignored: the server can name a
     * parent or a link we have no `ChannelState` for yet, and [ModelHandler] passes the lookup
     * result straight through.
     */
    @Synchronized
    fun addSubchannel(channel: Channel?) {
        if (channel == null) return
        for (i in mSubchannels.indices) {
            if (channel.compareTo(mSubchannels[i]) <= 0) {
                mSubchannels.add(i, channel)
                return
            }
        }
        mSubchannels.add(channel)
    }

    @Synchronized
    fun removeSubchannel(channel: Channel?) {
        if (channel != null) mSubchannels.remove(channel)
    }

    override val links: List<Channel>
        @Synchronized get() = Collections.unmodifiableList(ArrayList(mLinks))

    /** @see addSubchannel for why a null channel is ignored rather than rejected. */
    @Synchronized
    fun addLink(channel: Channel?) {
        if (channel == null) return
        for (i in mLinks.indices) {
            if (channel.compareTo(mLinks[i]) <= 0) {
                mLinks.add(i, channel)
                return
            }
        }
        mLinks.add(channel)
    }

    @Synchronized
    fun removeLink(channel: Channel?) {
        if (channel != null) mLinks.remove(channel)
    }

    /**
     * Replaces the whole link set atomically, ignoring channels we have no `ChannelState` for yet.
     * Readers never see an emptied intermediate list (the UI would blink the linked-channel style).
     */
    @Synchronized
    fun setLinks(links: Collection<Channel?>) {
        mLinks.clear()
        for (link in links) addLink(link)
    }

    /**
     * Recursively fetches the subchannel user count, holding one channel's lock at a time: the
     * subchannels are copied under the lock and the recursion happens outside it, so no thread ever
     * holds two channel locks at once (no lock-order inversion possible).
     */
    override val subchannelUserCount: Int
        get() {
            val direct: Int
            val children: List<Channel>
            synchronized(this) {
                direct = mUsers.size
                children = ArrayList(mSubchannels)
            }
            return direct + children.sumOf { it.subchannelUserCount }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        return id == (other as Channel).id
    }

    override fun hashCode(): Int = id

    /** Orders by position, then case-sensitively by name, with nameless (stub) channels first. */
    override fun compareTo(other: Channel): Int {
        if (position != other.position) return position.compareTo(other.position)
        return (name ?: "").compareTo(other.name ?: "")
    }
}
