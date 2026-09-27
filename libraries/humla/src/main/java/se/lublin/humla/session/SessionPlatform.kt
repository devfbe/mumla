/*
 * Copyright (C) 2026 The Mumla Authors
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

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.PowerManager
import se.lublin.humla.util.HumlaLog

/** Whether a network is up, and a one-shot wait for one. Confined to the session's thread. */
internal interface NetworkMonitor {
    val isOnline: Boolean

    /** Calls [onAvailable] once, on the session's thread, when a default network is up. Replaces an earlier wait. */
    fun awaitNetwork(onAvailable: () -> Unit)

    /** Drops the wait; harmless when there is none. */
    fun stopWaiting()
}

/** The partial wake lock a session holds from its first synchronization until it ends. */
internal interface SessionWakeLock {
    val isHeld: Boolean

    fun acquire()

    fun release()
}

/** A [NetworkMonitor] over the platform's default network callback, delivered on [handler]. */
internal class AndroidNetworkMonitor(
    private val connectivity: ConnectivityManager,
    private val handler: Handler,
) : NetworkMonitor {
    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Deliberately not a `NET_CAPABILITY_INTERNET` check: Robolectric's ShadowConnectivityManager
     * reports no capabilities unless a test sets them.
     */
    override val isOnline: Boolean get() = connectivity.activeNetwork != null

    override fun awaitNetwork(onAvailable: () -> Unit) {
        stopWaiting()
        val next = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                stopWaiting()
                onAvailable()
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(next, handler)
            callback = next
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            // Includes the platform's hidden TooManyRequestsException for too many callbacks.
            HumlaLog.e(TAG, "Error registering the network callback", e)
        }
    }

    override fun stopWaiting() {
        val registered = callback ?: return
        callback = null
        try {
            connectivity.unregisterNetworkCallback(registered)
        } catch (e: IllegalArgumentException) {
            HumlaLog.w(TAG, "The network callback was not registered", e)
        }
    }

    private companion object {
        const val TAG = "AndroidNetworkMonitor"
    }
}

/** A partial [PowerManager] wake lock tagged [tag]; reference counted, as the platform's is. */
internal class AndroidSessionWakeLock(powerManager: PowerManager, tag: String = "Humla:Session") : SessionWakeLock {
    private val lock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)

    override val isHeld: Boolean get() = lock.isHeld

    @SuppressLint("WakelockTimeout") // Held for the whole session, which has no upper bound.
    override fun acquire() = lock.acquire()

    override fun release() = lock.release()
}
