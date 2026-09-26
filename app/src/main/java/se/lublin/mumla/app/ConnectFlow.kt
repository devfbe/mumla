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

import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.os.Build
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.lublin.humla.model.Server
import se.lublin.humla.net.HumlaConnection
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.session.inMainThreadSlices
import se.lublin.mumla.db.PublicServer
import se.lublin.mumla.R
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.Settings
import se.lublin.mumla.ui.showConfirmDialog
import se.lublin.mumla.ui.showMessageDialog
import se.lublin.mumla.util.isPortOpen
import se.lublin.mumla.util.Orbot

/**
 * Connects [activity] to servers: gets the permissions a session needs, confirms leaving the
 * current server and checks that Tor is usable if enabled. [service] is the bound service, if any.
 */
class ConnectFlow(
    private val activity: AppCompatActivity,
    private val settings: Settings,
    private val service: () -> IMumlaService?,
) {
    private val gate = PermissionGate(PermissionHost())
    private val microphoneRequest = requestLauncher(Manifest.permission.RECORD_AUDIO)
    private val notificationsRequest = requestLauncher(Manifest.permission.POST_NOTIFICATIONS)

    /** Waits for a disconnect to connect elsewhere; see [awaitDisconnectThenConnect]. */
    private var pendingReconnect: Job? = null

    private fun requestLauncher(permission: String) =
        activity.registerForActivityResult(RequestPermission()) { gate.onResult(permission, it) }

    fun connect(server: Server) {
        gate.ensure { connectNow(server) }
    }

    /** Asks for the username to use on the public [server], then connects. */
    fun connectToPublic(server: PublicServer) {
        val usernameField = EditText(activity).apply { hint = settings.defaultUsername }
        val padding = activity.resources.getDimension(R.dimen.padding_medium).toInt()
        val layout = FrameLayout(activity).apply {
            addView(usernameField)
            setPadding(padding, 0, padding, 0)
        }
        MaterialAlertDialogBuilder(activity)
            .setView(layout)
            .setTitle(R.string.connectToServer)
            .setPositiveButton(R.string.connect) { _, _ ->
                server.username = usernameField.text.toString().ifEmpty { settings.defaultUsername }
                connect(server)
            }
            .show()
    }

    private inner class PermissionHost : PermissionGate.Host {
        override val sdkInt: Int get() = Build.VERSION.SDK_INT

        override var microphoneAsked: Boolean
            get() = settings.isMicrophonePermissionAsked
            set(value) {
                settings.isMicrophonePermissionAsked = value
            }

        override var notificationsAsked: Boolean
            get() = settings.isNotificationPermissionAsked
            set(value) {
                settings.isNotificationPermissionAsked = value
            }

        override fun isGranted(permission: String) =
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

        override fun shouldShowRationale(permission: String) =
            ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

        override fun request(permission: String) {
            if (permission == Manifest.permission.RECORD_AUDIO) {
                microphoneRequest.launch(permission)
            } else {
                notificationsRequest.launch(permission)
            }
        }

        override fun explainMicrophone(onContinue: () -> Unit) {
            activity.showConfirmDialog(activity.getString(R.string.microphone_permission_rationale)) { onContinue() }
        }

        override fun offerMicrophoneSettings() {
            activity.showConfirmDialog(
                activity.getString(R.string.microphone_permission_settings),
                R.string.open_settings,
            ) {
                val uri = Uri.fromParts("package", activity.packageName, null)
                activity.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))
            }
        }

        override fun onMicrophoneDenied() {
            Toast.makeText(activity, R.string.grant_perm_microphone, Toast.LENGTH_LONG).show()
        }

        override fun onNotificationsDenied() {
            Toast.makeText(activity, R.string.grant_perm_notifications, Toast.LENGTH_LONG).show()
        }
    }

    private fun connectNow(server: Server) {
        val service = service()
        if (service != null && service.isConnected) {
            activity.showConfirmDialog(activity.getString(R.string.reconnect_dialog_message), R.string.connect) {
                awaitDisconnectThenConnect(service, server)
                service.disconnect()
            }
            return
        }
        when {
            !settings.isTorEnabled -> start(server)
            !Orbot.isInstalled(activity) -> {
                settings.isTorEnabled = false
                showMessage(activity.getString(R.string.orbot_not_installed))
            }
            else -> startOverTor(server)
        }
    }

    private fun startOverTor(server: Server) {
        activity.lifecycleScope.launch {
            if (isPortOpen(HumlaConnection.TOR_HOST, HumlaConnection.TOR_PORT, TOR_PROBE_TIMEOUT_MS)) {
                start(server)
            } else {
                showMessage(activity.getString(R.string.orbot_tor_failed, HumlaConnection.TOR_PORT))
            }
        }
    }

    /** Connects to [server] once [service] reports the current session disconnected. */
    private fun awaitDisconnectThenConnect(service: IMumlaService, server: Server) {
        pendingReconnect?.cancel()
        pendingReconnect = activity.lifecycleScope.launch(Dispatchers.Main.immediate, CoroutineStart.UNDISPATCHED) {
            service.events.inMainThreadSlices().first { it is HumlaEvent.Disconnected }
            pendingReconnect = null
            connect(server)
        }
    }

    private fun start(server: Server) {
        startServerConnect(activity, server)
    }

    private fun showMessage(message: String) {
        activity.showMessageDialog(message)
    }

    private companion object {
        const val TOR_PROBE_TIMEOUT_MS = 2000
    }
}
