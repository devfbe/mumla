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

class Channel @JvmOverloads constructor(id: Int = 0, temporary: Boolean = false) : IChannel, Comparable<Channel> {
    private var mId = id
    private var mPosition = 0
    private var mTemporary = temporary
    private var mParent: Channel? = null
    private var mName: String? = null
    private var mDescription: String? = null
    private var mDescriptionHash: ByteArray? = null
    private val mSubchannels = ArrayList<Channel>()
    private val mUsers = ArrayList<User>()
    private val mLinks = ArrayList<Channel>()
    private var mPermissions = 0

    /** @see User.setChannel */
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
    internal fun removeUser(user: User) {
        mUsers.remove(user)
    }

    override fun getUsers(): List<User> = Collections.unmodifiableList(mUsers)

    override fun getId(): Int = mId

    fun setId(id: Int) {
        mId = id
    }

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

    override fun getSubchannels(): List<Channel> = Collections.unmodifiableList(mSubchannels)

    /**
     * Inserts [channel] at its sorted position. A null channel is ignored: the server can name a
     * parent or a link we have no `ChannelState` for yet, and [ModelHandler] passes the lookup
     * result straight through.
     */
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

    fun removeSubchannel(channel: Channel?) {
        if (channel != null) mSubchannels.remove(channel)
    }

    override fun getLinks(): List<Channel> = Collections.unmodifiableList(mLinks)

    /** @see addSubchannel for why a null channel is ignored rather than rejected. */
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

    fun removeLink(channel: Channel?) {
        if (channel != null) mLinks.remove(channel)
    }

    fun clearLinks() {
        mLinks.clear()
    }

    /**
     * Recursively fetches the subchannel user count.
     * FIXME: is it necessary to cache this?
     * @return The sum of users in this channel and its subchannels.
     */
    override fun getSubchannelUserCount(): Int {
        var userCount = mUsers.size
        for (sub in mSubchannels) userCount += sub.getSubchannelUserCount()
        return userCount
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

    /**
     * Orders by position, then case-sensitively by name, with nameless channels first. The Java
     * original dereferenced both names and threw for a channel that has none - reachable through
     * [addSubchannel] and [addLink] for the stub channel `ModelHandler.createStubChannel` creates.
     */
    override fun compareTo(other: Channel): Int {
        if (mPosition != other.getPosition()) return mPosition.compareTo(other.getPosition())
        return (mName ?: "").compareTo(other.getName() ?: "")
    }
}
