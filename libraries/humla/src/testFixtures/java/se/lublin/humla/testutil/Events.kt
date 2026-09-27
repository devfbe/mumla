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

package se.lublin.humla.testutil

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.HumlaEvent
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Collects [flow] on the main thread from the moment it is built, so events emitted on the main
 * thread are handled before the emitting call returns. Build it on the main thread.
 */
fun <T> collectOnMain(flow: Flow<T>, block: (T) -> Unit): Job =
    CoroutineScope(Dispatchers.Main.immediate).launch(start = CoroutineStart.UNDISPATCHED) {
        flow.collect { block(it) }
    }

/** Calls [block] for each of this session's events; see [collectOnMain]. Cancel the job to stop. */
fun IHumlaSession.onEvents(block: (HumlaEvent) -> Unit): Job = collectOnMain(events, block)

/** Records this session's events; see [collectOnMain]. */
class EventRecorder(session: IHumlaSession) {
    val events: MutableList<HumlaEvent> = CopyOnWriteArrayList()
    val job: Job = session.onEvents { events += it }

    inline fun <reified T : HumlaEvent> of(): List<T> = events.filterIsInstance<T>()
}
