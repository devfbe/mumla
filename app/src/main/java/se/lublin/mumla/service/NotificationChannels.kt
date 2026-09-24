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

package se.lublin.mumla.service

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import se.lublin.mumla.R

/**
 * Mumla's notification channels, in one group. Creating them again is cheap and refreshes their
 * names and descriptions; importance, once created, is the user's.
 */
object NotificationChannels {
    const val GROUP = "server"
    const val CONNECTION = "connection_status"
    const val MESSAGES = "message_channel"
    const val RECONNECT = "reconnecting_channel"

    /** The connection channel's id before its importance was lowered, which needed a new id. */
    private const val LEGACY_CONNECTION = "connected_channel"

    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.deleteNotificationChannel(LEGACY_CONNECTION)
        manager.createNotificationChannelGroup(
            NotificationChannelGroup(GROUP, context.getString(R.string.notification_group_server)),
        )
        fun channel(id: String, name: Int, description: Int, importance: Int) =
            NotificationChannel(id, context.getString(name), importance).apply {
                this.description = context.getString(description)
                group = GROUP
            }
        manager.createNotificationChannels(
            listOf(
                channel(CONNECTION, R.string.connected, R.string.notification_channel_connection_description,
                    NotificationManager.IMPORTANCE_LOW),
                channel(MESSAGES, R.string.messageReceived, R.string.notification_channel_messages_description,
                    NotificationManager.IMPORTANCE_DEFAULT),
                channel(RECONNECT, R.string.connection_lost_reconnecting,
                    R.string.notification_channel_reconnect_description, NotificationManager.IMPORTANCE_DEFAULT),
            ),
        )
    }
}
