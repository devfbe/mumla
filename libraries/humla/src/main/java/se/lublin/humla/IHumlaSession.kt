package se.lublin.humla

import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.session.CommunicationDevice
import se.lublin.humla.util.VoiceTargetMode

/**
 * A live connection to the server. Unless stated otherwise, members throw [IllegalStateException]
 * while not connected or not synchronized.
 */
@Suppress("TooManyFunctions") // The protocol's whole client API.
interface IHumlaSession {
    /** The TCP round trip in milliseconds. */
    val tcpLatency: Long

    /** The UDP round trip in milliseconds. */
    val udpLatency: Long

    /** The server's maximum audio bandwidth in bps, or -1 if not set. */
    val maxBandwidth: Int

    /** The bandwidth in bps of the audio sent now, or a negative value while none is sent. */
    val currentBandwidth: Int

    /** The server's protocol version as 0xAABBCC: major, minor and patch version. */
    val serverVersion: Int

    /** The server's Mumble release, user-readable. */
    val serverRelease: String?

    val serverOSName: String?
    val serverOSVersion: String?

    /** The own session id, set during synchronization. */
    val sessionId: Int

    /** The own user, set during synchronization. */
    val sessionUser: IUser?

    /** The channel the own user is in. */
    val sessionChannel: IChannel?

    val rootChannel: IChannel?

    /** The permissions in the root channel, see [se.lublin.humla.net.Permissions]. */
    val permissions: Int

    /** One of the `Constants.TRANSMIT_*` modes. */
    val transmitMode: Int

    val codec: HumlaUDPMessageType?

    /**
     * True if voice is routed over a Bluetooth headset right now, as opposed to
     * [usingBluetoothSco], which reports what the user asked for.
     */
    val isBluetoothScoActive: Boolean

    /**
     * Every device voice can be routed to right now, in the platform's order: earpiece, speaker,
     * wired and USB headsets, Bluetooth headsets with their own names. Empty while no session is
     * synchronized.
     */
    val audioDevices: List<CommunicationDevice>

    /**
     * The device voice goes to right now, whether it was chosen, taken automatically or is simply
     * where the platform plays; null while no session is synchronized.
     */
    val activeAudioDevice: CommunicationDevice?

    /**
     * Whether the echo canceller runs for the device voice goes to right now: its kind's default
     * or the user's override for that kind.
     */
    val isEchoCancellationEnabled: Boolean

    val isTalking: Boolean

    /**
     * The active voice target: 0 is normal speech, 1-30 are whisper targets, 31 is the server
     * loopback.
     */
    var voiceTargetId: Byte

    val voiceTargetMode: VoiceTargetMode

    /** The whisper target in use, or null when not whispering. */
    val whisperTarget: WhisperTarget?

    /** The settings from the server's `ServerConfig`, or null before it arrived. */
    val serverSettings: ServerSettings?

    /** The user with this session id, or null if there is none. */
    fun getUser(session: Int): IUser?

    /** The channel with this id, or null if there is none. */
    fun getChannel(id: Int): IChannel?

    /**
     * Whether a connected Bluetooth headset is taken automatically: the standing wish the app keeps
     * in its preference, not the route.
     */
    fun usingBluetoothSco(): Boolean

    /** Take a connected Bluetooth headset automatically, now and whenever one connects. */
    fun enableBluetoothSco()

    /** Stop taking Bluetooth headsets automatically; a route taken for one is given back. */
    fun disableBluetoothSco()

    /**
     * Routes voice to the device with this id from [audioDevices], as the user's explicit choice,
     * like the phone app's audio chooser. It holds across a dropped connection and ends with the
     * session, when the device goes away, or when a newly connected headset takes over. Choosing
     * the device the default would give anyway returns to the default.
     */
    fun selectAudioDevice(id: Int)

    fun setTalkingState(talking: Boolean)

    fun joinChannel(channel: Int)

    fun moveUserToChannel(session: Int, channel: Int)

    fun createChannel(parent: Int, name: String, description: String, position: Int, temporary: Boolean)

    fun sendAccessTokens(tokens: List<String>)

    fun requestPermissions(channel: Int)

    fun requestComment(session: Int)

    fun requestAvatar(session: Int)

    fun requestChannelDescription(channel: Int)

    fun registerUser(session: Int)

    fun kickBanUser(session: Int, reason: String?, ban: Boolean)

    fun sendUserTextMessage(session: Int, message: String): Message

    fun sendChannelTextMessage(channel: Int, message: String, tree: Boolean): Message

    fun setUserComment(session: Int, comment: String?)

    fun setPrioritySpeaker(session: Int, priority: Boolean)

    fun removeChannel(channel: Int)

    fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean)

    fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean)

    fun linkChannels(channelA: IChannel, channelB: IChannel)

    fun unlinkChannels(channelA: IChannel, channelB: IChannel)

    /** Unlinks every channel linked to [channel]. */
    fun unlinkAllChannels(channel: IChannel)

    /**
     * Registers a whisper target as a voice target on the server, which allows at most 30.
     * @return a voice target id in 1..30, or a negative value if all slots are taken.
     */
    fun registerWhisperTarget(target: WhisperTarget): Byte

    /** Frees the voice target slot [targetId]. */
    fun unregisterWhisperTarget(targetId: Byte)
}
