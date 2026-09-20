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
 * - An event accepted while an observer unregisters is still delivered to every observer that is
 *   registered when its turn comes, and a slice that ends early - because a callback threw, or
 *   because the looper refused the re-post - leaves the queue intact and re-arms the drain.
 * - The queue is bounded, which costs two things an observer has to know about:
 *   - **State refreshes for one subject are folded.** [onChannelStateUpdated],
 *     [onChannelPermissionsUpdated], [onUserStateUpdated] and [onUserTalkStateUpdated] carry one
 *     live model object and nothing else, and every observer in the tree reads that object's
 *     current state. A second one for the same subject therefore replaces the queued one in place
 *     instead of being appended: N updates for one channel become one delivery, which still says
 *     "re-read this channel". The visible cost is that a talk state that goes on and off again
 *     while the delivery thread is busy is delivered once, showing the state it ended on.
 *   - **Tree-shape events are dropped when the queue is over [maxQueuedEvents].**
 *     [onChannelAdded], [onChannelRemoved] and [onUserRemoved] are the three events whose every
 *     observer answers by rebuilding the whole list from the model
 *     (`ChannelListFragment` -> `ChannelListAdapter.updateChannels()`), so the *oldest* of them is
 *     dropped to make room and the newest still triggers that rebuild. Nothing else is ever
 *     dropped: a queue over its bound that holds none of those three grows instead, and counts the
 *     overrun in [droppedEvents]. Chat, log, lifecycle and one-shot state events therefore always
 *     arrive - `ChannelDescriptionFragment` and `UserCommentFragment` register an observer that
 *     unregisters itself on the one [onChannelStateUpdated]/[onUserStateUpdated] it is waiting for,
 *     which is why those are folded rather than dropped.
 *
 *   An observer must treat a model event as "something about this changed, read it again", never
 *   as a delta it accumulates. That was already true of every observer in the tree; the bound is
 *   what makes it binding.
 *
 * Kotlin makes this class and its members final, where the Java original was subclassable. That
 * narrowing is intentional: nothing in the tree subclasses [HumlaCallbacks], and the dispatch
 * invariants above depend on [registerObserver], [unregisterObserver] and the 19 event methods not
 * being overridden. Open it again only with those invariants in mind.
 */
class HumlaCallbacks @JvmOverloads constructor(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val maxQueuedEvents: Int = MAX_QUEUED_EVENTS,
) : IHumlaObserver {

    private val observers: MutableSet<IHumlaObserver> = Collections.newSetFromMap(ConcurrentHashMap())
    private val lock = Any()
    private val queue = ArrayDeque<Event>() // guarded by lock
    private val folded = HashMap<Any, Event>() // guarded by lock
    private var drainScheduled = false // guarded by lock
    private var dropped = 0L // guarded by lock

    /** How many events are waiting for the delivery thread. Diagnostics; safe from any thread. */
    val queuedEvents: Int get() = synchronized(lock) { queue.size }

    /** How many tree-shape events the bound has dropped. Diagnostics; safe from any thread. */
    val droppedEvents: Long get() = synchronized(lock) { dropped }

    /**
     * One queued fan-out.
     *
     * [foldKey] identifies the subject a state refresh is about; a later event with an equal key
     * overwrites [deliver] in place, which keeps this event's position in the queue and hands the
     * newest payload to the observers. [droppable] marks the tree-shape events the bound may throw
     * away. Both are decided at the raise site, in the overrides at the bottom of this class.
     *
     * [deliver] is a plain var although it is written on the producing thread and read on the
     * delivery thread: every write happens under [lock], and the drain takes [lock] to dequeue,
     * which orders the write before the read. Once dequeued the event is out of [folded] too, so no
     * producer can find it to write again. A `@Volatile` here would be a second guard on the same
     * ordering - one that no test could tell apart from its absence.
     */
    private class Event(
        var deliver: (IHumlaObserver) -> Unit,
        val foldKey: Any?,
        val droppable: Boolean,
    )

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
                    val event = synchronized(lock) {
                        queue.removeFirstOrNull()?.also { e -> e.foldKey?.let { folded.remove(it) } }
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

    private fun dispatch(
        foldKey: Any? = null,
        droppable: Boolean = false,
        event: (IHumlaObserver) -> Unit,
    ) {
        val inline: Boolean
        synchronized(lock) {
            inline = Looper.myLooper() == handler.looper && queue.isEmpty() && !drainScheduled
            if (!inline) {
                enqueue(Event(event, foldKey, droppable))
                if (!drainScheduled) {
                    drainScheduled = handler.post(drain)
                }
            }
        }
        if (inline) deliver(event)
    }

    /** Caller holds [lock]. */
    private fun enqueue(event: Event) {
        val key = event.foldKey
        if (key != null) {
            val queued = folded[key]
            if (queued != null) {
                queued.deliver = event.deliver
                return
            }
            folded[key] = event
        }
        queue.addLast(event)
        while (queue.size > maxQueuedEvents && dropOldestDroppable()) {
            // Keep going: one raise can only push the queue one over the bound, but a bound that
            // was lowered, or a run of undroppable events that has since drained, can leave more.
        }
    }

    /** Caller holds [lock]. Returns false when the queue holds nothing the bound may drop. */
    private fun dropOldestDroppable(): Boolean {
        val victim = queue.firstOrNull { it.droppable } ?: return false
        queue.remove(victim)
        victim.foldKey?.let { folded.remove(it) }
        dropped++
        return true
    }

    private fun deliver(event: (IHumlaObserver) -> Unit) {
        for (observer in observers) event(observer)
    }

    override fun onConnected() = dispatch { it.onConnected() }
    override fun onConnecting() = dispatch { it.onConnecting() }
    override fun onDisconnected(e: HumlaException?) = dispatch { it.onDisconnected(e) }
    override fun onTLSHandshakeFailed(chain: Array<X509Certificate>?) = dispatch { it.onTLSHandshakeFailed(chain) }
    override fun onChannelAdded(channel: IChannel?) =
        dispatch(droppable = true) { it.onChannelAdded(channel) }
    override fun onChannelStateUpdated(channel: IChannel?) =
        dispatch(foldKey = Fold.CHANNEL_STATE to channel) { it.onChannelStateUpdated(channel) }
    override fun onChannelRemoved(channel: IChannel?) =
        dispatch(droppable = true) { it.onChannelRemoved(channel) }
    override fun onChannelPermissionsUpdated(channel: IChannel?) =
        dispatch(foldKey = Fold.CHANNEL_PERMISSIONS to channel) { it.onChannelPermissionsUpdated(channel) }
    override fun onUserConnected(user: IUser?) = dispatch { it.onUserConnected(user) }
    override fun onUserStateUpdated(user: IUser?) =
        dispatch(foldKey = Fold.USER_STATE to user) { it.onUserStateUpdated(user) }
    override fun onUserTalkStateUpdated(user: IUser?) =
        dispatch(foldKey = Fold.USER_TALK_STATE to user) { it.onUserTalkStateUpdated(user) }
    override fun onUserJoinedChannel(user: IUser?, newChannel: IChannel?, oldChannel: IChannel?) =
        dispatch { it.onUserJoinedChannel(user, newChannel, oldChannel) }
    override fun onUserRemoved(user: IUser?, reason: String?) =
        dispatch(droppable = true) { it.onUserRemoved(user, reason) }
    override fun onPermissionDenied(reason: String?) = dispatch { it.onPermissionDenied(reason) }
    override fun onMessageLogged(message: IMessage?) = dispatch { it.onMessageLogged(message) }
    override fun onVoiceTargetChanged(mode: VoiceTargetMode?) = dispatch { it.onVoiceTargetChanged(mode) }
    override fun onLogInfo(message: String?) = dispatch { it.onLogInfo(message) }
    override fun onLogWarning(message: String?) = dispatch { it.onLogWarning(message) }
    override fun onLogError(message: String?) = dispatch { it.onLogError(message) }

    /** The kinds of event that fold, paired with their subject to make a queue key. */
    private enum class Fold { CHANNEL_STATE, CHANNEL_PERMISSIONS, USER_STATE, USER_TALK_STATE }

    companion object {
        /** Upper bound of observer events delivered per main-looper task. */
        const val MAX_EVENTS_PER_SLICE = 64
        /** Wall-clock budget of one drain slice (8 ms, half a 60 Hz frame). */
        const val SLICE_BUDGET_NANOS = 8_000_000L

        /**
         * Upper bound of events waiting for the delivery thread. 1 024 is 16 full slices, so a
         * queue at the bound is about 16 looper tasks of backlog - far more than any burst the UI
         * is meant to render, and small enough that the retained model objects are not a second
         * copy of the tree.
         */
        const val MAX_QUEUED_EVENTS = 1_024
    }
}
