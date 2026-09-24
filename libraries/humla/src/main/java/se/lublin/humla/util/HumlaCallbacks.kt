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
 * in production).
 *
 * Events raised on other threads are queued and drained in slices of at most
 * [MAX_EVENTS_PER_SLICE] events or [SLICE_BUDGET_NANOS], after which the drain re-posts itself, so
 * other main-looper work never waits more than one slice. Events raised on the handler's own thread
 * while nothing is queued are delivered inline (the service relies on this for its own state
 * changes); an event raised from an inline callback is itself delivered inline, nested.
 *
 * Thread contract:
 * - All observer methods, [registerObserver] and [unregisterObserver] may be called from any thread.
 * - Callbacks run on [handler]'s thread, one at a time, in the order events were accepted - except
 *   that a folded refresh takes the queue position of the refresh it replaced.
 * - Fan-out iterates the live (weakly consistent) observer set; an observer unregistered on the
 *   handler thread receives nothing from any fan-out that starts afterwards.
 * - A slice that ends early (a callback threw, the looper refused the re-post) keeps the queue
 *   intact and re-arms the drain.
 *
 * Queue bounds:
 * - State refreshes for one subject ([onChannelStateUpdated], [onChannelPermissionsUpdated],
 *   [onUserStateUpdated], [onUserTalkStateUpdated]) are folded: a second one replaces the queued
 *   one, so a talk state that toggles while the thread is busy is delivered once.
 * - Above [maxQueuedEvents], the oldest tree-shape event ([onChannelAdded], [onChannelRemoved],
 *   [onUserRemoved]) is dropped - never the newest, and only a tree-shape event triggers a drop, so
 *   a list rebuild is always still queued. Observers rebuild the whole list from the model anyway.
 * - Above [absoluteCeiling], the oldest event of any kind is dropped (chat and log included),
 *   except the connection-lifecycle events. Those are raised only on [handler]'s thread, so none
 *   can arrive while it is stuck - the only time the queue grows. Recheck this if [handler] and the
 *   connection's handler ever stop being the same thread.
 *
 * Observers must treat a model event as "re-read this", never as a delta to accumulate.
 */
class HumlaCallbacks @JvmOverloads constructor(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val maxQueuedEvents: Int = MAX_QUEUED_EVENTS,
) : IHumlaObserver {

    private val observers: MutableSet<IHumlaObserver> = Collections.newSetFromMap(ConcurrentHashMap())
    private val lock = Any()
    // An identity set (Event has no equals) that keeps arrival order and removes from the middle
    // in constant time, which the ceilings need.
    private val queue = LinkedHashSet<Event>() // guarded by lock
    // The droppable members of queue, in the same order, so the first ceiling need not scan past
    // undroppable events. Every removal takes a head, which keeps the two in step: see forget().
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

    /**
     * What the queue may do with an event besides deliver it. A sealed type so that an event can't
     * be both folded and droppable (a dropped event still indexed in [folded] would swallow the
     * next refresh for its subject).
     */
    private sealed interface Policy {
        /** Delivered as raised: never folded, and dropped only by [absoluteCeiling]. */
        data object Plain : Policy

        /** A connection-lifecycle event, which no ceiling drops; see the class doc for why that is bounded. */
        data object Lifecycle : Policy

        /** A state refresh for [key]; a later refresh for the same key replaces it in place. */
        class Fold(val key: Any) : Policy

        /** A tree-shape event the first ceiling may throw away, oldest first. */
        data object Droppable : Policy
    }

    /**
     * One queued fan-out. [deliver] is written and dequeued under [lock], which orders the write
     * before the read on the delivery thread.
     */
    private class Event(var deliver: (IHumlaObserver) -> Unit, val policy: Policy)

    private val drain = object : Runnable {
        override fun run() {
            val start = System.nanoTime()
            var delivered = 0
            try {
                // The budget is checked only after a delivery, so every slice makes progress even
                // after a descheduling longer than the budget.
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
                // Also runs when an observer threw. Clearing the flag when the post is refused (a
                // quitting looper) lets a later event re-arm the drain.
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
        // Only a droppable event may push the first ceiling: an undroppable burst must not evict
        // queued onChannelAdded events and leave nothing to rebuild the list from.
        if (policy is Policy.Droppable) {
            while (queue.size > maxQueuedEvents && dropOldestDroppableExcept(event)) {
                // A lowered ceiling or a drained undroppable run can leave more than one over.
            }
        }
        // The ceiling that bounds the queue, applied to every policy.
        while (queue.size > absoluteCeiling && dropOldestUnlessLifecycle()) {
            // Same reason as above.
        }
    }

    /**
     * Caller holds [lock]. Drops the oldest tree-shape event other than [newest] and returns false
     * when there is none; the newest must survive because its delivery shows everything dropped.
     */
    private fun dropOldestDroppableExcept(newest: Event): Boolean {
        val victim = droppable.firstOrNull() ?: return false
        if (victim === newest) return false // it is the only one, since newest was just appended
        discard(victim)
        return true
    }

    /**
     * Caller holds [lock]. Drops the oldest event [Policy.Lifecycle] does not exempt, and returns
     * false when there is none. Scans the lifecycle prefix, which stays short because lifecycle
     * events are raised only on [handler]'s thread.
     */
    private fun dropOldestUnlessLifecycle(): Boolean {
        val victim = queue.firstOrNull { it.policy !is Policy.Lifecycle } ?: return false
        discard(victim)
        return true
    }

    /** Caller holds [lock]. Throws [victim] away unheard and counts it. */
    private fun discard(victim: Event) {
        queue.remove(victim)
        forget(victim)
        dropped++
    }

    /**
     * Caller holds [lock]. Drops the index entries of an event that has just left [queue]. A stale
     * [folded] entry would swallow every later refresh for that subject. [droppable] is popped
     * because every droppable event that leaves the queue is its head.
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

    /** The kinds of event that fold, paired with the model object to make a queue key. */
    private enum class Subject { CHANNEL_STATE, CHANNEL_PERMISSIONS, USER_STATE, USER_TALK_STATE }

    companion object {
        /** Upper bound of observer events delivered per main-looper task. */
        const val MAX_EVENTS_PER_SLICE = 64
        /** Wall-clock budget of one drain slice (8 ms, half a 60 Hz frame). */
        const val SLICE_BUDGET_NANOS = 8_000_000L

        /** Upper bound of queued events before tree-shape events are dropped (16 full slices). */
        const val MAX_QUEUED_EVENTS = 1_024

        /**
         * How far above [MAX_QUEUED_EVENTS] the absolute ceiling sits: 128 full slices, about a
         * second of main-thread delivery work.
         */
        const val CEILING_FACTOR = 8
    }
}
