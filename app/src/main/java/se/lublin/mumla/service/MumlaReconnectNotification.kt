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

/** A prompt to reconnect, under why the session ended. Shows on the application context. */
class MumlaReconnectNotification(
    private val context: Context,
    private val listener: OnActionListener,
) {
    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BROADCAST_DISMISS -> listener.onReconnectNotificationDismissed()
                BROADCAST_RECONNECT -> listener.reconnect()
            }
        }
    }

    private var listening = false

    /** Posts the prompt, or replaces the one that is up. */
    fun show(error: String) {
        if (!listening) {
            val filter = IntentFilter().apply {
                addAction(BROADCAST_DISMISS)
                addAction(BROADCAST_RECONNECT)
            }
            ContextCompat.registerReceiver(context, notificationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            listening = true
        }

        NotificationChannels.create(context)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // No setDefaults(VIBRATE | LIGHTS): since API 26 the channel decides both.
            .setContentTitle(context.getString(R.string.mumlaDisconnected))
            .setContentText(error)
            .setDeleteIntent(broadcast(BROADCAST_DISMISS))
            .addAction(R.drawable.ic_action_move, context.getString(R.string.reconnect), broadcast(BROADCAST_RECONNECT))

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        }
    }

    fun hide() {
        if (listening) {
            context.unregisterReceiver(notificationReceiver)
            listening = false
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
    }

    companion object {
        /** Not 2: that is MumlaMessageNotification's id, whose dismissal would cancel this too. */
        private const val NOTIFICATION_ID = 3
        private const val CHANNEL_ID = NotificationChannels.RECONNECT
        private const val BROADCAST_DISMISS = "b_dismiss"
        private const val BROADCAST_RECONNECT = "b_reconnect"
    }
}
