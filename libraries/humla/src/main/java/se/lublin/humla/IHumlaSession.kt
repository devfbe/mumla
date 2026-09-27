package se.lublin.humla

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import se.lublin.humla.audio.TransmitMode
import se.lublin.humla.audio.routing.CommunicationDevice
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.Message
import se.lublin.humla.model.Server
import se.lublin.humla.model.ServerSettings
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.net.HumlaUDPMessageType
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.session.SessionState
import se.lublin.humla.session.inMainThreadSlices
import se.lublin.humla.util.VoiceTargetMode

/**
 * One session with a server: a connection and its automatic reconnects. The lifecycle members work
 * in every state; unless stated otherwise, the others throw [IllegalStateException] while not
 * synchronized. Main thread, except for collecting [state] and [events].
 */
@Suppress("TooManyFunctions") // The protocol's whole client API.
interface IHumlaSession : AutoCloseable {
    /** Whether the session is connected, and why it is not. */
    val state: StateFlow<SessionState>

    /**
     * What happens in the session, emitted from any thread. There is no replay: collect before
     * acting on a result. Collect on the main thread through [inMainThreadSlices] to keep the UI
     * responsive during bursts.
     */
    val events: SharedFlow<HumlaEvent>

    /** The configuration last passed to [configure]. */
    val config: SessionConfig

    /** The server of this session. */
    val targetServer: Server?

    /** The `AudioDeviceInfo` type voice is routed to; null while the platform decides. */
    val audioRoute: StateFlow<Int?>

    /**
     * The server's channels and users, one immutable snapshot per burst of changes; null without a
     * connection. Collect anywhere.
     */
    val model: StateFlow<ServerState?>

    /** The talk state of every user who is not silent, by session; updated on the main thread. */
    val talkStates: StateFlow<Map<Int, TalkState>>

    /**
     * Replaces the configuration. Audio settings apply live, connection settings on the next
     * connection.
     * @return true if a reconnect is required for the changes to take effect.
     */
    fun configure(config: SessionConfig): Boolean

    /** Connects to the configured server. Ignored while connecting or connected. */
    fun connect()

    /** Ends the session; a no-op once it has ended. */
    fun disconnect()

    /** Gives up an automatic reconnect that is waiting or in flight; the reason stays in [state]. */
    fun cancelReconnect()

    /** Disconnects and releases the session's threads and platform callbacks. The session is unusable afterwards. */
    override fun close()

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

    val transmitMode: TransmitMode

    val codec: HumlaUDPMessageType?

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
     * Routes voice to the device with this id from [audioDevices], as the user's explicit choice,
     * like the phone app's audio chooser. It holds across a dropped connection and ends with the
     * session, when the device goes away, or when a newly connected headset takes over. Choosing
     * the device the default would give anyway returns to the default.
     */
    fun selectAudioDevice(id: Int)

    /** Drops the choice [selectAudioDevice] made: the saved device or the automatic default applies. */
    fun selectAutomaticAudioDevice()

    fun setTalkingState(talking: Boolean)

    fun joinChannel(channel: Int)

    /**
     * Plays [session] at [volume], a linear gain, on this device only, and keeps it for users of
     * the same identity for the rest of the session. Storing it is up to the client.
     */
    fun setLocalVolume(session: Int, volume: Float)

    /** Mutes [session] on this device only; kept for a registered user's later sessions. */
    fun setLocalMuted(session: Int, muted: Boolean)

    /** Ignores [session]'s text messages on this device only; kept like [setLocalMuted]. */
    fun setLocalIgnored(session: Int, ignored: Boolean)

    /** Starts or stops listening to [channel] without joining it. */
    fun setListening(channel: Int, listen: Boolean)

    fun moveUserToChannel(session: Int, channel: Int)

    fun createChannel(parent: Int, name: String, description: String, position: Int, temporary: Boolean)

    fun sendAccessTokens(tokens: List<String>)

    fun requestPermissions(channel: Int)

    fun requestComment(session: Int)

    fun requestAvatar(session: Int)

    fun requestChannelDescription(channel: Int)

    /** Asks for [session]'s connection statistics; they arrive as `HumlaEvent.UserStatsReceived`. */
    fun requestUserStats(session: Int)

    fun registerUser(session: Int)

    fun kickBanUser(session: Int, reason: String?, ban: Boolean)

    /** Sends [message] to [session]; the returned message is also published as `MessageSent`. */
    fun sendUserTextMessage(session: Int, message: String): Message

    /** Sends [message] to [channel], and with [tree] to its subchannels; published as `MessageSent`. */
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
