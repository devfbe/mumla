/*
 * Copyright (C) 2015 Andrew Comminos
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
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import se.lublin.mumla.R

/**
 * A notification indicating auto-reconnect is in progress, or if auto-reconnect is disabled,
 * a prompt to reconnect with the error message.
 * Created by andrew on 17/01/15.
 */
class MumlaReconnectNotification(
    private val context: Context,
    private val listener: OnActionListener,
) {
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BROADCAST_DISMISS -> listener.onReconnectNotificationDismissed()
                BROADCAST_RECONNECT -> listener.reconnect()
                BROADCAST_CANCEL_RECONNECT -> listener.cancelReconnect()
            }
        }
    }

    fun show(error: String, autoReconnect: Boolean) {
        val filter = IntentFilter().apply {
            addAction(BROADCAST_DISMISS)
            addAction(BROADCAST_RECONNECT)
            addAction(BROADCAST_CANCEL_RECONNECT)
        }
        // Registering an already registered receiver does not throw, so the try/catch the Java
        // original had here could never run (measured under Robolectric: a second registration
        // adds a second entry and no exception).
        ContextCompat.registerReceiver(context, notificationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.connection_lost_reconnecting),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // No setDefaults(VIBRATE | LIGHTS): from API 26 on the channel decides both, and the
            // built notification carried defaults == 0 with the call in place (measured).
            .setContentTitle(context.getString(R.string.mumlaDisconnected))
            .setContentText(error)
            .setDeleteIntent(broadcast(BROADCAST_DISMISS))

        if (autoReconnect) {
            builder.addAction(
                R.drawable.ic_action_delete_dark,
                context.getString(R.string.cancel_reconnect),
                broadcast(BROADCAST_CANCEL_RECONNECT),
            )
            builder.setOngoing(true)
        } else {
            builder.addAction(R.drawable.ic_action_move, context.getString(R.string.reconnect), broadcast(BROADCAST_RECONNECT))
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    fun hide() {
        try {
            context.unregisterReceiver(notificationReceiver)
        } catch (e: IllegalArgumentException) {
            // Thrown if receiver is not registered.
            e.printStackTrace()
        }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** See MumlaConnectionNotification.broadcast: the action is the identity, nothing goes stale. */
    private fun broadcast(action: String): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName)
        return PendingIntent.getBroadcast(context, 0, intent, FLAG_IMMUTABLE)
    }

    interface OnActionListener {
        fun onReconnectNotificationDismissed()
        fun reconnect()
        fun cancelReconnect()
    }

    companion object {
        /**
         * Not 2: MumlaMessageNotification posts the chat notification under 2, so every chat
         * dismissal -- one runs on every disconnect -- cancelled this prompt as well, and every
         * chat message replaced it.
         */
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = "reconnecting_channel"
        private const val BROADCAST_DISMISS = "b_dismiss"
        private const val BROADCAST_RECONNECT = "b_reconnect"
        private const val BROADCAST_CANCEL_RECONNECT = "b_cancel_reconnect"

        @JvmStatic
        fun show(
            context: Context,
            error: String,
            autoReconnect: Boolean,
            listener: OnActionListener,
        ): MumlaReconnectNotification = MumlaReconnectNotification(context, listener).also {
            it.show(error, autoReconnect)
        }
    }
}
