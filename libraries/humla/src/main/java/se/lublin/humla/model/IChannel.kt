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

/** A channel as the server tree exposes it. */
interface IChannel {
    val users: List<IUser>
    val id: Int
    val position: Int
    val isTemporary: Boolean
    val parent: IChannel?
    val name: String?
    val description: String?
    val descriptionHash: ByteArray?
    val subchannels: List<IChannel>
    /** The users in this channel and all its subchannels. */
    val subchannelUserCount: Int
    val links: List<IChannel>
    val permissions: Int

    /** Whether the channel's ACL restricts who may enter it. */
    val isEnterRestricted: Boolean

    /** Whether the server lets the local user enter this channel. */
    val canEnter: Boolean
}
