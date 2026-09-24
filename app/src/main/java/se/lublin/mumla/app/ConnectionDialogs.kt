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

import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.HumlaService.ConnectionState
import se.lublin.humla.model.Server
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.util.HumlaException
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.util.MumlaTrustStore
import se.lublin.mumla.util.toHex
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** The dialogs that tell the user how a connection goes. Main thread only. */
class ConnectionDialogs(
    private val activity: AppCompatActivity,
    private val settings: Settings,
    private val listener: Listener,
) {
    interface Listener {
        /** Connects to [server] again, e.g. after its certificate was trusted. */
        fun reconnect(server: Server)

        /** Connects to [server] again with the password the user just entered. */
        fun reconnectWithPassword(server: Server)
    }

    private var connectingDialog: AlertDialog? = null
    private var errorDialog: AlertDialog? = null

    private val torSuffix get() = if (settings.isTorEnabled) " (Tor)" else ""

    fun dismiss() {
        connectingDialog?.dismiss()
        errorDialog?.dismiss()
    }

    /** Shows the connecting or error dialog [service]'s state calls for, and dismisses the others. */
    fun update(service: IMumlaService) {
        dismiss()
        when (service.connectionState) {
            ConnectionState.CONNECTING -> showConnecting(service)
            // Only bother the user if the error hasn't already been shown.
            ConnectionState.CONNECTION_LOST -> if (!service.isErrorShown) showError(service)
            else -> Unit
        }
    }

    private fun showConnecting(service: IMumlaService) {
        // The port is left out: the SRV lookup that may change it comes later.
        val host = service.targetServer?.host
        connectingDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.connecting_to_server, host) + torSuffix)
            .setView(R.layout.dialog_progress)
            .setCancelable(true)
            .setOnCancelListener {
                service.disconnect()
                Toast.makeText(activity, R.string.cancelled, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showError(service: IMumlaService) {
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.connectionRefused) + torSuffix)
            .setCancelable(false)
        val error = service.connectionError
        when {
            error != null && service.isReconnecting -> builder
                .setMessage(
                    error.message + "\n\n" +
                        activity.getString(R.string.attempting_reconnect, error.cause?.message ?: "unknown"),
                )
                .setPositiveButton(R.string.cancel_reconnect) { _, _ ->
                    service.cancelReconnect()
                    service.markErrorShown()
                }
            error != null && error.isWrongPassword -> {
                val passwordField = EditText(activity).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setHint(R.string.password)
                }
                builder.setTitle(R.string.invalid_password)
                    .setMessage(error.message)
                    .setView(passwordField)
                    .setPositiveButton(R.string.reconnect) { _, _ ->
                        val server = service.targetServer ?: return@setPositiveButton
                        server.password = passwordField.text.toString()
                        listener.reconnectWithPassword(server)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> service.markErrorShown() }
            }
            else -> builder
                .setMessage(error?.message ?: activity.getString(R.string.unknown))
                .setPositiveButton(android.R.string.ok) { _, _ -> service.markErrorShown() }
        }
        errorDialog = builder.show()
    }

    /** Offers to trust the unknown [certificate] of [server]. */
    fun showUntrustedCertificate(server: Server, certificate: X509Certificate) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.untrusted_certificate)
            .setView(certificateInfoView(certificate))
            .setPositiveButton(R.string.allow) { _, _ -> trustAndReconnect(server, certificate) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Warns that [server] presents a [certificate] other than the one trusted before. */
    fun showCertificateChanged(server: Server, certificate: X509Certificate) {
        // Cancel is the positive, default-looking choice: replacing the pin is what an attacker wants.
        MaterialAlertDialogBuilder(activity)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(R.string.certificate_changed_title)
            .setMessage(activity.getString(R.string.certificate_changed_message, server.host))
            .setView(certificateInfoView(certificate))
            .setPositiveButton(android.R.string.cancel, null)
            .setNegativeButton(R.string.certificate_changed_accept) { _, _ -> trustAndReconnect(server, certificate) }
            .show()
    }

    fun showPermissionDenied(reason: String) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.perm_denied)
            .setMessage(reason)
            .show()
    }

    private fun certificateInfoView(certificate: X509Certificate): View {
        val layout = activity.layoutInflater.inflate(R.layout.certificate_info, null)
        val textView = layout.findViewById<TextView>(R.id.certificate_info_text)
        textView.text = try {
            val encoded = certificate.encoded
            activity.getString(
                R.string.certificate_info,
                certificate.subjectDN.name,
                certificate.notBefore.toString(),
                certificate.notAfter.toString(),
                fingerprint("SHA-1", encoded),
                fingerprint("SHA-256", encoded),
            )
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Could not fingerprint the certificate", e)
            certificate.toString()
        }
        return layout
    }

    private fun trustAndReconnect(server: Server, certificate: X509Certificate) {
        try {
            MumlaTrustStore.pinCertificate(activity, server.host, certificate)
        } catch (e: IOException) {
            onTrustFailed(e)
            return
        } catch (e: GeneralSecurityException) {
            onTrustFailed(e)
            return
        }
        Toast.makeText(activity, R.string.trust_added, Toast.LENGTH_LONG).show()
        listener.reconnect(server)
    }

    private fun onTrustFailed(e: Exception) {
        Log.w(TAG, "Could not trust the certificate", e)
        Toast.makeText(activity, R.string.trust_add_failed, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "ConnectionDialogs"

        /** The [algorithm] digest of [data] as colon-separated hex, e.g. "ab:cd:...". */
        fun fingerprint(algorithm: String, data: ByteArray): String =
            MessageDigest.getInstance(algorithm).digest(data).toHex().chunked(2).joinToString(":")

        val HumlaException.isWrongPassword: Boolean
            get() = reason == HumlaException.HumlaDisconnectReason.REJECT &&
                reject?.type in setOf(Mumble.Reject.RejectType.WrongUserPW, Mumble.Reject.RejectType.WrongServerPW)
    }
}
