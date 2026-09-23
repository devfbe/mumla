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

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.util.Log
import java.util.concurrent.Executor

/**
 * One entry of `AudioManager.getAvailableCommunicationDevices()`, reduced to what routing and a
 * chooser need: the platform's [id] to select it by, its [android.media.AudioDeviceInfo] [type] to
 * decide and label by, and its product [name] - the Bluetooth headset's own name, which is how the
 * phone app shows one. [name] is never null; an unnamed device carries an empty string.
 */
data class CommunicationDevice(val id: Int, val type: Int, val name: String)

/**
 * The subset of `AudioManager`'s communication-device API (API 31) that routing needs.
 *
 * The module's `minSdk` is 31, so this is the only API there is here - spec A4's "on API 31+"
 * carries no alternative branch in this codebase, and `startBluetoothSco` has no reader left once
 * the service is wired to this.
 */
interface CommunicationDevices {
    /** Every communication device available right now, in the platform's order. */
    fun available(): List<CommunicationDevice>

    /** Routes voice to the device; false if the platform refused or the id is gone. */
    fun select(id: Int): Boolean

    /** Returns routing to the platform default. */
    fun clear()

    /** The current communication device, or null if none is set. */
    fun current(): CommunicationDevice?

    /**
     * Registers (or with null, removes) one callback for both kinds of change - the route moved,
     * or a device arrived or left - invoked on the main thread. The second kind is not a route
     * change: switching a headset on raises nothing on the platform's communication-device
     * listener until somebody routes to it, and "a headset appeared, take it" is exactly that case.
     */
    fun setOnChangedListener(listener: (() -> Unit)?)
}

/**
 * Thin pass-through to [AudioManager.setCommunicationDevice] and friends. The logic lives in
 * [AudioRouter] and is tested against a fake; this class carries only the seam and the wrapper.
 *
 * **The wrapper is spec 4.1's ruling, and it is the one thing here that is not pass-through.**
 * `BLUETOOTH_CONNECT` is asked for and is never a condition of routing: measured against the SDK's
 * own annotation database, of 26 annotated `AudioManager` members exactly four carry a
 * `RequiresPermission`, and `BLUETOOTH_CONNECT` appears on 136 members of which none is in
 * `android.media` - `setCommunicationDevice` and `getAvailableCommunicationDevices` included. A
 * user who denies the dialog would otherwise lose a headset that by the platform's own
 * documentation works. "Absent from the annotation database" is not "throws nowhere", though, so
 * every call into `AudioManager` is wrapped: a [SecurityException] from an OEM that enforces more
 * than it annotates is caught, each call answers with the value that reads as "no headset", and
 * [onSecurityDenial] is invoked **once per instance** - one instance is one service life - so the
 * reason reaches the chat log once rather than on every route decision.
 *
 * Note what is deliberately absent: there is no permission parameter and no way to express one.
 * The gate is not held open, it is unrepresentable.
 */
class AndroidCommunicationDevices(
    private val audioManager: AudioManager,
    private val mainHandler: Handler,
    private val onSecurityDenial: (SecurityException) -> Unit,
) : CommunicationDevices {
    private var changeListener: Registration? = null
    private var denialReported = false

    /** The two platform registrations one listener stands for; either may have been refused. */
    private class Registration(
        val route: AudioManager.OnCommunicationDeviceChangedListener?,
        val devices: AudioDeviceCallback?,
    )

    override fun available(): List<CommunicationDevice> = guarded(emptyList()) {
        audioManager.availableCommunicationDevices.map { it.toCommunicationDevice() }
    }

    override fun select(id: Int): Boolean = guarded(false) {
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.id == id }
        if (device == null) false else audioManager.setCommunicationDevice(device)
    }

    override fun clear() = guarded(Unit) { audioManager.clearCommunicationDevice() }

    override fun current(): CommunicationDevice? =
        guarded(null) { audioManager.communicationDevice?.toCommunicationDevice() }

    /**
     * One writer for [changeListener], deliberately. The obvious shape - unregister, clear the
     * field, register, set the field - has a `changeListener = null` in the middle whose only
     * consequence is whether a later teardown asks the platform to remove a listener it has
     * already removed, which no test can see; measured, deleting it left all 294 tests green.
     * Written as a single assignment there is no such line to leave behind.
     */
    override fun setOnChangedListener(listener: (() -> Unit)?) {
        changeListener?.let { unregister(it) }
        changeListener = listener?.let { register(it) }
    }

    private fun unregister(registration: Registration) {
        registration.route?.let { platformListener ->
            platformCall("Could not remove communication device listener", Unit) {
                audioManager.removeOnCommunicationDeviceChangedListener(platformListener)
            }
        }
        registration.devices?.let { callback ->
            platformCall("Could not remove audio device callback", Unit) {
                audioManager.unregisterAudioDeviceCallback(callback)
            }
        }
    }

    /**
     * Both platform registrations for [listener]; a refused one is null. The platform calls the
     * device callback once on registration with the devices already present - a change that
     * changes nothing, which the listener's owner reconciles like any other.
     *
     * The device callback posts to [mainHandler] itself although the platform is handed the same
     * handler. The platform already delivers there, so in production this is one extra hop; but it
     * makes "posted, never inline" a property of this class rather than of the platform, and it is
     * what keeps the registration callback from running inside `onCreate` - Robolectric's shadow
     * calls it inline, and a denial reported there reached the chat log before anyone listened.
     */
    private fun register(listener: () -> Unit): Registration {
        val route = AudioManager.OnCommunicationDeviceChangedListener { listener() }
        val devices = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                mainHandler.post(listener)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                mainHandler.post(listener)
            }
        }
        // Some vendor builds throw on either registration; routing still works, only the
        // automatic updates are lost.
        return Registration(
            route = platformCall("Communication device listener unavailable", null) {
                audioManager.addOnCommunicationDeviceChangedListener(
                    Executor { mainHandler.post(it) },
                    route,
                )
                route
            },
            devices = platformCall("Audio device callback unavailable", null) {
                audioManager.registerAudioDeviceCallback(devices, mainHandler)
                devices
            },
        )
    }

    /** A registration call: a denial is reported like any other, anything else is only logged. */
    private inline fun <T> platformCall(warning: String, fallback: T, body: () -> T): T =
        try {
            body()
        } catch (e: SecurityException) {
            reportDenial(e)
            fallback
        } catch (e: RuntimeException) {
            Log.w(TAG, warning, e)
            fallback
        }

    private fun AudioDeviceInfo.toCommunicationDevice() =
        CommunicationDevice(id, type, productName?.toString().orEmpty())

    private inline fun <T> guarded(fallback: T, body: () -> T): T =
        try {
            body()
        } catch (e: SecurityException) {
            reportDenial(e)
            fallback
        }

    private fun reportDenial(e: SecurityException) {
        Log.w(TAG, "The platform refused a communication-device call", e)
        if (denialReported) return
        denialReported = true
        onSecurityDenial(e)
    }

    companion object {
        private val TAG: String = AndroidCommunicationDevices::class.java.name
    }
}
