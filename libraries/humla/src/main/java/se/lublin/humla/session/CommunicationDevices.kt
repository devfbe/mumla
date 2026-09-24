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
 * One entry of `AudioManager.getAvailableCommunicationDevices()`: the platform [id] to select it
 * by, its [AudioDeviceInfo] [type], its product [name] and its [address] (each empty if unknown,
 * never null). The id changes whenever the device reconnects; the address does not.
 */
data class CommunicationDevice(val id: Int, val type: Int, val name: String, val address: String = "")

/**
 * What [AndroidCommunicationDevices.available] lists, for showing without a session: reads only,
 * never routes or changes the audio mode. Empty if the platform refuses.
 */
fun listCommunicationDevices(audioManager: AudioManager): List<CommunicationDevice> =
    try {
        audioManager.availableCommunicationDevices.map { it.toCommunicationDevice() }
    } catch (e: SecurityException) {
        Log.w("CommunicationDevices", "The platform refused the communication device list", e)
        emptyList()
    }

private fun AudioDeviceInfo.toCommunicationDevice() =
    CommunicationDevice(id, type, productName?.toString().orEmpty(), address.orEmpty())

/** The subset of `AudioManager`'s communication-device API (API 31) that routing needs. */
interface CommunicationDevices {
    fun available(): List<CommunicationDevice>

    /** Routes voice to the device; false if the platform refused or the id is gone. */
    fun select(id: Int): Boolean

    fun clear()

    /** Takes or gives back `MODE_IN_COMMUNICATION`, without which the communication device is ignored. */
    fun setCommunicationMode(on: Boolean)

    fun current(): CommunicationDevice?

    /**
     * Registers (null removes) one main-thread callback for route changes and devices coming or
     * going; a new headset raises nothing on the communication-device listener until routed to.
     */
    fun setOnChangedListener(listener: (() -> Unit)?)
}

/**
 * Thin pass-through to [AudioManager.setCommunicationDevice] and friends. The platform doesn't
 * require `BLUETOOTH_CONNECT` for these calls, but some OEMs enforce more, so a [SecurityException]
 * yields the "no headset" value and [onSecurityDenial] is invoked once per instance.
 */
class AndroidCommunicationDevices(
    private val audioManager: AudioManager,
    private val mainHandler: Handler,
    private val onSecurityDenial: (SecurityException) -> Unit,
) : CommunicationDevices {
    private var changeListener: Registration? = null
    private var denialReported = false

    /** Either registration may have been refused. */
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

    override fun setCommunicationMode(on: Boolean) = guarded(Unit) {
        audioManager.mode = if (on) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
    }

    override fun current(): CommunicationDevice? =
        guarded(null) { audioManager.communicationDevice?.toCommunicationDevice() }

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
     * Both platform registrations for [listener]; a refused one is null. The device callback posts
     * to [mainHandler] so delivery is never inline, even when called during registration.
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
        // Some vendor builds throw here; routing still works, only automatic updates are lost.
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
