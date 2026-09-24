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

package se.lublin.mumla.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_CANCEL_CURRENT
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import se.lublin.mumla.R
import se.lublin.mumla.app.DrawerAdapter
import se.lublin.mumla.app.MumlaActivity

/**
 * The ongoing notification that keeps the service in the foreground.
 *
 * [show] enters the foreground once and afterwards only re-posts the notification: the platform
 * re-checks the background-start restriction on every `startForeground` call, even a text-only
 * update, so e.g. "Connection lost – reconnecting…" with the screen off must not go through it.
 *
 * [show] returns false for a refusal ([ForegroundServiceStartNotAllowedException],
 * [SecurityException]) instead of throwing; other exceptions (e.g. a missing foreground service
 * type) still propagate. The action receiver is registered exactly while in the foreground.
 */
class MumlaConnectionNotification private constructor(
    private val service: Service,
    contentText: String,
    private val listener: OnActionListener,
) {
    var customContentText: String = contentText
    /** Mute, deafen and overlay: only meaningful while a session is up. */
    var actionsShown: Boolean = false

    /** "Cancel reconnect", for ConnectionLost and Reconnecting. */
    var cancelReconnectShown: Boolean = false

    private var channelCreated = false

    /** True from a successful [show] until [hide]. */
    var isForeground: Boolean = false
        private set

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BROADCAST_MUTE -> listener.onMuteToggled()
                BROADCAST_DEAFEN -> listener.onDeafenToggled()
                BROADCAST_OVERLAY -> listener.onOverlayToggled()
                BROADCAST_CANCEL_RECONNECT -> listener.onReconnectCancelled()
            }
        }
    }

    /**
     * Enters the foreground, or once there replaces the posted notification.
     * @return false if the platform refused the foreground start.
     */
    fun show(): Boolean {
        val notification = buildNotification()
        if (isForeground) {
            service.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            return true
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                service.startForeground(NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                service.startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w(TAG, "Foreground service start not allowed", e)
            return false
        } catch (e: SecurityException) {
            Log.w(TAG, "Not permitted to start a microphone foreground service", e)
            return false
        }
        isForeground = true
        val filter = IntentFilter().apply {
            addAction(BROADCAST_DEAFEN)
            addAction(BROADCAST_MUTE)
            addAction(BROADCAST_OVERLAY)
            addAction(BROADCAST_CANCEL_RECONNECT)
        }
        ContextCompat.registerReceiver(service, notificationReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return true
    }

    /** Leaves the foreground, removes the notification and unregisters the action receiver. */
    fun hide() {
        if (isForeground) {
            isForeground = false
            service.unregisterReceiver(notificationReceiver)
        }
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    private fun buildNotification(): Notification {
        ensureChannel()

        // The app name is always shown, so no content title.
        val builder = NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentText(customContentText)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setShowWhen(false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (actionsShown) {
            builder.addAction(R.drawable.ic_action_microphone, service.getString(R.string.mute), broadcast(BROADCAST_MUTE))
            builder.addAction(R.drawable.ic_action_audio, service.getString(R.string.deafen), broadcast(BROADCAST_DEAFEN))
            builder.addAction(R.drawable.ic_action_channels, service.getString(R.string.overlay), broadcast(BROADCAST_OVERLAY))
        }
        if (cancelReconnectShown) {
            builder.addAction(
                R.drawable.ic_action_delete_dark,
                service.getString(R.string.cancel_reconnect),
                broadcast(BROADCAST_CANCEL_RECONNECT),
            )
        }

        val channelListIntent = Intent(service, MumlaActivity::class.java)
            .putExtra(MumlaActivity.EXTRA_DRAWER_FRAGMENT, DrawerAdapter.ITEM_SERVER)
        // FLAG_CANCEL_CURRENT so the extra is always delivered: extras are not part of a
        // PendingIntent's identity, and MumlaMessageNotification uses the same request code.
        builder.setContentIntent(
            PendingIntent.getActivity(service, 0, channelListIntent, FLAG_CANCEL_CURRENT or FLAG_IMMUTABLE),
        )
        return builder.build()
    }

    private fun ensureChannel() {
        if (channelCreated) return
        NotificationChannels.create(service)
        channelCreated = true
    }

    /** Buttons differ by action, which is what tells PendingIntents apart; they carry no extras. */
    private fun broadcast(action: String): PendingIntent {
        val intent = Intent(action).setPackage(service.packageName)
        return PendingIntent.getBroadcast(service, 0, intent, FLAG_IMMUTABLE)
    }

    interface OnActionListener {
        fun onMuteToggled()
        fun onDeafenToggled()
        fun onOverlayToggled()
        fun onReconnectCancelled()
    }

    companion object {
        private val TAG = MumlaConnectionNotification::class.java.name
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = NotificationChannels.CONNECTION
        private const val BROADCAST_MUTE = "b_mute"
        private const val BROADCAST_DEAFEN = "b_deafen"
        private const val BROADCAST_OVERLAY = "b_overlay"

        /** Distinct from MumlaReconnectNotification's action: both receivers can be registered at once. */
        private const val BROADCAST_CANCEL_RECONNECT = "b_foreground_cancel_reconnect"

        fun create(service: Service, contentText: String, listener: OnActionListener): MumlaConnectionNotification =
            MumlaConnectionNotification(service, contentText, listener)
    }
}
