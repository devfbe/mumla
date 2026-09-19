/*
 * Copyright (C) 2026 The Mumla Authors
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

package se.lublin.humla.audio

import java.nio.ByteBuffer

internal object PacketBytes {
    /** Copies the first [length] bytes of [input] (from its position) into a fresh array; null stays null. */
    fun copy(input: ByteBuffer?, length: Int): ByteArray? {
        if (input == null) return null
        val bytes = ByteArray(length)
        input.duplicate().get(bytes, 0, length)
        return bytes
    }
}
