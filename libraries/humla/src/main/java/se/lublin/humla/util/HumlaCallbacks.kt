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
 * A composite wrapper around Humla observers to easily broadcast to each observer.
 * Created by andrew on 12/07/14.
 *
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
 * - Events are delivered in the order they were accepted.
 * - Re-entrancy differs between the two paths, deliberately:
 *   - **Queued path.** An event raised from inside a callback that the drain is running is
 *     appended to the queue, because a drain is scheduled. It lands after the events already
 *     accepted, and the stack does not grow.
 *   - **Inline path.** An event raised from inside a callback that was delivered inline is itself
 *     delivered inline, nested inside the outer fan-out: the inner event reaches every observer
 *     before the outer one has finished reaching all of them, and each nesting level costs a stack
 *     frame. This is what the Java implementation did and what the service relies on for its own
 *     synchronous state changes, so it is preserved on purpose. An observer that re-raises
 *     unconditionally from the handler thread will recurse until the stack overflows, exactly as
 *     before.
 * - Fan-out reads the live registration set, so an observer that calls [unregisterObserver] on
 *   [handler]'s own thread receives nothing from any fan-out that starts afterwards, including
 *   fan-outs for events already queued. Whether it still sees the remainder of a fan-out already
 *   in flight - its own, when it unregisters from inside its callback - is unspecified: the
 *   concurrent set's iterator is weakly consistent. An observer that unregisters from any other
 *   thread gets only that weaker guarantee.
 * - No event is dropped: an event accepted while an observer unregisters is still delivered to
 *   every observer that is registered when its turn comes, and a slice that ends early - because
 *   a callback threw, or because the looper refused the re-post - leaves the queue intact and
 *   re-arms the drain.
 *
 * Kotlin makes this class and its members final, where the Java original was subclassable. That
 * narrowing is intentional: nothing in the tree subclasses [HumlaCallbacks], and the dispatch
 * invariants above depend on [registerObserver], [unregisterObserver] and the 19 event methods not
 * being overridden. Open it again only with those invariants in mind.
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
                // The budget is tested only after an event has been delivered, so a slice that
                // reaches a non-empty queue always makes progress. Testing it first would let a
                // descheduling longer than the budget (a GC pause, a throttled core) produce a
                // slice that delivers nothing and re-posts, which is churn rather than progress.
                while (true) {
                    val event = synchronized(lock) { queue.removeFirstOrNull() } ?: break
                    deliver(event)
                    delivered++
                    if (delivered >= MAX_EVENTS_PER_SLICE ||
                        System.nanoTime() - start >= SLICE_BUDGET_NANOS
                    ) {
                        break
                    }
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
