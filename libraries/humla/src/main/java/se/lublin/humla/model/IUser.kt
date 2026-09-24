/*
 * Copyright (C) 2015 Andrew Comminos <andrew@comminos.com>
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

/** A user as the server tree exposes it. */
interface IUser {
    val session: Int
    val channel: Channel?
    /** The registered user id, or -1 for an unregistered user. */
    val userId: Int
    val name: String?
    val comment: String?
    val commentHash: ByteArray?
    val texture: ByteArray?
    val textureHash: ByteArray?
    /** The certificate hash. */
    val hash: String?
    val isMuted: Boolean
    val isDeafened: Boolean
    val isSuppressed: Boolean
    val isSelfMuted: Boolean
    val isSelfDeafened: Boolean
    val isPrioritySpeaker: Boolean
    val isRecording: Boolean
    /** Muted on this device only. */
    var isLocalMuted: Boolean
    /** Text messages ignored on this device only. */
    var isLocalIgnored: Boolean
    val talkState: TalkState

    /** The playback gain set on this device, see [LocalVolumes]; 1 is unchanged. */
    val localVolume: Float
}
