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

package se.lublin.humla.session

import android.media.AudioDeviceInfo

/**
 * What kind of device a route is, as far as the user can tell: the unit the echo canceller's
 * default is decided per, and the unit a user's override of it is remembered per. Stable names -
 * the app stores overrides under them.
 */
enum class AudioDeviceCategory(
    /**
     * Whether the canceller runs when the user has not said otherwise: where the phone plays out
     * loud and its own microphone hears it, not on a headset, where it only costs quality.
     */
    val echoCancellationByDefault: Boolean,
) {
    SPEAKER(true),
    EARPIECE(true),
    BLUETOOTH(false),
    WIRED(false),
    OTHER(false);

    companion object {
        @JvmStatic
        fun of(type: Int): AudioDeviceCategory = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> EARPIECE
            in AudioRouter.BLUETOOTH -> BLUETOOTH
            in AudioRouter.WIRED -> WIRED
            else -> OTHER
        }
    }
}
