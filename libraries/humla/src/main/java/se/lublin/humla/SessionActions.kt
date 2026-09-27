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
package se.lublin.humla

import se.lublin.humla.model.Message
import se.lublin.humla.model.WhisperTarget
import se.lublin.humla.util.VoiceTargetMode

/**
 * What a client asks of the server, where its voice goes, and the choices it makes about other
 * users on this device. Main thread. Every request is dropped while the session is not
 * synchronized; nothing here throws.
 */
@Suppress("TooManyFunctions") // The protocol's client requests.
public interface SessionActions {
    public fun joinChannel(channel: Int)

    public fun moveUser(session: Int, channel: Int)

    public fun createChannel(parent: Int, name: String, description: String, position: Int, temporary: Boolean)

    public fun removeChannel(channel: Int)

    public fun linkChannels(channel: Int, other: Int)

    public fun unlinkChannels(channel: Int, other: Int)

    /** Unlinks every channel linked to [channel]. */
    public fun unlinkAllChannels(channel: Int)

    /** Starts or stops listening to [channel] without joining it. */
    public fun setListening(channel: Int, listen: Boolean)

    public fun sendAccessTokens(tokens: List<String>)

    /** Asks for the local user's permissions in [channel]; they arrive in the model. */
    public fun requestPermissions(channel: Int)

    /** Asks for a comment only its hash was sent for; it arrives in the model. */
    public fun requestComment(session: Int)

    /** Asks for a description only its hash was sent for; it arrives in the model. */
    public fun requestChannelDescription(channel: Int)

    /** Asks for [session]'s connection statistics; they arrive as `HumlaEvent.UserStatsReceived`. */
    public fun requestUserStats(session: Int)

    public fun registerUser(session: Int)

    public fun kickBanUser(session: Int, reason: String?, ban: Boolean)

    public fun setUserComment(session: Int, comment: String?)

    public fun setPrioritySpeaker(session: Int, priority: Boolean)

    public fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean)

    public fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean)

    /** Sends [message] to [session]; the message is also published as `MessageSent`. Null if not sent. */
    public fun sendUserTextMessage(session: Int, message: String): Message?

    /** Sends [message] to [channel], and with [tree] to its subchannels; like [sendUserTextMessage]. */
    public fun sendChannelTextMessage(channel: Int, message: String, tree: Boolean): Message?

    /** Mutes [session] on this device only; kept for a registered user's later sessions. */
    public fun setLocalMuted(session: Int, muted: Boolean)

    /** Ignores [session]'s text messages on this device only; kept like [setLocalMuted]. */
    public fun setLocalIgnored(session: Int, ignored: Boolean)

    /**
     * Plays [session] at [volume], a linear gain, on this device only, and keeps it for users of
     * the same identity. Storing it beyond the session is up to the client.
     */
    public fun setLocalVolume(session: Int, volume: Float)

    public val voiceTargetMode: VoiceTargetMode

    /** The registered whisper target, armed or active; null while none is registered. */
    public val whisperTarget: WhisperTarget?

    /** Whether [whisperTarget] is the active voice target right now, rather than only armed. */
    public val isWhisperActive: Boolean

    /**
     * Registers [target] in place of a whisper target already registered, and with [activate]
     * (the default) makes it the active voice target too; with it false, [target] is only armed,
     * ready for [setWhisperActive] to switch to without registering it again.
     * @return false if the server's 30 voice target slots are taken.
     */
    public fun whisperTo(target: WhisperTarget, activate: Boolean = true): Boolean

    /** Switches transmission to [whisperTarget] or back to normal speech; a no-op without one registered. */
    public fun setWhisperActive(active: Boolean)

    /** Back to normal speech; the whisper target's slot is freed. */
    public fun stopWhispering()
}
