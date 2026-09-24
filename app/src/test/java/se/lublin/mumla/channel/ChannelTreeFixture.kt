package se.lublin.mumla.channel

import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState

/**
 * A channel tree the adapter can walk, with a counter on every accessor the walk uses. Mirrors
 * [se.lublin.humla.model.Channel], including the recursive [subchannelUserCount].
 */
class FakeChannel(
    override val id: Int,
    override val name: String = "channel-$id",
    val counters: Counters = Counters(),
) : IChannel {
    class Counters {
        var subchannelUserCountCalls = 0
        var getUsersCalls = 0
        var getSubchannelsCalls = 0
        fun reset() {
            subchannelUserCountCalls = 0
            getUsersCalls = 0
            getSubchannelsCalls = 0
        }
    }

    // Can hold nulls, so the adapter's null check can be exercised.
    private val userList = mutableListOf<IUser?>()
    private val subchannelList = mutableListOf<FakeChannel>()
    private val linkList = mutableListOf<IChannel>()

    override var parent: FakeChannel? = null
        private set

    fun addLink(channel: IChannel) {
        linkList.add(channel)
    }

    fun addSubchannel(child: FakeChannel): FakeChannel {
        subchannelList.add(child)
        child.parent = this
        return child
    }

    fun addUser(user: IUser) {
        userList.add(user)
    }

    fun removeUser(user: IUser) {
        userList.remove(user)
    }

    /** A user the model counts but has not filled in yet. */
    fun addAbsentUser() {
        userList.add(null)
    }

    @Suppress("UNCHECKED_CAST")
    override val users: List<IUser>
        get() {
            counters.getUsersCalls++
            return userList as List<IUser>
        }

    override val position: Int = 0
    override val isTemporary: Boolean = false
    override val description: String = ""
    override val descriptionHash: ByteArray? = null

    override val subchannels: List<IChannel>
        get() {
            counters.getSubchannelsCalls++
            return subchannelList
        }

    /** Same recursion as `Channel.subchannelUserCount`, so the counter counts node visits. */
    override val subchannelUserCount: Int
        get() {
            counters.subchannelUserCountCalls++
            var count = userList.size
            for (sub in subchannelList) {
                count += sub.subchannelUserCount
            }
            return count
        }

    override val links: List<IChannel> get() = linkList
    override val permissions: Int = 0
    override var isEnterRestricted: Boolean = false
    override var canEnter: Boolean = true

    override fun equals(other: Any?): Boolean = other is FakeChannel && other.id == id
    override fun hashCode(): Int = id
}

class FakeUser(
    override val session: Int,
    override val name: String = "user-$session",
    var selfDeafened: Boolean = false,
    var deafened: Boolean = false,
    var selfMuted: Boolean = false,
    var muted: Boolean = false,
    var suppressed: Boolean = false,
    var state: TalkState = TalkState.PASSIVE,
    /** Negative for an unregistered user. */
    override val userId: Int = -1,
    /** Raw avatar bytes that may or may not decode. */
    override val texture: ByteArray? = null,
) : IUser {
    override val channel: se.lublin.humla.model.Channel? = null
    override val comment: String = ""
    override val commentHash: ByteArray? = null
    override val textureHash: ByteArray? = null
    override val hash: String = ""
    override val isMuted: Boolean get() = muted
    override val isDeafened: Boolean get() = deafened
    override val isSuppressed: Boolean get() = suppressed
    override val isSelfMuted: Boolean get() = selfMuted
    override val isSelfDeafened: Boolean get() = selfDeafened
    override val isPrioritySpeaker: Boolean = false
    override val isRecording: Boolean = false
    override var isLocalMuted: Boolean = false
    override var isLocalIgnored: Boolean = false
    override val talkState: TalkState get() = state
}

/**
 * Builds a balanced channel tree of [channelCount] channels with the given [branching] factor,
 * breadth-first, and puts one user in every [userEvery]-th channel.
 */
fun buildChannelTree(
    channelCount: Int,
    branching: Int,
    userEvery: Int,
): Pair<FakeChannel, Map<Int, FakeChannel>> {
    val counters = FakeChannel.Counters()
    val root = FakeChannel(0, counters = counters)
    val byId = linkedMapOf(0 to root)
    val frontier = ArrayDeque<FakeChannel>()
    frontier.add(root)
    var next = 1
    while (next < channelCount) {
        val parent = frontier.removeFirst()
        var i = 0
        while (i < branching && next < channelCount) {
            val child = FakeChannel(next, counters = counters)
            parent.addSubchannel(child)
            byId[next] = child
            frontier.add(child)
            next++
            i++
        }
    }
    var session = 1
    for ((id, channel) in byId) {
        if (id % userEvery == 0) {
            channel.addUser(FakeUser(session++))
        }
    }
    return root to byId
}
