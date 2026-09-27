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
package se.lublin.humla.audio.native

/**
 * Owns one native object: [create] runs on construction, [destroy] at most once, on [close], after
 * which [value] is 0. Not thread-safe: the owner confines it to one thread or locks around it.
 * Reading [value] allocates nothing, so it is fit for the audio threads.
 */
class NativeHandle(create: () -> Long, private val destroy: (Long) -> Unit) : AutoCloseable {
    var value: Long = create()
        private set

    override fun close() {
        val handle = value
        if (handle != 0L) {
            value = 0L
            destroy(handle)
        }
    }
}
