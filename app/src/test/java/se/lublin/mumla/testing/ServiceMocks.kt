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

package se.lublin.mumla.testing

import io.mockk.every
import kotlinx.coroutines.flow.MutableSharedFlow
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.exception.HumlaDisconnectedException
import se.lublin.humla.session.HumlaEvent

fun <T : IHumlaService> T.stubConnected(session: IHumlaSession): T = apply {
    every { isConnected } returns true
    every { this@stubConnected.session } returns session
}

/** Stubs a service mock as disconnected: like HumlaService, `session` then throws. */
fun <T : IHumlaService> T.stubDisconnected(): T = apply {
    every { isConnected } returns false
    every { session } throws HumlaDisconnectedException()
}

/** Stubs a service mock's event flow; emit into the returned flow to deliver events. */
fun IHumlaService.stubEvents(): MutableSharedFlow<HumlaEvent> =
    MutableSharedFlow<HumlaEvent>(extraBufferCapacity = 64).also { every { events } returns it }
