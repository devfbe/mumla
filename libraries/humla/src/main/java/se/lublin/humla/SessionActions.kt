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
interface SessionActions {
    fun joinChannel(channel: Int)

    fun moveUser(session: Int, channel: Int)

    fun createChannel(parent: Int, name: String, description: String, position: Int, temporary: Boolean)

    fun removeChannel(channel: Int)

    fun linkChannels(channel: Int, other: Int)

    fun unlinkChannels(channel: Int, other: Int)

    /** Unlinks every channel linked to [channel]. */
    fun unlinkAllChannels(channel: Int)

    /** Starts or stops listening to [channel] without joining it. */
    fun setListening(channel: Int, listen: Boolean)

    fun sendAccessTokens(tokens: List<String>)

    /** Asks for the local user's permissions in [channel]; they arrive in the model. */
    fun requestPermissions(channel: Int)

    /** Asks for a comment only its hash was sent for; it arrives in the model. */
    fun requestComment(session: Int)

    /** Asks for a description only its hash was sent for; it arrives in the model. */
    fun requestChannelDescription(channel: Int)

    /** Asks for [session]'s connection statistics; they arrive as `HumlaEvent.UserStatsReceived`. */
    fun requestUserStats(session: Int)

    fun registerUser(session: Int)

    fun kickBanUser(session: Int, reason: String?, ban: Boolean)

    fun setUserComment(session: Int, comment: String?)

    fun setPrioritySpeaker(session: Int, priority: Boolean)

    fun setMuteDeafState(session: Int, mute: Boolean, deaf: Boolean)

    fun setSelfMuteDeafState(mute: Boolean, deaf: Boolean)

    /** Sends [message] to [session]; the message is also published as `MessageSent`. Null if not sent. */
    fun sendUserTextMessage(session: Int, message: String): Message?

    /** Sends [message] to [channel], and with [tree] to its subchannels; like [sendUserTextMessage]. */
    fun sendChannelTextMessage(channel: Int, message: String, tree: Boolean): Message?

    /** Mutes [session] on this device only; kept for a registered user's later sessions. */
    fun setLocalMuted(session: Int, muted: Boolean)

    /** Ignores [session]'s text messages on this device only; kept like [setLocalMuted]. */
    fun setLocalIgnored(session: Int, ignored: Boolean)

    /**
     * Plays [session] at [volume], a linear gain, on this device only, and keeps it for users of
     * the same identity. Storing it beyond the session is up to the client.
     */
    fun setLocalVolume(session: Int, volume: Float)

    val voiceTargetMode: VoiceTargetMode

    /** The whisper target in use, or null when not whispering. */
    val whisperTarget: WhisperTarget?

    /**
     * Whispers to [target] from now on, in place of a whisper target in use.
     * @return false if the server's 30 voice target slots are taken.
     */
    fun whisperTo(target: WhisperTarget): Boolean

    /** Back to normal speech; the whisper target's slot is freed. */
    fun stopWhispering()
}
