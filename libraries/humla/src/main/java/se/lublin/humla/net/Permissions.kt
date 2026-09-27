/*
 * Copyright (C) 2014 Andrew Comminos
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
package se.lublin.humla.net

/** Mumble ACL permission bits, as `PermissionQuery` reports them. */
object Permissions {
    const val WRITE = 0x1
    const val ENTER = 0x4
    const val MUTE_DEAFEN = 0x10
    const val MOVE = 0x20
    const val MAKE_CHANNEL = 0x40
    const val MAKE_TEMP_CHANNEL = 0x400
    const val LISTEN = 0x800

    // Root channel only
    const val KICK = 0x10000
    const val BAN = 0x20000
    const val REGISTER = 0x40000
    const val SELF_REGISTER = 0x80000

    const val ALL = 0xf07ff
}
