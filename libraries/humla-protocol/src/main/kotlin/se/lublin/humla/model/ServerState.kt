/*
 * Copyright (C) 2026 The Mumla authors
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

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

/**
 * One immutable snapshot of a server's channels and users. Readable from any thread; a newer
 * snapshot shares everything that did not change with this one.
 *
 * The tree is kept as ids: a channel's subchannels in display order (position, then name), the
 * users in a channel and its listeners by name, ignoring case.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One persistent structure per index; the tree's read API.
public class ServerState internal constructor(
    /** The local user's session, known from ServerSync on; null before. */
    public val selfSession: Int?,
    internal val channelMap: PersistentMap<Int, ChannelState>,
    internal val userMap: PersistentMap<Int, UserState>,
    internal val children: PersistentMap<Int, PersistentList<Int>>,
    internal val members: PersistentMap<Int, PersistentList<Int>>,
    internal val listeners: PersistentMap<Int, PersistentList<Int>>,
    /** The server-wide permissions: those of the root channel, see `se.lublin.humla.net.Permissions`. */
    public val permissions: Int,
    /** The server's `ServerConfig`, or null before it arrived. */
    public val serverSettings: ServerSettings?,
    /** What this device remembers about users; applied as they appear. */
    internal val local: LocalUserSettings,
) {
    public val channels: Map<Int, ChannelState> get() = channelMap
    public val users: Map<Int, UserState> get() = userMap

    public val root: ChannelState? get() = channelMap[ROOT_CHANNEL_ID]
    public val self: UserState? get() = selfSession?.let(userMap::get)
    public val selfChannel: ChannelState? get() = self?.let { channelMap[it.channel] }

    public fun channel(id: Int): ChannelState? = channelMap[id]

    public fun user(session: Int): UserState? = userMap[session]

    internal fun subchannelIds(channel: Int): List<Int> = children[channel].orEmpty()

    internal fun userIds(channel: Int): List<Int> = members[channel].orEmpty()

    internal fun listenerIds(channel: Int): List<Int> = listeners[channel].orEmpty()

    public fun subchannels(channel: Int): List<ChannelState> = subchannelIds(channel).mapNotNull(channelMap::get)

    public fun usersIn(channel: Int): List<UserState> = userIds(channel).mapNotNull(userMap::get)

    public fun listenersOf(channel: Int): List<UserState> = listenerIds(channel).mapNotNull(userMap::get)

    /** The users in [channel] and every channel below it. */
    public fun subtreeUserCount(channel: Int): Int =
        userIds(channel).size + subchannelIds(channel).sumOf(::subtreeUserCount)

    /** The permissions in [channel]; the root's are the server-wide ones. */
    public fun permissionsIn(channel: Int): Int =
        if (channel == ROOT_CHANNEL_ID) permissions else channelMap[channel]?.permissions ?: 0

    /** [channel] and every channel below it, depth first, each parent before its subchannels. */
    public fun flatten(channel: Int = ROOT_CHANNEL_ID): List<ChannelState> = buildList {
        fun visit(id: Int) {
            channelMap[id]?.let(::add) ?: return
            subchannelIds(id).forEach(::visit)
        }
        visit(channel)
    }

    public companion object {
        /** The id Mumble gives the root channel. */
        public const val ROOT_CHANNEL_ID: Int = 0

        /** Subchannel order: position, then name, with nameless stubs first. */
        internal fun compareChannels(a: ChannelState?, b: ChannelState?): Int {
            val byPosition = (a?.position ?: 0).compareTo(b?.position ?: 0)
            return if (byPosition != 0) byPosition else (a?.name ?: "").compareTo(b?.name ?: "")
        }

        /** User and listener order: by name, ignoring case, with nameless users first. */
        internal fun compareUsers(a: UserState?, b: UserState?): Int =
            (a?.name ?: "").compareTo(b?.name ?: "", ignoreCase = true)

        /**
         * The snapshot of these channels and users, with the tree derived from their parents,
         * channels and listening channels, for a client that assembles one itself.
         */
        public fun of(
            channels: Collection<ChannelState>,
            users: Collection<UserState> = emptyList(),
            selfSession: Int? = null,
            permissions: Int = 0,
            serverSettings: ServerSettings? = null,
        ): ServerState {
            val channelMap = channels.associateBy { it.id }
            val children = channels.filter { it.parent in channelMap }.groupBy { it.parent!! }
                .mapValues { (_, list) -> list.sortedWith(::compareChannels).map { it.id }.toPersistentList() }
            val members = users.groupBy { it.channel }
                .mapValues { (_, list) -> list.sortedWith(::compareUsers).map { it.session }.toPersistentList() }
            val listeners = users.flatMap { user -> user.listening.map { it to user } }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, list) -> list.sortedWith(::compareUsers).map { it.session }.toPersistentList() }
            return ServerState(
                selfSession, channelMap.toPersistentMap(), users.associateBy { it.session }.toPersistentMap(),
                children.toPersistentMap(), members.toPersistentMap(), listeners.toPersistentMap(),
                permissions, serverSettings, LocalUserSettings(),
            )
        }

        /** A server nothing is known of yet. */
        public fun empty(): ServerState = empty(LocalUserSettings())

        /** A server nothing is known of yet, with what this device remembers about users. */
        internal fun empty(local: LocalUserSettings): ServerState = ServerState(
            selfSession = null,
            channelMap = persistentMapOf(),
            userMap = persistentMapOf(),
            children = persistentMapOf(),
            members = persistentMapOf(),
            listeners = persistentMapOf(),
            permissions = 0,
            serverSettings = null,
            local = local,
        )
    }
}

/**
 * What this device keeps for other users across their sessions: local mutes and message ignores
 * of registered users by user id, and playback volumes by [localVolumeKey].
 */
internal data class LocalUserSettings(
    val volumes: Map<String, Float> = emptyMap(),
    val mutedUserIds: Set<Int> = emptySet(),
    val ignoredUserIds: Set<Int> = emptySet(),
    /** "host:port" of the server, which scopes volumes kept by name. */
    val serverScope: String? = null,
)

/**
 * Identifies [user] across sessions: by certificate hash when the server sent one, else by name
 * on the server of [serverScope] ("host:port"). Null if neither is known.
 */
public fun localVolumeKey(user: UserState, serverScope: String?): String? {
    val hash = user.hash
    val name = user.name
    return when {
        !hash.isNullOrEmpty() -> "cert:$hash"
        name != null && serverScope != null -> "name:$serverScope:$name"
        else -> null
    }
}

/** The scope [localVolumeKey] keys names by on [server]. */
public val Server.localVolumeScope: String get() = "$host:$port"
