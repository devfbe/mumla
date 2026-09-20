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

package se.lublin.humla.net

import se.lublin.humla.protocol.HumlaTCPMessageListener
import se.lublin.humla.protocol.HumlaUDPMessageListener

/**
 * Registration of protocol message handlers, implemented by [HumlaConnection].
 *
 * Registering and unregistering is safe from any thread. A handler runs on the protocol thread, so
 * it must not block: everything behind it - parsing, the model, voice routing - waits on it.
 * A handler removed while a message is being dispatched may still see that dispatch.
 */
interface MessageHandlerRegistry {
    fun addTCPMessageHandlers(vararg handlers: HumlaTCPMessageListener)
    fun removeTCPMessageHandler(handler: HumlaTCPMessageListener)
    fun addUDPMessageHandlers(vararg handlers: HumlaUDPMessageListener)
    fun removeUDPMessageHandler(handler: HumlaUDPMessageListener)
}
