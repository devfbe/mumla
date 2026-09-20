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

import android.media.AudioManager
import android.os.Handler
import android.util.Log
import java.util.concurrent.Executor

/**
 * The subset of `AudioManager`'s communication-device API (API 31) that [ScoRouter] needs.
 *
 * The module's `minSdk` is 31, so this is the only API there is here - spec A4's "on API 31+"
 * carries no alternative branch in this codebase, and `startBluetoothSco` has no reader left once
 * the service is wired to this.
 */
interface CommunicationDevices {
    /** Ids of currently available communication devices of the given [android.media.AudioDeviceInfo] type. */
    fun availableIdsOfType(type: Int): List<Int>

    /** Routes voice to the device; false if the platform refused or the id is gone. */
    fun select(id: Int): Boolean

    /** Returns routing to the platform default. */
    fun clear()

    /** Type of the current communication device, or null if none is set. */
    fun currentType(): Int?

    /** Registers (or with null, removes) a callback for route changes; invoked on the main thread. */
    fun setOnChangedListener(listener: (() -> Unit)?)
}

/**
 * Thin pass-through to [AudioManager.setCommunicationDevice] and friends. The logic lives in
 * [ScoRouter] and is tested against a fake; this class carries only the seam and the wrapper.
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
    private var changeListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    private var denialReported = false

    override fun availableIdsOfType(type: Int): List<Int> = guarded(emptyList()) {
        audioManager.availableCommunicationDevices.filter { it.type == type }.map { it.id }
    }

    override fun select(id: Int): Boolean = guarded(false) {
        val device = audioManager.availableCommunicationDevices.firstOrNull { it.id == id }
        if (device == null) false else audioManager.setCommunicationDevice(device)
    }

    override fun clear() = guarded(Unit) { audioManager.clearCommunicationDevice() }

    override fun currentType(): Int? = guarded(null) { audioManager.communicationDevice?.type }

    override fun setOnChangedListener(listener: (() -> Unit)?) {
        changeListener?.let {
            try {
                audioManager.removeOnCommunicationDeviceChangedListener(it)
            } catch (e: SecurityException) {
                reportDenial(e)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not remove communication device listener", e)
            }
        }
        changeListener = null
        if (listener == null) return
        val platformListener = AudioManager.OnCommunicationDeviceChangedListener { listener() }
        try {
            audioManager.addOnCommunicationDeviceChangedListener(
                Executor { mainHandler.post(it) },
                platformListener,
            )
            changeListener = platformListener
        } catch (e: SecurityException) {
            reportDenial(e)
        } catch (e: RuntimeException) {
            // Some vendor builds throw here; SCO still works, only automatic route updates are lost.
            Log.w(TAG, "Communication device listener unavailable", e)
        }
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
