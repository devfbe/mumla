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

package se.lublin.humla.model

/** The 30 whisper target slots Mumble's 5-bit voice target id leaves, tracked in a bit vector. */
class WhisperTargetList {
    private val activeTargets = arrayOfNulls<WhisperTarget>(TARGET_MAX - TARGET_MIN + 1)
    private var takenIds = 0

    private fun isTaken(id: Int) = takenIds and (1 shl id) != 0

    /**
     * Puts [target] in the lowest free slot.
     * @return the slot in [TARGET_MIN]..[TARGET_MAX], or -1 if all are taken.
     */
    fun append(target: WhisperTarget): Byte {
        val freeId = (TARGET_MIN..TARGET_MAX).firstOrNull { !isTaken(it) } ?: return -1
        activeTargets[freeId - TARGET_MIN] = target
        takenIds = takenIds or (1 shl freeId)
        return freeId.toByte()
    }

    /**
     * @return the target in slot [id], or null if the slot is free.
     * @throws IndexOutOfBoundsException for an id outside [TARGET_MIN]..[TARGET_MAX].
     */
    operator fun get(id: Byte): WhisperTarget? {
        checkSlot(id)
        return if (isTaken(id.toInt())) activeTargets[id - TARGET_MIN] else null
    }

    /** @throws IndexOutOfBoundsException for a slot outside [TARGET_MIN]..[TARGET_MAX]. */
    fun free(slot: Byte) {
        checkSlot(slot)
        takenIds = takenIds and (1 shl slot.toInt()).inv()
    }

    fun spaceRemaining(): Int = (TARGET_MIN..TARGET_MAX).count { !isTaken(it) }

    fun clear() {
        takenIds = 0
    }

    private fun checkSlot(slot: Byte) {
        if (slot !in TARGET_MIN..TARGET_MAX) throw IndexOutOfBoundsException("Whisper target slot $slot")
    }

    companion object {
        const val TARGET_MIN: Int = 1
        const val TARGET_MAX: Int = 30
    }
}
