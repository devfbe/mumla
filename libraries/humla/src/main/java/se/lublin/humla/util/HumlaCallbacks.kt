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
 * - Events are delivered in the order they were accepted, with one exception: a folded refresh
 *   (below) replaces the payload of the event already queued for its subject, so the state it
 *   carries is delivered at that older event's place in the queue - ahead of events accepted in
 *   between. Since every folded event says only "re-read this subject", the order that matters is
 *   preserved; the order of the *payloads* is not.
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
 * - The queue is bounded by two separate ceilings, which cost three things an observer has to know
 *   about. The first, [maxQueuedEvents], is **not** a bound on the queue: it only ever drops
 *   tree-shape events, so it holds only while the events it may not touch stay under it.
 *   [absoluteCeiling] is the one that holds, and it holds up to the events it exempts:
 *   `queue.size <= max(absoluteCeiling, number of Policy.Lifecycle events enqueued)`.
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
 *     dropped to make room. Nothing else is ever dropped, and two rules follow from that which
 *     together are the whole promise:
 *     - **Only a tree-shape event can cause a drop.** An event the bound may not drop never
 *       evicts one it may. Otherwise a burst of chat, log or user events would push every queued
 *       [onChannelAdded] out and leave nothing behind to rebuild from - the channel list would
 *       stay empty until some unrelated event happened to trigger a rebuild. Measured on the
 *       evicting version: 5 000 [onChannelAdded] followed by 1 024 [onLogInfo] delivered *no*
 *       channel at all.
 *     - **The newest tree-shape event is never the one dropped.** It is the delivery that shows
 *       everything the dropped ones carried, so when the queue holds nothing else droppable it
 *       grows past the bound rather than throwing that one away.
 *     A queue over this first ceiling that holds no tree-shape event beside the newest therefore
 *     grows, and counts what it did drop in [droppedEvents].
 *   - **Above [absoluteCeiling] the oldest event goes, whatever else its policy allows.** The
 *     paragraph above is the whole reason this second ceiling exists, and the reason it has to be absolute:
 *     since only a droppable event may push the first ceiling, the real ceiling there is
 *     `#undroppable + 1`, and **nothing bounds `#undroppable`**. Twelve of the nineteen events are
 *     undroppable and two of them are bulk - `ModelHandler.messageUserState` raises
 *     [onUserConnected] *and* an [onLogInfo] per new user, so a 5 000-user server is at least
 *     10 000 events nothing may touch, and the count is the server's to choose. A ceiling a server
 *     can raise is not a ceiling.
 *
 *     The exemption is the four connection-lifecycle events - [onConnected], [onConnecting],
 *     [onDisconnected] and [onTLSHandshakeFailed]. Losing one of them is expensive
 *     (`MumlaActivity`'s observer would stay on its connecting screen for good), but that is not
 *     what makes them safe to exempt, because it is not exclusive: [onPermissionDenied] carries a
 *     `reason` string that is in no model either, and so do chat and log.
 *
 *     **What makes them safe to exempt is that they are confined to the delivery thread.** All
 *     four are raised on [handler]'s own thread: `HumlaConnection` posts every listener callback
 *     to its `mainHandler` (`deliverDisconnected`, `notifyListener`), `HumlaTCP` posts
 *     `onTLSHandshakeFailed` to its callback handler, `HumlaService.connect()` raises
 *     [onConnecting] on main, and `HumlaService.scheduleReconnect()` posts the retry to a main
 *     `Handler`. So for as long as that thread is stuck - the only condition under which this
 *     queue grows at all - **no lifecycle event can arrive to grow it**, and the invariant above
 *     is `absoluteCeiling` plus whatever handful was already queued when the thread stopped
 *     turning. Everything else goes: chat, log and folded refreshes included.
 *
 *     It is worth saying what does *not* hold it up, because it reads as if it should: the count
 *     is **not** the connection's to choose rather than the server's. `HumlaService`
 *     `onConnectionDisconnected` hands a `CONNECTION_ERROR` to the session state machine, and
 *     `scheduleReconnect()` posts the retry after the `ReconnectPolicy` backoff; each cycle raises
 *     [onConnecting] and [onDisconnected] again. The policy caps a run at `maxAttempts` (10), but a
 *     successful session or a connectivity change resets the count, so over a long enough
 *     disconnect loop the server still chooses the number. Confinement is what carries this, and
 *     it is the thing that has to be rechecked when [handler] and the connection's own handler
 *     stop being one thread.
 *
 *     What that costs, plainly.
 *     - **Chat.** A dropped [onMessageLogged] is lost for good: `MumlaService` accumulates chat
 *       into `mMessageLog` (a `ChatMessageLog` bounded at 500) from this callback, so this
 *       queue is the only place one can go missing, and the user sees a gap at the *old* end of the chat pane. Log lines are
 *       lost here too, but this queue is not their only loss - `HumlaService.logInfo` already
 *       discards every info line raised before synchronisation.
 *     - **A folded refresh**, which is the one that can also be felt as a hang:
 *       `ChannelDescriptionFragment` and `UserCommentFragment` register an observer that
 *       unregisters itself on the single [onChannelStateUpdated]/[onUserStateUpdated] it is
 *       waiting for.
 *     - **An avatar.** [onUserConnected] is [Policy.Plain], so this ceiling may throw it away, and
 *       with it the `requestAvatar` that `MumlaService` answers it with. The avatar stays blank
 *       until some later `UserState` frame for that user arrives, which `MumlaService` answers
 *       with a second `requestAvatar` - often, but not reliably.
 *     The channel list itself is unaffected, because every observer of a dropped tree-shape event
 *     rebuilds it from the model anyway.
 *
 *     None of that happens before the main thread has failed to drain
 *     `absoluteCeiling / MAX_EVENTS_PER_SLICE` consecutive slices - 128 of them at the production
 *     numbers, about a second of delivery work at [SLICE_BUDGET_NANOS] each. A backlog deeper than
 *     that is not one the UI catches up on, and losing its oldest end is the cheaper half of the
 *     trade against growing without limit on a server's say-so.
 *
 *   An observer must treat a model event as "something about this changed, read it again", never
 *   as a delta it accumulates. That was already true of every observer in the tree; the ceilings
 *   are what make it binding.
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
    // A LinkedHashSet rather than an ArrayDeque because the ceilings remove from the middle: Event
    // has no equals/hashCode, so this is an identity set that keeps arrival order and removes any
    // element in constant time. An ArrayDeque's remove(Object) is a scan, and the queue is deepest
    // exactly when a ceiling runs.
    private val queue = LinkedHashSet<Event>() // guarded by lock
    // The Policy.Droppable members of queue, in the same order. Without it, finding the oldest
    // droppable event means scanning past every undroppable one ahead of it - measured at 177 us
    // per raise against a queue 11 024 deep, against 1.3 us with it, inside dispatch() under a lock
    // the protocol thread shares with the audio thread. Every removal from either structure takes a
    // head, so the two stay in step: see forget().
    private val droppable = ArrayDeque<Event>() // guarded by lock
    private val folded = HashMap<Any, Event>() // guarded by lock
    private var drainScheduled = false // guarded by lock
    private var dropped = 0L // guarded by lock

    /** How many events are waiting for the delivery thread. Diagnostics; safe from any thread. */
    val queuedEvents: Int get() = synchronized(lock) { queue.size }

    /** How many events either bound has dropped. Diagnostics; safe from any thread. */
    val droppedEvents: Long get() = synchronized(lock) { dropped }

    /**
     * The ceiling that holds for the queue as a whole, above which the oldest event goes whatever
     * its policy is - except [Policy.Lifecycle]. Stated exactly, the invariant is
     * `queuedEvents <= max(absoluteCeiling, number of Policy.Lifecycle events enqueued)`; see this
     * class's doc for why the second term stays small, what the ceiling costs, and why
     * [maxQueuedEvents] alone is not a bound at all.
     */
    val absoluteCeiling: Int =
        if (maxQueuedEvents > Int.MAX_VALUE / CEILING_FACTOR) Int.MAX_VALUE
        else maxQueuedEvents * CEILING_FACTOR

    /**
     * What the queue may do with an event besides deliver it, decided at the raise site in the
     * overrides at the bottom of this class.
     *
     * Exactly one of the four applies, and that is why this is a type rather than a key plus a
     * flag: an event that was folded *and* droppable could be dropped while [folded] still pointed
     * at it, and the next event for that subject would then fold into something already thrown
     * away and never be delivered. Making the combination unrepresentable is cheaper than guarding
     * against it, and leaves nothing behind that no test could reach.
     */
    private sealed interface Policy {
        /** Delivered as raised: never folded, and dropped only by [absoluteCeiling]. */
        data object Plain : Policy

        /**
         * A connection-lifecycle event, which no ceiling may drop. The only such exemption, and
         * what keeps [absoluteCeiling] a bound despite it is **thread confinement**: all four are
         * raised on [handler]'s own thread, so none can arrive while that thread is stuck, which
         * is the only state in which the queue grows. Not "their number is the connection's to
         * choose" - a reconnect loop raises two of them per attempt and nothing caps the attempts
         * yet. See this class's doc.
         */
        data object Lifecycle : Policy

        /** A state refresh for [key]; a later refresh for the same key replaces it in place. */
        class Fold(val key: Any) : Policy

        /** A tree-shape event the first ceiling may throw away, oldest first. */
        data object Droppable : Policy
    }

    /**
     * One queued fan-out.
     *
     * [deliver] is a plain var although it is written on the producing thread and read on the
     * delivery thread: every write happens under [lock], and the drain takes [lock] to dequeue,
     * which orders the write before the read. Once dequeued the event is out of [folded] too, so no
     * producer can find it to write again. A `@Volatile` here would be a second guard on the same
     * ordering - one that no test could tell apart from its absence.
     */
    private class Event(var deliver: (IHumlaObserver) -> Unit, val policy: Policy)

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
        // Only a droppable event may push the first ceiling, and never over itself. An event that
        // may not be dropped must not make room by evicting one that may: a burst of chat, log or
        // user events would otherwise throw away every queued onChannelAdded and then leave nothing
        // to rebuild from, which is the empty channel list this ceiling exists to prevent. The
        // queue grows past it instead, exactly as it does for a queue that holds nothing droppable
        // at all.
        if (policy is Policy.Droppable) {
            while (queue.size > maxQueuedEvents && dropOldestDroppableExcept(event)) {
                // Keep going: one raise can only push the queue one over the ceiling, but a ceiling
                // that was lowered, or a run of undroppable events that has since drained, can
                // leave more.
            }
        }
        // And the ceiling that does bound the queue, which every policy is subject to. It runs on
        // every raise, because the events it exists to catch are exactly the ones the loop above
        // will not look at.
        while (queue.size > absoluteCeiling && dropOldestUnlessLifecycle()) {
            // Same reason as above.
        }
    }

    /**
     * Caller holds [lock]. Drops the oldest tree-shape event other than [newest], and returns false
     * when there is none - which is how the newest one survives a queue that is full of events the
     * first ceiling may not touch. It is the one that must survive: every observer answers it by
     * rebuilding the whole list from the model, so the newest delivery is the one that shows
     * everything the dropped ones carried.
     */
    private fun dropOldestDroppableExcept(newest: Event): Boolean {
        val victim = droppable.firstOrNull() ?: return false
        if (victim === newest) return false // it is the only one, since newest was just appended
        discard(victim)
        return true
    }

    /**
     * Caller holds [lock]. Drops the oldest event [Policy.Lifecycle] does not exempt, and returns
     * false when the queue holds nothing else - at which point it is as short as this ceiling can
     * make it.
     *
     * This scans the exempt prefix, under a lock the protocol thread shares with the audio thread,
     * and with L lifecycle events at the head every raise costs O(L) - the same shape the
     * [droppable] index exists to remove. What keeps L small is **not** that `Policy.Lifecycle` is
     * four event *types*; four types say nothing about how many *instances* queue up. It is that
     * all four are raised on [handler]'s own thread, so none can be added while that thread is
     * stuck, which is the only state in which anything queues at all. Reachable prefixes are
     * therefore whatever was in flight when the thread stopped turning.
     *
     * That reason is the one to recheck when [handler] stops being the thread the connection
     * posts on. `findingTheOldestDroppableEventDoesNotScanTheQueue` does not cover this scan: it
     * fills with `onLogInfo`, so this loop stops at element 0 in every iteration it measures.
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
     * Caller holds [lock]. Drops the index entries of an event that has just left [queue], whether
     * it was delivered or discarded.
     *
     * Leaving a [Policy.Fold] event in [folded] after it has left the queue costs more than the one
     * refresh that is obvious: the index is keyed by *subject*, so the stale entry matches **every**
     * later [onChannelStateUpdated]/[onUserStateUpdated] for that channel or user. Each of them
     * folds into an event nobody holds any more and is never queued at all, so the subject gets no
     * name, no comment, no mute symbol and no talk state for the rest of the connection, and
     * `ChannelDescriptionFragment`/`UserCommentFragment` - which unregister on the single refresh
     * they are waiting for - hang for good. It is the failure [Policy] makes unrepresentable one
     * level down, and it is pinned from both call sites: see
     * `HumlaCallbacksBoundTest.aRefreshThatWasDeliveredDoesNotSwallowTheNextOneForItsSubject` for
     * the drain and `aRefreshTheCeilingDiscardedDoesNotSwallowTheNextOneToo` for [discard].
     *
     * [droppable] is popped rather than searched because every event that leaves the queue and is
     * droppable is [droppable]'s own head. The drain takes the queue's head; both ceilings take the
     * oldest droppable event, or - for the absolute one - the oldest event that is not
     * [Policy.Lifecycle], and a lifecycle event is never in [droppable] to be skipped past.
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

        /**
         * Upper bound of events waiting for the delivery thread. 1 024 is 16 full slices, so a
         * queue at the bound is about 16 looper tasks of backlog - far more than any burst the UI
         * is meant to render, and small enough that the retained model objects are not a second
         * copy of the tree.
         */
        const val MAX_QUEUED_EVENTS = 1_024

        /**
         * How far above the first ceiling the absolute one sits. Eight puts it at
         * `8 * 1 024 / 64` = 128 full drain slices, about a second of main-thread delivery work at
         * [SLICE_BUDGET_NANOS] each: far enough that no burst the UI is meant to render reaches it,
         * near enough that what it throws away is a backlog the UI was never going to catch up on.
         */
        const val CEILING_FACTOR = 8
    }
}
