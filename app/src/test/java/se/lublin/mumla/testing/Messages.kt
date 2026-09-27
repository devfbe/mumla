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

import se.lublin.humla.model.Message
import se.lublin.mumla.chat.IChatMessage

/** A text message without targets. */
fun textMessage(body: String, actor: Int = -1, actorName: String? = null, receivedTime: Long = 0L) =
    Message(actor, actorName, emptyList(), emptyList(), emptyList(), body, receivedTime)

fun info(body: String) = IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, body)
