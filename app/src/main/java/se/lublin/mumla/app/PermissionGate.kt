/*
 * Copyright (C) 2026 The Mumla Authors
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
package se.lublin.mumla.app

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.RECORD_AUDIO
import android.os.Build

/**
 * Obtains the permissions a connection needs before it starts: the microphone, which is
 * required, and on Android 13+ notifications, which are asked for once.
 */
class PermissionGate(private val host: Host) {

    /** The platform side: permission state, requests and the dialogs around them. */
    interface Host {
        val sdkInt: Int

        /** Whether the microphone was requested before; persisted. */
        var microphoneAsked: Boolean

        /** Whether notifications were requested before; persisted. */
        var notificationsAsked: Boolean

        fun isGranted(permission: String): Boolean

        fun shouldShowRationale(permission: String): Boolean

        /** Requests [permission]; the answer must be passed to [onResult]. */
        fun request(permission: String)

        /** Explains why the microphone is needed; [onContinue] if the user wants to go on. */
        fun explainMicrophone(onContinue: () -> Unit)

        /** The microphone can only be allowed in the app's system settings: offer to go there. */
        fun offerMicrophoneSettings()

        fun onMicrophoneDenied()

        fun onNotificationsDenied()
    }

    private var pending: (() -> Unit)? = null

    /** Whether the system asked to explain the microphone before the request now running. */
    private var explainedBeforeRequest = false

    /** Runs [connect] once the permissions are settled, unless the microphone is refused. Replaces an earlier call. */
    fun ensure(connect: () -> Unit) {
        pending = connect
        proceed()
    }

    /** Passes on the answer to a [Host.request]. */
    fun onResult(permission: String, granted: Boolean) {
        when (permission) {
            RECORD_AUDIO -> onMicrophoneResult(granted)
            POST_NOTIFICATIONS -> {
                host.notificationsAsked = true
                if (!granted && host.shouldShowRationale(POST_NOTIFICATIONS)) host.onNotificationsDenied()
                proceed()
            }
        }
    }

    private fun onMicrophoneResult(granted: Boolean) {
        host.microphoneAsked = true
        if (granted) {
            proceed()
            return
        }
        pending = null
        // No rationale after one was due means the user chose not to be asked again.
        val permanently = explainedBeforeRequest && !host.shouldShowRationale(RECORD_AUDIO)
        if (permanently) host.offerMicrophoneSettings() else host.onMicrophoneDenied()
    }

    private fun proceed() {
        val connect = pending ?: return
        when {
            !host.isGranted(RECORD_AUDIO) -> askForMicrophone()
            needsNotifications() -> host.request(POST_NOTIFICATIONS)
            else -> {
                pending = null
                connect()
            }
        }
    }

    private fun askForMicrophone() {
        when {
            host.shouldShowRationale(RECORD_AUDIO) -> host.explainMicrophone { requestMicrophone(explained = true) }
            // Asked before and no rationale: the system would refuse without asking.
            host.microphoneAsked -> {
                pending = null
                host.offerMicrophoneSettings()
            }
            else -> requestMicrophone(explained = false)
        }
    }

    private fun requestMicrophone(explained: Boolean) {
        explainedBeforeRequest = explained
        host.request(RECORD_AUDIO)
    }

    private fun needsNotifications() = host.sdkInt >= Build.VERSION_CODES.TIRAMISU &&
        !host.notificationsAsked && !host.isGranted(POST_NOTIFICATIONS)
}
