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
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.protocol.ModelHandler
import se.lublin.humla.session.AudioDeviceCategory
import se.lublin.humla.session.AudioRouter
import se.lublin.humla.util.HumlaCallbacks

// HumlaService's test seams are internal to the library; these re-export them to tests of
// subclasses in other modules.

val HumlaService.testCallbacks: HumlaCallbacks get() = mCallbacks

val HumlaService.testRouter: AudioRouter get() = mRouter

val HumlaService.testActivityInputMode: ActivityInputMode get() = mActivityInputMode

val HumlaService.testEchoOverrides: Map<AudioDeviceCategory, Boolean> get() = mEchoOverrides

/** Replaces the live connection, e.g. with a mock, to reach a session state without a server. */
var HumlaService.testConnection: HumlaConnection?
    get() = mConnection
    set(value) {
        mConnection = value
    }

var HumlaService.testModelHandler: ModelHandler?
    get() = mModelHandler
    set(value) {
        mModelHandler = value
    }

var HumlaService.testConnectionState: HumlaService.ConnectionState
    get() = mConnectionState
    set(value) {
        mConnectionState = value
    }
