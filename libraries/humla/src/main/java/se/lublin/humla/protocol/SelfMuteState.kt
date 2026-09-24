package se.lublin.humla.protocol

import se.lublin.humla.protobuf.Mumble

/**
 * The own user's mute flags as last reported by the server. A UserState carries only the fields
 * that changed, so each flag is kept until a message sets it again.
 */
internal class SelfMuteState(
    @Volatile private var serverMuted: Boolean,
    @Volatile private var selfMuted: Boolean,
    @Volatile private var suppressed: Boolean,
) {
    val isMuted: Boolean get() = serverMuted || selfMuted || suppressed

    /** Applies the mute fields [msg] carries; the caller checks that it is about the own session. */
    fun update(msg: Mumble.UserState) {
        if (msg.hasMute()) serverMuted = msg.mute
        if (msg.hasSelfMute()) selfMuted = msg.selfMute
        if (msg.hasSuppress()) suppressed = msg.suppress
    }
}
