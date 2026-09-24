/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import se.lublin.mumla.R
import se.lublin.mumla.app.DrawerAdapter
import se.lublin.mumla.app.MumlaActivity

/**
 * Heads-up notification for unread chat messages, augmenting [MumlaConnectionNotification]. It
 * counts every unread message but lists only the newest [MAX_LINES], each cut to [MAX_TEXT_CHARS].
 */
class MumlaMessageNotification(private val context: Context) {
    private val unreadLines = ArrayDeque<String>()
    private var unreadCount = 0
    private var channelCreated = false

    /** Adds a message from [actorName] with the plain (markup-free) [text] and posts the summary. */
    fun show(actorName: String?, text: String) {
        val shortText = text.ellipsize(MAX_TEXT_CHARS)
        unreadCount++
        unreadLines.addLast(context.getString(R.string.notification_message, actorName, shortText))
        while (unreadLines.size > MAX_LINES) unreadLines.removeFirst()

        val style = NotificationCompat.InboxStyle().setBigContentTitle(
            context.resources.getQuantityString(R.plurals.notification_unread_many, unreadCount, unreadCount),
        )
        unreadLines.forEach(style::addLine)

        val channelListIntent = Intent(context, MumlaActivity::class.java)
            .putExtra(MumlaActivity.EXTRA_DRAWER_FRAGMENT, DrawerAdapter.ITEM_SERVER)
        // FLAG_CANCEL_CURRENT ensures that the extra always gets sent.
        val pendingIntent = PendingIntent.getActivity(
            context, 0, channelListIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setContentTitle(actorName)
            .setContentText(shortText)
            .setVibrate(VIBRATION_PATTERN)
            .setStyle(style)
            .setNumber(unreadCount)
            .build()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /** Dismisses the notification, marking all messages read. */
    fun dismiss() {
        unreadLines.clear()
        unreadCount = 0
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel() {
        if (channelCreated) return
        val channel = NotificationChannel(
            CHANNEL_ID, context.getString(R.string.messageReceived), NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        channelCreated = true
    }

    private companion object {
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "message_channel"
        const val MAX_LINES = 5
        const val MAX_TEXT_CHARS = 200
        val VIBRATION_PATTERN = longArrayOf(0, 100)

        /** Cuts this to at most [max] characters, ending in an ellipsis, without splitting a surrogate pair. */
        fun String.ellipsize(max: Int): String {
            if (length <= max) return this
            var end = max - 1
            if (this[end - 1].isHighSurrogate()) end--
            return substring(0, end) + "…"
        }
    }
}
