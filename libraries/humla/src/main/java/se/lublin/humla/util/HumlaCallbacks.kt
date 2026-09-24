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
 * Fans [IHumlaObserver] events out to registered observers on [handler]'s thread (the main thread
 * in production). May be called from any thread; callbacks run one at a time in acceptance order.
 *
 * Events from other threads are queued and drained in slices of at most [MAX_EVENTS_PER_SLICE]
 * events or [SLICE_BUDGET_NANOS]. Events raised on the handler's thread while nothing is queued are
 * delivered inline (the service relies on this for its own state changes).
 *
 * Queue bounds: per-subject state refreshes are folded (a later one replaces the queued one in
 * place); above [maxQueuedEvents] the oldest tree-shape event is dropped, never the newest; above
 * [absoluteCeiling] the oldest event of any kind except connection-lifecycle events is dropped.
 * Lifecycle events are raised only on [handler]'s thread, so they cannot pile up while it is stuck.
 * Observers must therefore treat a model event as "re-read this", never as a delta.
 */
class HumlaCallbacks @JvmOverloads constructor(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val maxQueuedEvents: Int = MAX_QUEUED_EVENTS,
) : IHumlaObserver {

    private val observers: MutableSet<IHumlaObserver> = Collections.newSetFromMap(ConcurrentHashMap())
    private val lock = Any()
    // Identity set (Event has no equals): arrival order plus O(1) removal from the middle.
    private val queue = LinkedHashSet<Event>() // guarded by lock
    // The droppable members of queue, in the same order.
    private val droppable = ArrayDeque<Event>() // guarded by lock
    private val folded = HashMap<Any, Event>() // guarded by lock
    private var drainScheduled = false // guarded by lock
    private var dropped = 0L // guarded by lock

    /** How many events are waiting for the delivery thread. Diagnostics; safe from any thread. */
    val queuedEvents: Int get() = synchronized(lock) { queue.size }

    /** How many events either bound has dropped. Diagnostics; safe from any thread. */
    val droppedEvents: Long get() = synchronized(lock) { dropped }

    /**
     * Ceiling above which the oldest non-[Policy.Lifecycle] event is dropped:
     * `queuedEvents <= max(absoluteCeiling, number of Policy.Lifecycle events enqueued)`.
     */
    val absoluteCeiling: Int =
        if (maxQueuedEvents > Int.MAX_VALUE / CEILING_FACTOR) Int.MAX_VALUE
        else maxQueuedEvents * CEILING_FACTOR

    /** What the queue may do with an event besides deliver it; exclusive, so a folded event is never dropped. */
    private sealed interface Policy {
        /** Never folded; dropped only by [absoluteCeiling]. */
        data object Plain : Policy

        /** Connection lifecycle; never dropped. */
        data object Lifecycle : Policy

        class Fold(val key: Any) : Policy

        /** Tree-shape event the first ceiling may drop, oldest first. */
        data object Droppable : Policy
    }

    /** One queued fan-out; [deliver] is written and dequeued under [lock]. */
    private class Event(var deliver: (IHumlaObserver) -> Unit, val policy: Policy)

    private val drain = object : Runnable {
        override fun run() {
            val start = System.nanoTime()
            var delivered = 0
            try {
                // Budget checked after a delivery, so every slice makes progress.
                while (true) {
                    val event = synchronized(lock) {
                        val head = queue.iterator()
                        if (!head.hasNext()) null
                        else head.next().also { head.remove(); forget(it) }
                    } ?: break
                    deliver(event.deliver)
                    delivered++
                    if (delivered >= MAX_EVENTS_PER_SLICE ||
                        System.nanoTime() - start >= SLICE_BUDGET_NANOS
                    ) {
                        break
                    }
                }
            } finally {
                // Also after a throwing observer; a refused post (quitting looper) clears the flag.
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

    private fun dispatch(policy: Policy = Policy.Plain, event: (IHumlaObserver) -> Unit) {
        val inline: Boolean
        synchronized(lock) {
            inline = Looper.myLooper() == handler.looper && queue.isEmpty() && !drainScheduled
            if (!inline) {
                enqueue(Event(event, policy))
                if (!drainScheduled) {
                    drainScheduled = handler.post(drain)
                }
            }
        }
        if (inline) deliver(event)
    }

    /** Caller holds [lock]. */
    private fun enqueue(event: Event) {
        val policy = event.policy
        if (policy is Policy.Fold) {
            val queued = folded[policy.key]
            if (queued != null) {
                queued.deliver = event.deliver
                return
            }
            folded[policy.key] = event
        }
        queue.add(event)
        if (policy is Policy.Droppable) droppable.addLast(event)
        // Only a droppable event may trigger the first ceiling, so a list rebuild always stays queued.
        if (policy is Policy.Droppable) {
            while (queue.size > maxQueuedEvents && dropOldestDroppableExcept(event)) {
                // May be more than one over.
            }
        }
        while (queue.size > absoluteCeiling && dropOldestUnlessLifecycle()) {
            // Same reason as above.
        }
    }

    /** Caller holds [lock]. Drops the oldest tree-shape event other than [newest]; false if none. */
    private fun dropOldestDroppableExcept(newest: Event): Boolean {
        val victim = droppable.firstOrNull() ?: return false
        if (victim === newest) return false // it is the only one, since newest was just appended
        discard(victim)
        return true
    }

    /** Caller holds [lock]. Drops the oldest non-lifecycle event; false if none. */
    private fun dropOldestUnlessLifecycle(): Boolean {
        val victim = queue.firstOrNull { it.policy !is Policy.Lifecycle } ?: return false
        discard(victim)
        return true
    }

    /** Caller holds [lock]. */
    private fun discard(victim: Event) {
        queue.remove(victim)
        forget(victim)
        dropped++
    }

    /**
     * Caller holds [lock]. Drops the index entries of an event that just left [queue]; a droppable
     * event leaving the queue is always the head of [droppable].
     */
    private fun forget(event: Event) {
        when (val policy = event.policy) {
            is Policy.Fold -> folded.remove(policy.key)
            is Policy.Droppable -> droppable.removeFirst()
            else -> {}
        }
    }

    private fun deliver(event: (IHumlaObserver) -> Unit) {
        for (observer in observers) event(observer)
    }

    override fun onConnected() = dispatch(Policy.Lifecycle) { it.onConnected() }
    override fun onConnecting() = dispatch(Policy.Lifecycle) { it.onConnecting() }
    override fun onDisconnected(e: HumlaException?) = dispatch(Policy.Lifecycle) { it.onDisconnected(e) }
    override fun onTLSHandshakeFailed(chain: Array<X509Certificate>?) =
        dispatch(Policy.Lifecycle) { it.onTLSHandshakeFailed(chain) }
    override fun onTLSCertificateChanged(chain: Array<X509Certificate>?) =
        dispatch(Policy.Lifecycle) { it.onTLSCertificateChanged(chain) }
    override fun onChannelAdded(channel: IChannel?) =
        dispatch(Policy.Droppable) { it.onChannelAdded(channel) }
    override fun onChannelStateUpdated(channel: IChannel?) =
        dispatch(Policy.Fold(Subject.CHANNEL_STATE to channel)) { it.onChannelStateUpdated(channel) }
    override fun onChannelRemoved(channel: IChannel?) =
        dispatch(Policy.Droppable) { it.onChannelRemoved(channel) }
    override fun onChannelPermissionsUpdated(channel: IChannel?) =
        dispatch(Policy.Fold(Subject.CHANNEL_PERMISSIONS to channel)) { it.onChannelPermissionsUpdated(channel) }
    override fun onUserConnected(user: IUser?) = dispatch { it.onUserConnected(user) }
    override fun onUserStateUpdated(user: IUser?) =
        dispatch(Policy.Fold(Subject.USER_STATE to user)) { it.onUserStateUpdated(user) }
    override fun onUserTalkStateUpdated(user: IUser?) =
        dispatch(Policy.Fold(Subject.USER_TALK_STATE to user)) { it.onUserTalkStateUpdated(user) }
    override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) =
        dispatch { it.onUserJoinedChannel(user, newChannel, oldChannel) }
    override fun onUserRemoved(user: IUser?, reason: String?) =
        dispatch(Policy.Droppable) { it.onUserRemoved(user, reason) }
    override fun onPermissionDenied(reason: String?) = dispatch { it.onPermissionDenied(reason) }
    override fun onMessageLogged(message: IMessage?) = dispatch { it.onMessageLogged(message) }
    override fun onVoiceTargetChanged(mode: VoiceTargetMode?) = dispatch { it.onVoiceTargetChanged(mode) }
    override fun onLogInfo(message: String?) = dispatch { it.onLogInfo(message) }
    override fun onLogWarning(message: String?) = dispatch { it.onLogWarning(message) }
    override fun onLogError(message: String?) = dispatch { it.onLogError(message) }

    private enum class Subject { CHANNEL_STATE, CHANNEL_PERMISSIONS, USER_STATE, USER_TALK_STATE }

    companion object {
        const val MAX_EVENTS_PER_SLICE = 64
        /** 8 ms, half a 60 Hz frame. */
        const val SLICE_BUDGET_NANOS = 8_000_000L

        /** Queued events before tree-shape events are dropped (16 full slices). */
        const val MAX_QUEUED_EVENTS = 1_024

        /** Absolute ceiling factor: 128 full slices, about a second of delivery work. */
        const val CEILING_FACTOR = 8
    }
}
