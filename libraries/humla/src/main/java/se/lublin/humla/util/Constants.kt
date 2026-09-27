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
package se.lublin.humla.util

/** Protocol constants. Prefer keeping new constants with the class that uses them. */
public object Constants {
    internal const val PROTOCOL_MAJOR: Int = 1
    internal const val PROTOCOL_MINOR: Int = 5
    internal const val PROTOCOL_PATCH: Int = 0

    public const val PROTOCOL_STRING: String = "$PROTOCOL_MAJOR.$PROTOCOL_MINOR.$PROTOCOL_PATCH"
    internal const val DEFAULT_PORT: Int = 64738
}
