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
public object Permissions {
    public const val WRITE: Int = 0x1
    public const val ENTER: Int = 0x4
    public const val MUTE_DEAFEN: Int = 0x10
    public const val MOVE: Int = 0x20
    public const val MAKE_CHANNEL: Int = 0x40
    public const val MAKE_TEMP_CHANNEL: Int = 0x400
    public const val LISTEN: Int = 0x800

    // Root channel only
    public const val KICK: Int = 0x10000
    public const val BAN: Int = 0x20000
    public const val REGISTER: Int = 0x40000
    public const val SELF_REGISTER: Int = 0x80000

    internal const val ALL: Int = 0xf07ff
}
