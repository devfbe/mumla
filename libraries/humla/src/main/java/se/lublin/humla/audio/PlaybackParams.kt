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
package se.lublin.humla.audio

import se.lublin.humla.model.UserState
import java.util.Arrays

/**
 * How this device plays each user: the local volume and the local mute, for the users that have
 * either. Immutable and swapped whole; a lookup is a binary search over primitive arrays, so the
 * network and playback threads read it without allocating.
 */
class PlaybackParams private constructor(
    private val sessions: IntArray,
    private val volumes: FloatArray,
    private val muted: BooleanArray,
) {
    /** The playback gain for [session]; 1 is unchanged. */
    fun volume(session: Int): Float {
        val index = Arrays.binarySearch(sessions, session)
        return if (index >= 0) volumes[index] else 1f
    }

    fun isMuted(session: Int): Boolean {
        val index = Arrays.binarySearch(sessions, session)
        return index >= 0 && muted[index]
    }

    companion object {
        val DEFAULT = PlaybackParams(IntArray(0), FloatArray(0), BooleanArray(0))

        fun of(users: Collection<UserState>): PlaybackParams {
            val adjusted = users.filter { it.isLocalMuted || it.localVolume != 1f }.sortedBy { it.session }
            if (adjusted.isEmpty()) return DEFAULT
            return PlaybackParams(
                IntArray(adjusted.size) { adjusted[it].session },
                FloatArray(adjusted.size) { adjusted[it].localVolume },
                BooleanArray(adjusted.size) { adjusted[it].isLocalMuted },
            )
        }
    }
}
