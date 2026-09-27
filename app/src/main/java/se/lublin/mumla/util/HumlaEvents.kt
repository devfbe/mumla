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
package se.lublin.mumla.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.inMainThreadSlices

/**
 * Collects [session]'s events in [scope] on the main thread until the returned job or [scope] is
 * cancelled. Subscribed when this returns; events emitted on the main thread arrive inline.
 */
fun collectEvents(scope: CoroutineScope, session: IHumlaSession, onEvent: (HumlaEvent) -> Unit): Job =
    scope.launch(Dispatchers.Main.immediate, start = CoroutineStart.UNDISPATCHED) {
        session.events.inMainThreadSlices().collect(onEvent)
    }
