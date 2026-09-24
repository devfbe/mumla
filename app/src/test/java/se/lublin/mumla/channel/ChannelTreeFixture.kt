package se.lublin.mumla.channel

import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState

/**
 * A channel tree the adapter can walk, with a counter on every accessor the walk uses. Mirrors
 * [se.lublin.humla.model.Channel], including the recursive [getSubchannelUserCount].
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

    // Nullable like the real `Channel.getUsers()`, so the adapter's null check can be exercised.
    private val users = mutableListOf<IUser?>()
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

    /** A user the model counts but has not filled in yet. */
    fun addAbsentUser() {
        users.add(null)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getUsers(): List<IUser> {
        counters.getUsersCalls++
        return users as List<IUser>
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

    /** Same recursion as `Channel.getSubchannelUserCount()`, so the counter counts node visits. */
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
    // Not `var talkState`: that would generate getTalkState(), colliding with the interface method.
    var state: TalkState = TalkState.PASSIVE,
    // Negative for an unregistered user. Not a `var`: getUserId() is the interface's accessor.
    userId: Int = -1,
    // Raw avatar bytes that may or may not decode. Not a `var`: getTexture() is the interface's.
    texture: ByteArray? = null,
) : IUser {
    private val registeredUserId: Int = userId
    private val avatar: ByteArray? = texture
    private var localMuted = false
    private var localIgnored = false

    override fun getSession(): Int = session
    override fun getChannel(): se.lublin.humla.model.Channel? = null
    override fun getUserId(): Int = registeredUserId
    override fun getName(): String = name
    override fun getComment(): String = ""
    override fun getCommentHash(): ByteArray? = null
    override fun getTexture(): ByteArray? = avatar
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
