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

package se.lublin.humla.testutil

import se.lublin.humla.session.CommunicationDevices

/**
 * The communication-device seam as a map of ids to types, with every input [ScoRouter] branches on
 * expressible: a device list that holds none, one or several of a type, a platform that refuses a
 * selection, and a route the system changed by itself.
 *
 * Distinct ids are the point of it. Robolectric's `AudioDeviceInfoBuilder` has no `setId`, so every
 * device it builds carries the same one and "the first SCO device" cannot be told from "any SCO
 * device" there; it can here.
 *
 * [notifiesOnChange] defaults to **false**, and that is the production ordering rather than a
 * convenience. `AndroidCommunicationDevices` hands `AudioManager` an Executor that posts to the
 * main looper, and [ScoRouter] is main-thread-only, so while `apply()` is running the platform's
 * own callback cannot run: `select` and `clear` return with the route already changed and the event
 * still queued. A fake that notifies inline models a state production cannot reach, and it hides
 * the only thing that reports the change in time - see
 * ScoRouterTest.applyReportsTheRouteItselfWhenTheSeamHasNotRaisedItsEventYet, which is the test the
 * inline default had made unwritable.
 */
class FakeCommunicationDevices : CommunicationDevices {
    /** device id -> AudioDeviceInfo type, in the order the platform would report them. */
    val available = linkedMapOf<Int, Int>()
    var selectedId: Int? = null
    var selectResult = true

    /** Whether [select] and [clear] raise the change event inline; see the class doc. */
    var notifiesOnChange = false
    var listener: (() -> Unit)? = null
    val selectCalls = mutableListOf<Int>()
    var clearCalls = 0
    var listenerRegistrations = 0

    override fun availableIdsOfType(type: Int): List<Int> =
        available.filterValues { it == type }.keys.toList()

    override fun select(id: Int): Boolean {
        selectCalls += id
        if (!selectResult) return false
        selectedId = id
        if (notifiesOnChange) listener?.invoke()
        return true
    }

    override fun clear() {
        clearCalls++
        selectedId = null
        if (notifiesOnChange) listener?.invoke()
    }

    override fun currentType(): Int? = selectedId?.let { available[it] }

    override fun setOnChangedListener(listener: (() -> Unit)?) {
        listenerRegistrations++
        this.listener = listener
    }

    /** The system switched the route on its own (headset connected, headset walked away). */
    fun systemSelects(id: Int?) {
        selectedId = id
        listener?.invoke()
    }
}
