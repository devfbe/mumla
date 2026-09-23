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

import se.lublin.humla.session.CommunicationDevice
import se.lublin.humla.session.CommunicationDevices

/**
 * The communication-device seam as a map of ids to types, with every input the router branches on
 * expressible: a device list that holds none, one or several of a type, a platform that refuses a
 * selection, and a route the system changed by itself.
 *
 * Distinct ids are the point of it. Robolectric's `AudioDeviceInfoBuilder` has no `setId`, so every
 * device it builds carries the same one and "the first SCO device" cannot be told from "any SCO
 * device" there; it can here.
 *
 * [notifiesOnChange] defaults to **false**, and that is the production ordering rather than a
 * convenience. `AndroidCommunicationDevices` hands `AudioManager` an Executor that posts to the
 * main looper, and the router is main-thread-only, so while `apply()` is running the platform's
 * own callback cannot run: `select` and `clear` return with the route already changed and the event
 * still queued. A fake that notifies inline models a state production cannot reach, and it hides
 * the only thing that reports the change in time - see
 * AudioRouterTest.applyReportsTheRouteItselfWhenTheSeamHasNotRaisedItsEventYet, which is the test the
 * inline default had made unwritable.
 */
class FakeCommunicationDevices : CommunicationDevices {
    /** device id -> AudioDeviceInfo type, in the order the platform would report them. */
    val available = linkedMapOf<Int, Int>()

    /** device id -> product name; a device without an entry is unnamed, as most built-in ones are. */
    val names = mutableMapOf<Int, String>()
    var selectedId: Int? = null
    var selectResult = true

    /** Whether [select] and [clear] raise the change event inline; see the class doc. */
    var notifiesOnChange = false
    var listener: (() -> Unit)? = null
    val selectCalls = mutableListOf<Int>()
    var clearCalls = 0
    var listenerRegistrations = 0

    override fun available(): List<CommunicationDevice> =
        available.map { (id, type) -> device(id, type) }

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

    override fun current(): CommunicationDevice? =
        selectedId?.let { id -> available[id]?.let { device(id, it) } }

    private fun device(id: Int, type: Int) = CommunicationDevice(id, type, names[id].orEmpty())

    override fun setOnChangedListener(listener: (() -> Unit)?) {
        listenerRegistrations++
        this.listener = listener
    }

    /** The system switched the route on its own (headset connected, headset walked away). */
    fun systemSelects(id: Int?) {
        selectedId = id
        listener?.invoke()
    }

    /** A device was switched on or plugged in: the platform raises the device callback. */
    fun deviceArrives(id: Int, type: Int, name: String = "") {
        available[id] = type
        if (name.isNotEmpty()) names[id] = name
        listener?.invoke()
    }

    /**
     * A device was switched off or unplugged. The platform drops a route that pointed at it by
     * itself and raises the change; [selectedId] follows it to null, which is what "the platform
     * default" reads as through [current].
     */
    fun deviceLeaves(id: Int) {
        available.remove(id)
        names.remove(id)
        if (selectedId == id) selectedId = null
        listener?.invoke()
    }
}
