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
package se.lublin.humla

import kotlinx.coroutines.flow.StateFlow
import se.lublin.humla.audio.TransmitMode
import se.lublin.humla.audio.routing.CommunicationDevice

/** The session's audio: transmitting, and where voice is routed. Main thread; nothing here throws. */
interface AudioControls {
    /** The `AudioDeviceInfo` type voice is routed to; null while the platform decides. */
    val route: StateFlow<Int?>

    val transmitMode: TransmitMode

    /** Whether push-to-talk is on. */
    val isTalking: Boolean

    fun setTalking(talking: Boolean)

    /**
     * Every device voice can be routed to right now, in the platform's order: earpiece, speaker,
     * wired and USB headsets, Bluetooth headsets with their own names. Empty while no session is
     * synchronized.
     */
    val devices: List<CommunicationDevice>

    /**
     * The device voice goes to right now, whether it was chosen, taken automatically or is simply
     * where the platform plays; null while no session is synchronized.
     */
    val activeDevice: CommunicationDevice?

    /**
     * Whether the echo canceller runs for the device voice goes to right now: its kind's default
     * or the user's override for that kind.
     */
    val isEchoCancellationEnabled: Boolean

    /**
     * Routes voice to the device with this id from [devices], as the user's explicit choice, like
     * the phone app's audio chooser. It holds across a dropped connection and ends with the
     * session, when the device goes away, or when a newly connected headset takes over. Choosing
     * the device the default would give anyway returns to the default.
     */
    fun selectDevice(id: Int)

    /** Drops the choice [selectDevice] made: the saved device or the automatic default applies. */
    fun selectAutomaticDevice()

    /** The bandwidth in bps of the audio sent now, or a negative value while none is sent. */
    val currentBandwidth: Int
}
