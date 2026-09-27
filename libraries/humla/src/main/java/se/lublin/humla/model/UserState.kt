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
package se.lublin.humla.model

/** A user as one [ServerState] saw them, with what this device keeps for them locally. */
data class UserState(
    val session: Int,
    val name: String?,
    val channel: Int,
    /** The registered user id, or -1 for an unregistered user. */
    val userId: Int = -1,
    /** The certificate hash. */
    val hash: String? = null,
    /** The comment, or null while only its hash is known (see [hasCommentHash]). */
    val comment: String? = null,
    /** The server holds a comment too long to send unasked; request it to fill [comment]. */
    val hasCommentHash: Boolean = false,
    /** The avatar image, once the server sent it. */
    val texture: Bytes? = null,
    val isMuted: Boolean = false,
    val isDeafened: Boolean = false,
    val isSuppressed: Boolean = false,
    val isSelfMuted: Boolean = false,
    val isSelfDeafened: Boolean = false,
    val isPrioritySpeaker: Boolean = false,
    val isRecording: Boolean = false,
    /** The channels the user listens to without being in them. */
    val listening: Set<Int> = emptySet(),
    /** Muted on this device only. */
    val isLocalMuted: Boolean = false,
    /** Text messages ignored on this device only. */
    val isLocalIgnored: Boolean = false,
    /** The playback gain set on this device; 1 is unchanged. */
    val localVolume: Float = 1f,
)
