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

import se.lublin.humla.HumlaService
import se.lublin.humla.audio.inputmode.ActivityInputMode
import se.lublin.humla.audio.routing.AudioRouter
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.SessionStateMachine

// HumlaService's test seams are internal to the library; these re-export them to tests of
// subclasses in other modules.

/** Publishes [event] as the service would, bypassing the service's own filtering. */
fun HumlaService.testEmit(event: HumlaEvent) {
    check(mutableEvents.tryEmit(event)) { "event not accepted: $event" }
}

val HumlaService.testStateMachine: SessionStateMachine get() = stateMachine

val HumlaService.testRouter: AudioRouter get() = audio.router

val HumlaService.testActivityInputMode: ActivityInputMode get() = audio.activityInputMode

/** Replaces the live connection, e.g. with a mock, to reach a session state without a server. */
var HumlaService.testConnection: HumlaConnection?
    get() = connection
    set(value) {
        connection = value
    }

var HumlaService.testModelHandler: ModelHandler?
    get() = modelHandler
    set(value) {
        modelHandler = value
    }
