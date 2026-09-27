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

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import se.lublin.humla.model.Server
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.SessionState
import se.lublin.humla.util.HumlaLog
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.app.ConnectionErrorDialogFragment.Action
import se.lublin.mumla.servers.ServerEditFragment
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.ui.MessageDialogFragment
import se.lublin.mumla.ui.showSnackbar
import se.lublin.mumla.util.MumlaTrustStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate

/**
 * The dialogs that tell the user why a connection failed, as fragments of [activity] so that they
 * survive configuration changes. Create it in `onCreate`: it registers for the dialogs' results.
 * Progress is not a dialog; [ConnectionBanner] shows it. Main thread only.
 */
class ConnectionDialogs(
    private val activity: AppCompatActivity,
    private val settings: Settings,
    private val listener: Listener,
    private val sessions: SessionManager,
) {
    interface Listener {
        /** Connects to [server] again, e.g. after its certificate was trusted or with another name. */
        fun reconnect(server: Server)

        /** Connects to [server] again with the password the user just entered. */
        fun reconnectWithPassword(server: Server)
    }

    private val fragments get() = activity.supportFragmentManager

    private val torSuffix get() = if (settings.isTorEnabled) " (Tor)" else ""

    init {
        fragments.setFragmentResultListener(ConnectionErrorDialogFragment.REQUEST_KEY, activity) { _, result ->
            onErrorAction(ConnectionErrorDialogFragment.Result.from(result))
        }
        fragments.setFragmentResultListener(CertificateTrustDialogFragment.REQUEST_KEY, activity) { _, result ->
            val (server, certificate) = CertificateTrustDialogFragment.parseResult(result)
            trustAndReconnect(server, certificate)
        }
    }

    /** Whether the error dialog is up. */
    val isShowing: Boolean
        get() = fragments.findFragmentByTag(TAG_ERROR) != null

    /**
     * Shows the error or certificate dialog the session's state calls for, or dismisses the error
     * dialog. One that is up already and would look the same stays, e.g. one restored after rotation.
     */
    fun update() {
        if (fragments.isStateSaved) return
        val reason = (sessions.currentState as? SessionState.Disconnected)?.reason
        when {
            reason == null || sessions.errorShown.value -> dismissError()
            reason is DisconnectReason.TlsUntrusted -> offerTrust(reason.chain, changed = false)
            reason is DisconnectReason.TlsCertificateChanged -> offerTrust(reason.chain, changed = true)
            else -> showError(reason)
        }
    }

    private fun dismissError() {
        (fragments.findFragmentByTag(TAG_ERROR) as? DialogFragment)?.dismissNow()
    }

    private fun showError(reason: DisconnectReason) {
        val server = sessions.session.value?.targetServer ?: return
        val (failure, detail) = connectionFailureUi(reason)
        val message = listOfNotNull(activity.getString(failure.message), detail).joinToString("\n\n")
        val dialog = ConnectionErrorDialogFragment.newInstance(
            activity.getString(failure.title) + torSuffix,
            message,
            failure.input,
            server,
        )
        val shown = fragments.findFragmentByTag(TAG_ERROR) as? ConnectionErrorDialogFragment
        if (shown?.content == dialog.content) return
        shown?.dismissNow()
        dialog.showNow(fragments, TAG_ERROR)
    }

    private fun onErrorAction(result: ConnectionErrorDialogFragment.Result) {
        sessions.markErrorShown()
        val server = result.server
        when (result.action) {
            Action.RETRY ->
                if (result.input == FailureInput.PASSWORD) {
                    listener.reconnectWithPassword(server)
                } else {
                    listener.reconnect(server)
                }
            Action.EDIT -> editServer(server)
            Action.CLOSE -> Unit
        }
    }

    /** Opens the editor for [server]: a saved one is saved again, any other connected to. */
    private fun editServer(server: Server) {
        if (fragments.isStateSaved) return
        val mode = if (server.isSaved) ServerEditFragment.Mode.EDIT else ServerEditFragment.Mode.CONNECT
        ServerEditFragment.newInstance(server, mode).show(fragments, TAG_EDIT)
    }

    /** Offers once to trust the server's certificate, which is unknown or, if [changed], not the pinned one. */
    private fun offerTrust(chain: List<X509Certificate>, changed: Boolean) {
        dismissError()
        sessions.markErrorShown()
        val server = sessions.session.value?.targetServer ?: return
        val certificate = chain.firstOrNull() ?: return
        showUntrustedCertificate(server, certificate, changed)
    }

    /** Offers to trust the [certificate] of [server], which is unknown or, if [changed], not the pinned one. */
    private fun showUntrustedCertificate(server: Server, certificate: X509Certificate, changed: Boolean) {
        if (fragments.isStateSaved) return
        val dialog = try {
            CertificateTrustDialogFragment.newInstance(server, certificate, changed)
        } catch (e: GeneralSecurityException) {
            onTrustFailed(e)
            return
        }
        dialog.show(fragments, TAG_CERTIFICATE)
    }

    fun showPermissionDenied(reason: String) {
        if (fragments.isStateSaved) return
        MessageDialogFragment.newInstance(activity.getString(R.string.perm_denied), reason)
            .show(fragments, TAG_PERMISSION_DENIED)
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
        activity.showSnackbar(R.string.trust_added)
        listener.reconnect(server)
    }

    private fun onTrustFailed(e: Exception) {
        HumlaLog.w(TAG, "Could not trust the certificate", e)
        activity.showSnackbar(R.string.trust_add_failed)
    }

    private companion object {
        const val TAG = "ConnectionDialogs"
        const val TAG_ERROR = "connection_error"
        const val TAG_CERTIFICATE = "certificate"
        const val TAG_EDIT = "server_edit"
        const val TAG_PERMISSION_DENIED = "permission_denied"
    }
}
