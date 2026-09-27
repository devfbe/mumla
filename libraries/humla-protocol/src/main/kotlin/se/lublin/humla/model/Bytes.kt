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

/** Immutable bytes compared by content, so they can sit in a snapshot's data classes. */
public class Bytes private constructor(private val data: ByteArray) {
    public val size: Int get() = data.size

    /** A copy the caller may keep and change. */
    public fun toByteArray(): ByteArray = data.copyOf()

    override fun equals(other: Any?): Boolean = this === other || (other is Bytes && data.contentEquals(other.data))

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = "Bytes(${data.size})"

    public companion object {
        public fun of(bytes: ByteArray): Bytes = Bytes(bytes.copyOf())

        internal fun wrap(bytes: ByteArray): Bytes = Bytes(bytes)
    }
}
