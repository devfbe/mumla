/*
 * Copyright (C) 2014 Andrew Comminos
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

import android.os.Handler
import android.os.Looper
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.IUser
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Fan-out of [IHumlaObserver] events to registered observers, delivered on the thread of
 * [handler] (the main thread in production).
 *
 * Events raised on other threads are queued and drained in slices of at most
 * [MAX_EVENTS_PER_SLICE] events or [SLICE_BUDGET_NANOS], after which the drain re-posts itself,
 * so a task posted to the main looper in between never waits for more than one slice (spec A1).
 * Events raised on the handler's own thread while nothing is queued are delivered inline, which
 * preserves the synchronous semantics the service relies on for its own state changes.
 *
 * Thread contract:
 * - Every [IHumlaObserver] method, [registerObserver] and [unregisterObserver] may be called from
 *   any thread, concurrently.
 * - Observer callbacks always run on [handler]'s thread, one at a time, never concurrently.
 * - Events are delivered in the order they were accepted. An event raised from inside a callback
 *   is appended to the queue rather than recursing, so it lands after the events already accepted.
 * - Fan-out reads the live registration set, so an observer that unregisters on [handler]'s own
 *   thread is guaranteed to receive nothing afterwards, including events already queued. An
 *   observer that unregisters from another thread may still see an event whose fan-out is already
 *   in progress.
 * - No event is dropped: an event accepted while an observer unregisters is still delivered to
 *   every observer that is registered when its turn comes, and a slice that ends early - because
 *   a callback threw, or because the looper refused the re-post - leaves the queue intact and
 *   re-arms the drain.
 */
class HumlaCallbacks @JvmOverloads constructor(
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : IHumlaObserver {

    private val observers: MutableSet<IHumlaObserver> = Collections.newSetFromMap(ConcurrentHashMap())
    private val lock = Any()
    private val queue = ArrayDeque<(IHumlaObserver) -> Unit>() // guarded by lock
    private var drainScheduled = false // guarded by lock

    private val drain = object : Runnable {
        override fun run() {
            val start = System.nanoTime()
            var delivered = 0
            try {
                // The first event of a slice always runs, so the drain cannot stall.
                while (delivered < MAX_EVENTS_PER_SLICE && System.nanoTime() - start < SLICE_BUDGET_NANOS) {
                    val event = synchronized(lock) { queue.removeFirstOrNull() } ?: break
                    deliver(event)
                    delivered++
                }
            } finally {
                // Also runs when an observer threw: the rest of the queue keeps its turn. Clearing
                // the flag when the post is refused (a quitting looper) lets a later event re-arm
                // the drain instead of wedging it forever.
                synchronized(lock) {
                    drainScheduled = queue.isNotEmpty() && handler.post(this)
                }
            }
        }
    }

    fun registerObserver(observer: IHumlaObserver) {
        observers.add(observer)
    }

    fun unregisterObserver(observer: IHumlaObserver) {
        observers.remove(observer)
    }

    private fun dispatch(event: (IHumlaObserver) -> Unit) {
        val inline: Boolean
        synchronized(lock) {
            inline = Looper.myLooper() == handler.looper && queue.isEmpty() && !drainScheduled
            if (!inline) {
                queue.addLast(event)
                if (!drainScheduled) {
                    drainScheduled = handler.post(drain)
                }
            }
        }
        if (inline) deliver(event)
    }

    private fun deliver(event: (IHumlaObserver) -> Unit) {
        for (observer in observers) event(observer)
    }

    override fun onConnected() = dispatch { it.onConnected() }
    override fun onConnecting() = dispatch { it.onConnecting() }
    override fun onDisconnected(e: HumlaException?) = dispatch { it.onDisconnected(e) }
    override fun onTLSHandshakeFailed(chain: Array<X509Certificate>?) = dispatch { it.onTLSHandshakeFailed(chain) }
    override fun onChannelAdded(channel: IChannel?) = dispatch { it.onChannelAdded(channel) }
    override fun onChannelStateUpdated(channel: IChannel?) = dispatch { it.onChannelStateUpdated(channel) }
    override fun onChannelRemoved(channel: IChannel?) = dispatch { it.onChannelRemoved(channel) }
    override fun onChannelPermissionsUpdated(channel: IChannel?) = dispatch { it.onChannelPermissionsUpdated(channel) }
    override fun onUserConnected(user: IUser?) = dispatch { it.onUserConnected(user) }
    override fun onUserStateUpdated(user: IUser?) = dispatch { it.onUserStateUpdated(user) }
    override fun onUserTalkStateUpdated(user: IUser?) = dispatch { it.onUserTalkStateUpdated(user) }
    override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) =
        dispatch { it.onUserJoinedChannel(user, newChannel, oldChannel) }
    override fun onUserRemoved(user: IUser?, reason: String?) = dispatch { it.onUserRemoved(user, reason) }
    override fun onPermissionDenied(reason: String?) = dispatch { it.onPermissionDenied(reason) }
    override fun onMessageLogged(message: IMessage?) = dispatch { it.onMessageLogged(message) }
    override fun onVoiceTargetChanged(mode: VoiceTargetMode?) = dispatch { it.onVoiceTargetChanged(mode) }
    override fun onLogInfo(message: String?) = dispatch { it.onLogInfo(message) }
    override fun onLogWarning(message: String?) = dispatch { it.onLogWarning(message) }
    override fun onLogError(message: String?) = dispatch { it.onLogError(message) }

    companion object {
        /** Upper bound of observer events delivered per main-looper task. */
        const val MAX_EVENTS_PER_SLICE = 64
        /** Wall-clock budget of one drain slice (8 ms, half a 60 Hz frame). */
        const val SLICE_BUDGET_NANOS = 8_000_000L
    }
}
