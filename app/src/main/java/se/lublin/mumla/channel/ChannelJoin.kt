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
package se.lublin.mumla.channel

import android.content.Context
import android.widget.Toast
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IChannel
import se.lublin.mumla.R

/**
 * Moves the local user into [channel], or tells the user why not when the server has said they
 * may not enter it.
 */
fun IHumlaSession.joinOrExplain(context: Context, channel: IChannel) {
    if (!channel.canEnter) {
        val text = context.getString(R.string.channel_enter_denied, channel.name)
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        return
    }
    joinChannel(channel.id)
}
