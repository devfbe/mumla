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
package se.lublin.humla

/** Protocol and transmit constants. Prefer keeping new constants with the class that uses them. */
object Constants {
    const val PROTOCOL_MAJOR = 1
    const val PROTOCOL_MINOR = 4
    const val PROTOCOL_PATCH = 0

    const val TRANSMIT_VOICE_ACTIVITY = 0
    const val TRANSMIT_PUSH_TO_TALK = 1
    const val TRANSMIT_CONTINUOUS = 2

    const val PROTOCOL_VERSION = (PROTOCOL_MAJOR shl 16) or (PROTOCOL_MINOR shl 8) or PROTOCOL_PATCH
    const val PROTOCOL_STRING = "$PROTOCOL_MAJOR.$PROTOCOL_MINOR.$PROTOCOL_PATCH"
    const val DEFAULT_PORT = 64738
}
