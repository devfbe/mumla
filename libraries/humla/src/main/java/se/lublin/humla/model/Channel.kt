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
 * The id is immutable: it is the [hashCode], and `HumlaCallbacks` keys queued refreshes on the
 * channel object.
 *
 * A read snapshots one list, not the tree: a channel can exist while its subchannels are still
 * arriving. The UI redraws on the next `onChannelAdded`, so a half-built subtree is only a frame
 * late.
 */
class Channel @JvmOverloads constructor(id: Int = 0, temporary: Boolean = false) : IChannel, Comparable<Channel> {
    private val mId = id
    @Volatile private var mPosition = 0
    @Volatile private var mTemporary = temporary
    @Volatile private var mParent: Channel? = null
    @Volatile private var mName: String? = null
    @Volatile private var mDescription: String? = null
    @Volatile private var mDescriptionHash: ByteArray? = null
    @Volatile private var mPermissions = 0
    private val mSubchannels = ArrayList<Channel>() // guarded by this
    private val mUsers = ArrayList<User>() // guarded by this
    private val mLinks = ArrayList<Channel>() // guarded by this

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

    @Synchronized
    override fun getUsers(): List<User> = Collections.unmodifiableList(ArrayList(mUsers))

    override fun getId(): Int = mId

    override fun getPosition(): Int = mPosition

    fun setPosition(position: Int) {
        mPosition = position
    }

    override fun isTemporary(): Boolean = mTemporary

    fun setTemporary(temporary: Boolean) {
        mTemporary = temporary
    }

    override fun getParent(): Channel? = mParent

    fun setParent(parent: Channel?) {
        mParent = parent
    }

    override fun getName(): String? = mName

    fun setName(name: String?) {
        mName = name
    }

    override fun getDescription(): String? = mDescription

    fun setDescription(description: String?) {
        mDescription = description
    }

    override fun getDescriptionHash(): ByteArray? = mDescriptionHash

    fun setDescriptionHash(descriptionHash: ByteArray?) {
        mDescriptionHash = descriptionHash
    }

    @Synchronized
    override fun getSubchannels(): List<Channel> = Collections.unmodifiableList(ArrayList(mSubchannels))

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

    @Synchronized
    override fun getLinks(): List<Channel> = Collections.unmodifiableList(ArrayList(mLinks))

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
     *
     * FIXME: is it necessary to cache this?
     * @return The sum of users in this channel and its subchannels.
     */
    override fun getSubchannelUserCount(): Int {
        val direct: Int
        val subchannels: List<Channel>
        synchronized(this) {
            direct = mUsers.size
            subchannels = ArrayList(mSubchannels)
        }
        return direct + subchannels.sumOf { it.getSubchannelUserCount() }
    }

    override fun getPermissions(): Int = mPermissions

    fun setPermissions(permissions: Int) {
        mPermissions = permissions
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        return mId == (other as Channel).mId
    }

    override fun hashCode(): Int = mId

    /** Orders by position, then case-sensitively by name, with nameless (stub) channels first. */
    override fun compareTo(other: Channel): Int {
        if (mPosition != other.getPosition()) return mPosition.compareTo(other.getPosition())
        return (mName ?: "").compareTo(other.getName() ?: "")
    }
}
