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

/**
 * A channel as one [ServerState] saw it. A channel named by a user or a subchannel before its own
 * state arrived is a nameless stub until then.
 */
data class ChannelState(
    val id: Int,
    val name: String? = null,
    /** Null for the root, and for a channel whose parent the server has not named yet. */
    val parent: Int? = null,
    val position: Int = 0,
    val isTemporary: Boolean = false,
    /** The description, or null while only its hash is known (see [hasDescriptionHash]). */
    val description: String? = null,
    /** The server holds a description too long to send unasked; request it to fill [description]. */
    val hasDescriptionHash: Boolean = false,
    val links: Set<Int> = emptySet(),
    /** The local user's permissions here, see `se.lublin.humla.net.Permissions`; 0 until queried. */
    val permissions: Int = 0,
    /** Whether the channel's ACL restricts who may enter it. */
    val isEnterRestricted: Boolean = false,
    /** Whether the server lets the local user enter this channel. */
    val canEnter: Boolean = true,
)
