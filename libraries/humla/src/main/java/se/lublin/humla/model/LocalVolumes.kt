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

import java.util.concurrent.ConcurrentHashMap

/**
 * The playback volumes set on this device for other users, as linear gains (1 is unchanged),
 * keyed by [keyOf]. Seeded with the stored volumes; safe to use from any thread.
 */
class LocalVolumes(private val server: Server?, stored: Map<String, Float> = emptyMap()) {
    private val volumes = ConcurrentHashMap(stored)

    /** The volume remembered for [user], or 1. */
    fun volumeFor(user: IUser): Float = keyOf(user, server)?.let { volumes[it] } ?: 1f

    /** Sets [user]'s volume now, and for whoever has the same identity later in this session. */
    fun set(user: User, volume: Float) {
        user.localVolume = volume
        val key = keyOf(user, server) ?: return
        if (volume == 1f) volumes.remove(key) else volumes[key] = volume
    }

    companion object {
        /**
         * Identifies [user] across sessions: by certificate hash when the server sent one, else by
         * name on [server]. Null if neither is known.
         */
        fun keyOf(user: IUser, server: Server?): String? {
            val hash = user.hash
            val name = user.name
            return when {
                !hash.isNullOrEmpty() -> "cert:$hash"
                name != null && server != null -> "name:${server.host}:${server.port}:$name"
                else -> null
            }
        }
    }
}
