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
package se.lublin.humla.protocol

import android.util.Log
import com.google.protobuf.MessageLite
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import se.lublin.humla.model.Bytes
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.ServerState.Companion.ROOT_CHANNEL_ID
import se.lublin.humla.model.UserState
import se.lublin.humla.model.localVolumeKey
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.HumlaEvent

/** A change the client makes to its own view of other users, applied by the model's writer. */
internal sealed interface LocalInput {
    val session: Int

    data class Mute(override val session: Int, val muted: Boolean) : LocalInput

    data class Ignore(override val session: Int, val ignored: Boolean) : LocalInput

    data class Volume(override val session: Int, val volume: Float) : LocalInput
}

/**
 * The model as a pure function: a snapshot and one message make the next snapshot. Notices for the
 * chat log go to `emit` as they arise.
 */
internal object ServerReducer {
    fun reduce(state: ServerState, msg: MessageLite, emit: (HumlaEvent) -> Unit): ServerState =
        ServerWriter(state).apply { onMessage(msg, emit) }.snapshot()

    fun reduce(state: ServerState, input: LocalInput): ServerState =
        ServerWriter(state).apply { onLocal(input) }.snapshot()
}

/**
 * Applies messages to the snapshot [published] last, and makes the next one on [snapshot]. The
 * persistent builders copy only the paths a message touches, and only once between two
 * snapshots, so a burst of frames costs about what it changes, not what the server holds.
 * Confined to one thread.
 */
@Suppress("TooManyFunctions") // One step per message kind and field group.
internal class ServerWriter(private var published: ServerState) {
    var selfSession = published.selfSession
        private set
    private var permissions = published.permissions
    private var serverSettings = published.serverSettings
    private var local = published.local
    private val channels = published.channelMap.builder()
    private val users = published.userMap.builder()
    private val children = published.children.builder()
    private val members = published.members.builder()
    private val listeners = published.listeners.builder()

    fun onMessage(msg: MessageLite, emit: (HumlaEvent) -> Unit) {
        when (msg) {
            is Mumble.ChannelState -> if (msg.hasChannelId()) channelState(msg)
            is Mumble.ChannelRemove -> removeChannel(msg.channelId)
            is Mumble.PermissionQuery -> permissionQuery(msg)
            is Mumble.UserState -> userState(msg, emit)
            is Mumble.UserRemove -> userRemove(msg, emit)
            is Mumble.ServerSync -> {
                emit(HumlaEvent.LogMessage(HumlaEvent.Level.INFO, msg.welcomeText))
                selfSession = msg.session
            }
            is Mumble.ServerConfig -> serverSettings = ServerSettings.from(msg)
        }
    }

    fun channel(id: Int): ChannelState? = channels[id]

    fun user(session: Int): UserState? = users[session]

    /** The current state, or the last snapshot again if nothing changed since. */
    fun snapshot(): ServerState {
        val next = ServerState(
            selfSession, channels.build(), users.build(), children.build(), members.build(), listeners.build(),
            permissions, serverSettings, local,
        )
        val last = published
        val unchanged = next.selfSession == last.selfSession && next.permissions == last.permissions &&
            next.serverSettings === last.serverSettings && next.local == last.local &&
            next.channelMap === last.channelMap && next.userMap === last.userMap &&
            next.children === last.children && next.members === last.members && next.listeners === last.listeners
        if (!unchanged) published = next
        return published
    }

    private val channelOrder = Comparator<Int> { a, b -> ServerState.compareChannels(channels[a], channels[b]) }

    private val userOrder = Comparator<Int> { a, b -> ServerState.compareUsers(users[a], users[b]) }

    private fun channelState(msg: Mumble.ChannelState) {
        val id = msg.channelId
        val known = channels[id]
        val before = known ?: ChannelState(id, isTemporary = msg.temporary)
        val channel = describe(before, msg).copy(
            name = if (msg.hasName()) msg.name else before.name,
            position = if (msg.hasPosition()) msg.position else before.position,
            isEnterRestricted = if (msg.hasIsEnterRestricted()) msg.isEnterRestricted else before.isEnterRestricted,
            canEnter = if (msg.hasCanEnter()) msg.canEnter else before.canEnter,
        )
        channels[id] = channel
        val parent = channel.parent
        if (msg.hasParent()) {
            hang(id, msg.parent)
        } else if (parent != null && (channel.name != before.name || channel.position != before.position)) {
            resort(children, parent, id, channelOrder)
        }
        links(id, msg)
    }

    /** A new description hash drops the description, a new description its hash. */
    private fun describe(channel: ChannelState, msg: Mumble.ChannelState): ChannelState = when {
        msg.hasDescription() -> channel.copy(description = msg.description, hasDescriptionHash = false)
        msg.hasDescriptionHash() -> channel.copy(description = null, hasDescriptionHash = true)
        else -> channel
    }

    /** Hangs [id] under [named], or where [fallbackParent] says if that is refused. */
    private fun hang(id: Int, named: Int) {
        stub(named)
        val parent = (if (mayHang(id, named)) named else fallbackParent(id)) ?: return
        val channel = channels.getValue(id)
        channel.parent?.let { remove(children, it, id) }
        channels[id] = channel.copy(parent = parent)
        insert(children, parent, id, channelOrder)
    }

    /**
     * Whether [id] may be hung under [parent]: not under itself or one of its descendants (a
     * cycle), nor under a parent already [MAX_CHANNEL_DEPTH] below the root.
     */
    private fun mayHang(id: Int, parent: Int): Boolean {
        var depth = 0
        var above: Int? = parent
        var refusal: String? = null
        while (above != null && refusal == null) {
            refusal = when {
                above == id -> "refusing to make channel $id its own ancestor"
                ++depth > MAX_CHANNEL_DEPTH -> "refusing to hang channel $id deeper than $MAX_CHANNEL_DEPTH"
                else -> null
            }
            above = channels[above]?.parent
        }
        refusal?.let { Log.w(TAG, it) }
        return refusal == null
    }

    /**
     * Where a channel goes when [mayHang] refuses the parent its frame names, or null to leave
     * it where it is. A parentless channel would never heal (the server does not resend its
     * state) and would be invisible with its users, since readers walk down from the root; so
     * a refused channel without a parent is hung under the root, if that is no cycle either.
     */
    private fun fallbackParent(id: Int): Int? {
        if (channels.getValue(id).parent != null) return null
        stub(ROOT_CHANNEL_ID)
        return ROOT_CHANNEL_ID.takeIf { mayHang(id, it) }
    }

    /**
     * A user or a subchannel may name a channel before its own state arrived: it gets a
     * nameless stub, which that state then fills in.
     */
    private fun stub(id: Int) {
        if (channels[id] == null) channels[id] = ChannelState(id)
    }

    /**
     * A full link set replaces the old one, keeping only channels we know; additions and
     * removals are mutual. The server sends each channel's full set during synchronization, so
     * a full set does not touch the other side.
     */
    private fun links(id: Int, msg: Mumble.ChannelState) {
        if (msg.linksCount > 0) {
            update(id) { it.copy(links = msg.linksList.filterTo(HashSet()) { link -> channels[link] != null }) }
        }
        for (link in msg.linksRemoveList) {
            if (channels[link] == null) continue
            update(id) { it.copy(links = it.links - link) }
            update(link) { it.copy(links = it.links - id) }
        }
        for (link in msg.linksAddList) {
            if (channels[link] == null) continue
            update(id) { it.copy(links = it.links + link) }
            update(link) { it.copy(links = it.links + id) }
        }
    }

    private inline fun update(id: Int, change: (ChannelState) -> ChannelState) {
        channels[id]?.let { channels[id] = change(it) }
    }

    /** The root is never removed. The channel's own subchannels and users are left as they are. */
    private fun removeChannel(id: Int) {
        if (id == ROOT_CHANNEL_ID) return
        val channel = channels.remove(id) ?: return
        channel.parent?.let { remove(children, it, id) }
        for (link in channel.links) update(link) { it.copy(links = it.links - id) }
    }

    private fun permissionQuery(msg: Mumble.PermissionQuery) {
        if (msg.flush) {
            for ((id, channel) in channels.entries.toList()) {
                if (channel.permissions != 0) channels[id] = channel.copy(permissions = 0)
            }
        }
        val id = msg.channelId
        update(id) { it.copy(permissions = msg.permissions) }
        if (id == ROOT_CHANNEL_ID && channels[id] != null) permissions = msg.permissions
    }

    private fun self(): UserState? = selfSession?.let { users[it] }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One step per field group, in protocol order.
    private fun userState(msg: Mumble.UserState, emit: (HumlaEvent) -> Unit) {
        val session = msg.session
        val known = users[session]
        if (known == null && !msg.hasName()) return
        val actor = if (msg.hasActor()) users[msg.actor] else null
        var user = known ?: newUser(msg)
        fun commit(next: UserState) {
            if (next == user) return
            user = next
            users[session] = next
        }

        if (msg.hasUserId()) {
            commit(
                user.copy(
                    userId = msg.userId,
                    isLocalMuted = user.isLocalMuted || msg.userId in local.mutedUserIds,
                    isLocalIgnored = user.isLocalIgnored || msg.userId in local.ignoredUserIds,
                )
            )
        }
        if (msg.hasHash()) commit(user.copy(hash = msg.hash))
        if (known == null) emit(HumlaEvent.UserJoinedServer(user.name))

        if (msg.hasSelfMute() || msg.hasSelfDeaf()) {
            commit(
                user.copy(
                    isSelfMuted = if (msg.hasSelfMute()) msg.selfMute else user.isSelfMuted,
                    isSelfDeafened = if (msg.hasSelfDeaf()) msg.selfDeaf else user.isSelfDeafened,
                )
            )
            self()?.let { UserNotices.selfMute(user, it) }?.let(emit)
        }
        if (msg.hasRecording()) {
            commit(user.copy(isRecording = msg.recording))
            self()?.let { UserNotices.recording(user, it) { id -> channels[id] } }?.let(emit)
        }
        commit(
            user.copy(
                isDeafened = if (msg.hasDeaf()) msg.deaf else user.isDeafened,
                isMuted = if (msg.hasMute()) msg.mute else user.isMuted,
                isSuppressed = if (msg.hasSuppress()) msg.suppress else user.isSuppressed,
                isPrioritySpeaker = if (msg.hasPrioritySpeaker()) msg.prioritySpeaker else user.isPrioritySpeaker,
            )
        )
        commit(listen(user, msg))

        if (msg.hasChannelId()) {
            val target = channels[msg.channelId]
            if (target == null) {
                // The rest of a frame naming an unknown channel is dropped.
                Log.e(TAG, "Invalid channel for user!")
                return
            }
            val old = user.channel
            if (old != target.id) {
                remove(members, old, session)
                commit(user.copy(channel = target.id))
                insert(members, target.id, session, userOrder)
            }
            val self = self()
            if (self != null && self.session != session) {
                UserNotices.move(user, actor, channels[old], target, self)?.let(emit)
            }
        }

        commit(profile(user, msg))
        if (known == null || msg.hasHash() || msg.hasName()) {
            commit(user.copy(localVolume = localVolumeKey(user, local.serverScope)?.let(local.volumes::get) ?: 1f))
        }
    }

    /** A user a frame introduces, in the root channel: joining the root carries no channel id. */
    private fun newUser(msg: Mumble.UserState): UserState {
        stub(ROOT_CHANNEL_ID)
        val user = UserState(msg.session, msg.name, ROOT_CHANNEL_ID)
        users[msg.session] = user
        insert(members, ROOT_CHANNEL_ID, msg.session, userOrder)
        return user
    }

    /** The channels [user] starts or stops listening to; unknown channels are skipped. */
    private fun listen(user: UserState, msg: Mumble.UserState): UserState {
        var listening = user.listening
        for (id in msg.listeningChannelAddList) {
            if (channels[id] == null || id in listening) continue
            listening = listening + id
            insert(listeners, id, user.session, userOrder)
        }
        for (id in msg.listeningChannelRemoveList) {
            if (id !in listening) continue
            listening = listening - id
            remove(listeners, id, user.session)
        }
        return if (listening === user.listening) user else user.copy(listening = listening)
    }

    /** Name, avatar and comment; a new hash drops the cached blob, a new blob its hash. */
    private fun profile(user: UserState, msg: Mumble.UserState): UserState {
        var next = user
        if (msg.hasName() && msg.name != user.name) {
            next = next.copy(name = msg.name)
            users[user.session] = next
            resort(members, next.channel, next.session, userOrder)
            for (channel in next.listening) resort(listeners, channel, next.session, userOrder)
        }
        if (msg.hasTextureHash()) next = next.copy(texture = null)
        if (msg.hasTexture()) {
            next = next.copy(texture = msg.texture.takeUnless { it.isEmpty }?.let { Bytes.wrap(it.toByteArray()) })
        }
        if (msg.hasCommentHash()) next = next.copy(comment = null, hasCommentHash = true)
        if (msg.hasComment()) next = next.copy(comment = msg.comment, hasCommentHash = false)
        return next
    }

    private fun userRemove(msg: Mumble.UserRemove, emit: (HumlaEvent) -> Unit) {
        val user = users[msg.session]
        val actor = users[msg.actor]
        emit(
            when {
                msg.session == selfSession -> HumlaEvent.SelfKicked(actor?.name, msg.reason, msg.ban)
                actor != null -> HumlaEvent.UserKicked(user?.name, actor.name, msg.reason, msg.ban)
                else -> HumlaEvent.UserLeftServer(user?.name)
            }
        )
        if (user == null) return
        remove(members, user.channel, user.session)
        for (channel in user.listening) remove(listeners, channel, user.session)
        users.remove(user.session)
    }

    /**
     * Applies a local mute, ignore or volume to the user, and remembers it for whoever has the
     * same identity later: mutes and ignores of registered users by user id, volumes by
     * [localVolumeKey].
     */
    fun onLocal(input: LocalInput) {
        val user = users[input.session] ?: return
        val registered = user.userId >= 0
        users[user.session] = when (input) {
            is LocalInput.Mute -> {
                if (registered) local = local.copy(mutedUserIds = local.mutedUserIds.toggled(user.userId, input.muted))
                user.copy(isLocalMuted = input.muted)
            }
            is LocalInput.Ignore -> {
                if (registered) {
                    local = local.copy(ignoredUserIds = local.ignoredUserIds.toggled(user.userId, input.ignored))
                }
                user.copy(isLocalIgnored = input.ignored)
            }
            is LocalInput.Volume -> {
                localVolumeKey(user, local.serverScope)?.let { key ->
                    val volumes = local.volumes
                    val volume = input.volume
                    local = local.copy(volumes = if (volume == 1f) volumes - key else volumes + (key to volume))
                }
                user.copy(localVolume = input.volume)
            }
        }
    }

    companion object {
        /**
         * How far below the root a channel may be placed. The server controls the tree depth and
         * readers walk it recursively, so an unbounded chain would end in a `StackOverflowError`.
         * 256 is far above Mumble's default nesting limit (10) and far below the depth that
         * overflows the stack.
         */
        const val MAX_CHANNEL_DEPTH = 256

        private val TAG: String = ServerWriter::class.java.name
    }
}

private fun Set<Int>.toggled(id: Int, present: Boolean): Set<Int> = if (present) this + id else this - id

private fun insert(index: PersistentMap.Builder<Int, PersistentList<Int>>, key: Int, id: Int, order: Comparator<Int>) {
    val list = index[key] ?: persistentListOf()
    var low = 0
    var high = list.size
    while (low < high) {
        val mid = (low + high) ushr 1
        if (order.compare(list[mid], id) < 0) low = mid + 1 else high = mid
    }
    index[key] = list.addingAt(low, id)
}

private fun remove(index: PersistentMap.Builder<Int, PersistentList<Int>>, key: Int, id: Int) {
    val list = index[key] ?: return
    val next = list.removing(id)
    if (next.isEmpty()) index.remove(key) else index[key] = next
}

/** Moves [id] to its place in [key]'s list after its sort key changed. */
private fun resort(index: PersistentMap.Builder<Int, PersistentList<Int>>, key: Int, id: Int, order: Comparator<Int>) {
    if (index[key]?.contains(id) != true) return
    remove(index, key, id)
    insert(index, key, id, order)
}
