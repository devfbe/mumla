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
 * A channel of the server tree. Mutated on the protocol thread and read from the main thread (and
 * from the binder thread `ChannelSearchProvider` runs on), so scalar fields are volatile, list
 * mutations are synchronized and list reads return snapshots (spec A1, "guarded model").
 *
 * A read hands back an unmodifiable *copy*. The Java original handed back an unmodifiable *view*,
 * so callers outside this library already treat the result as read-only; dropping that half of the
 * contract while adding the copy would turn a caller's mistaken write from an exception into a
 * change that silently goes nowhere.
 *
 * The id is the one thing that never changes. It had a setter with no callers, and the setter was
 * not harmless: [hashCode] is the id, and `HumlaCallbacks` keys its folded state refreshes on the
 * channel object. Changing the id under a queued refresh would strand that entry in the fold map -
 * it would never be found again, so the refresh would leak and folding would quietly stop working
 * for that channel. Deleting the setter is what makes that unrepresentable; `ModelHandler` creates
 * a channel with its id and never renumbers one.
 *
 * What a reader gets is a snapshot of one list, not of the tree: a channel can exist while its
 * subchannels are still arriving, and that is deliberate. The alternative - a tree-wide lock held
 * across a `ChannelState` frame - would make the protocol thread wait on every list read the UI
 * takes, which is the cost task 4 exists to avoid. The UI already redraws on the next
 * `onChannelAdded`, so a half-built subtree is a frame late, not wrong.
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
     * Replaces the whole link set in one step, ignoring channels we have no `ChannelState` for yet.
     *
     * This exists instead of a `clearLinks()` the caller follows with N `addLink` calls, because
     * that sequence made the emptied list visible to the main thread: `ChannelListAdapter`
     * italicises a channel that is linked to ours (`:167`, `:172`), so a re-announced link set made
     * the italics blink off and on. It is also the only shape of the operation a test can hold to
     * account - the window in a `clear()` that another thread can observe is a few nanoseconds
     * wide, while a half-rebuilt list lasts as long as the rebuild.
     *
     * The nested [addLink] calls re-enter this object's monitor, which is what keeps the rebuild
     * atomic for every reader.
     */
    @Synchronized
    fun setLinks(links: Collection<Channel?>) {
        mLinks.clear()
        for (link in links) addLink(link)
    }

    /**
     * Recursively fetches the subchannel user count, holding one channel's lock at a time: the
     * subchannels are copied under the lock and the recursion happens outside it, so no thread ever
     * holds two channel locks at once.
     *
     * Two decisions, and only one of them is pinned. **The lock is load-bearing**: without it the
     * copy can include a slot `fastRemove` has already nulled (`es[size = newSize] = null`) and the
     * recursion throws a NullPointerException on the main thread - `ChannelTest`'s
     * `countingUsersRecursivelyWhileTheTreeChangesNeitherThrowsNorDoubleCounts` goes red in every
     * run when it is taken away - 341 to 756 of its 20 000 observations throw, over 11 runs.
     * **Releasing it before recursing** is the part no test can tell from a plain `@Synchronized`,
     * and that is measured rather than asserted: written as a plain `@Synchronized` method over
     * `mUsers.size` and a loop across `mSubchannels`, `ChannelTest` stays 12 of 12 green in three
     * runs out of three. Nothing else in this class nests two locks today, so there is nothing to
     * observe; it is written this way so that a later member which does take a second lock cannot
     * turn this into a lock-order inversion.
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
