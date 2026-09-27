/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

public enum class VoiceTargetMode {
    NORMAL,
    WHISPER,
    SERVER_LOOPBACK;

    internal companion object {
        private const val LOOPBACK_ID: Byte = 31

        /** @throws IllegalArgumentException for an id outside 0..31. */
        internal fun fromId(targetId: Byte): VoiceTargetMode = when (targetId) {
            0.toByte() -> NORMAL
            in 1 until LOOPBACK_ID -> WHISPER
            LOOPBACK_ID -> SERVER_LOOPBACK
            else -> throw IllegalArgumentException("Voice target id out of range: $targetId")
        }
    }
}
