package se.lublin.mumla.channel

import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState

/**
 * A channel tree the adapter can walk, with a counter on every accessor the walk uses.
 *
 * The real [se.lublin.humla.model.Channel] is stream A's file and cannot be instrumented from
 * here; this fake reproduces its shape exactly, including the recursive
 * [getSubchannelUserCount] that the production tree computes from scratch on every call.
 */
class FakeChannel(
    private val id: Int,
    private val name: String = "channel-$id",
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

    private val users = mutableListOf<IUser>()
    private val subchannels = mutableListOf<FakeChannel>()
    private val links = mutableListOf<IChannel>()
    private var parent: FakeChannel? = null

    fun addLink(channel: IChannel) {
        links.add(channel)
    }

    fun addSubchannel(child: FakeChannel): FakeChannel {
        subchannels.add(child)
        child.parent = this
        return child
    }

    fun addUser(user: IUser) {
        users.add(user)
    }

    fun removeUser(user: IUser) {
        users.remove(user)
    }

    override fun getUsers(): List<IUser> {
        counters.getUsersCalls++
        return users
    }

    override fun getId(): Int = id
    override fun getPosition(): Int = 0
    override fun isTemporary(): Boolean = false
    override fun getParent(): IChannel? = parent
    override fun getName(): String = name
    override fun getDescription(): String = ""
    override fun getDescriptionHash(): ByteArray? = null

    override fun getSubchannels(): List<IChannel> {
        counters.getSubchannelsCalls++
        return subchannels
    }

    /**
     * Same recursion as `Channel.getSubchannelUserCount()`: the whole subtree, every call.
     * The counter therefore counts node *visits*, which is what the walk actually costs.
     */
    override fun getSubchannelUserCount(): Int {
        counters.subchannelUserCountCalls++
        var count = users.size
        for (sub in subchannels) {
            count += sub.getSubchannelUserCount()
        }
        return count
    }

    override fun getLinks(): List<IChannel> = links
    override fun getPermissions(): Int = 0

    override fun equals(other: Any?): Boolean = other is FakeChannel && other.id == id
    override fun hashCode(): Int = id
}

class FakeUser(
    private val session: Int,
    private val name: String = "user-$session",
    var selfDeafened: Boolean = false,
    var deafened: Boolean = false,
    var selfMuted: Boolean = false,
    var muted: Boolean = false,
    var suppressed: Boolean = false,
    // Not `var talkState`: that generates getTalkState(), which collides with the interface
    // method this class overrides (spec 4.05).
    var state: TalkState = TalkState.PASSIVE,
    // Negative for an unregistered user, which is the server's way of saying "not an account".
    // Backed by a field rather than a `var` for the same reason: `var userId` would generate
    // getUserId(), which is the interface's own accessor (spec 4.05).
    userId: Int = -1,
) : IUser {
    private val registeredUserId: Int = userId
    private var localMuted = false
    private var localIgnored = false

    override fun getSession(): Int = session
    override fun getChannel(): se.lublin.humla.model.Channel? = null
    override fun getUserId(): Int = registeredUserId
    override fun getName(): String = name
    override fun getComment(): String = ""
    override fun getCommentHash(): ByteArray? = null
    override fun getTexture(): ByteArray? = null
    override fun getTextureHash(): ByteArray? = null
    override fun getHash(): String = ""
    override fun isMuted(): Boolean = muted
    override fun isDeafened(): Boolean = deafened
    override fun isSuppressed(): Boolean = suppressed
    override fun isSelfMuted(): Boolean = selfMuted
    override fun isSelfDeafened(): Boolean = selfDeafened
    override fun isPrioritySpeaker(): Boolean = false
    override fun isRecording(): Boolean = false
    override fun isLocalMuted(): Boolean = localMuted
    override fun isLocalIgnored(): Boolean = localIgnored
    override fun setLocalMuted(muted: Boolean) { localMuted = muted }
    override fun setLocalIgnored(ignored: Boolean) { localIgnored = ignored }
    override fun getTalkState(): TalkState = state
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
