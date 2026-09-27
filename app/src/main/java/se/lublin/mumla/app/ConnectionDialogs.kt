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

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.DialogFragment
import se.lublin.humla.model.Server
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.RejectType
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.app.ConnectionErrorDialogFragment.Action
import se.lublin.mumla.app.ConnectionErrorDialogFragment.Kind
import se.lublin.mumla.chat.NoticeFormatter
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.ui.MessageDialogFragment
import se.lublin.mumla.util.MumlaTrustStore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.cert.X509Certificate

/**
 * The dialogs that tell the user how a connection goes, as fragments of [activity] so that they
 * survive configuration changes. Create it in `onCreate`: it registers for the dialogs' results.
 * Main thread only.
 */
class ConnectionDialogs(
    private val activity: AppCompatActivity,
    private val settings: Settings,
    private val listener: Listener,
    private val sessions: SessionManager,
) {
    interface Listener {
        /** Connects to [server] again, e.g. after its certificate was trusted. */
        fun reconnect(server: Server)

        /** Connects to [server] again with the password the user just entered. */
        fun reconnectWithPassword(server: Server)
    }

    private val fragments get() = activity.supportFragmentManager

    private val torSuffix get() = if (settings.isTorEnabled) " (Tor)" else ""

    private val notices = NoticeFormatter(activity)

    init {
        fragments.setFragmentResultListener(ConnectingDialogFragment.REQUEST_CANCELLED, activity) { _, _ ->
            sessions.disconnect()
            Toast.makeText(activity, R.string.cancelled, Toast.LENGTH_SHORT).show()
        }
        fragments.setFragmentResultListener(ConnectionErrorDialogFragment.REQUEST_KEY, activity) { _, result ->
            onErrorAction(
                Action.valueOf(requireNotNull(result.getString(ConnectionErrorDialogFragment.RESULT_ACTION))),
                result.getString(ConnectionErrorDialogFragment.RESULT_PASSWORD).orEmpty(),
            )
        }
        fragments.setFragmentResultListener(CertificateTrustDialogFragment.REQUEST_KEY, activity) { _, result ->
            val (server, certificate) = CertificateTrustDialogFragment.parseResult(result)
            trustAndReconnect(server, certificate)
        }
    }

    /** Whether the connecting or error dialog is up. */
    val isShowing: Boolean
        get() = fragments.findFragmentByTag(TAG_CONNECTING) != null || fragments.findFragmentByTag(TAG_ERROR) != null

    /**
     * Shows the connecting, error or certificate dialog the session's state calls for, and
     * dismisses the others. A dialog that is up already and would look the same stays, e.g. one
     * restored after rotation.
     */
    fun update() {
        if (fragments.isStateSaved) return
        val errorShown = sessions.errorShown.value
        when (val state = sessions.currentState) {
            SessionState.Connecting, is SessionState.Reconnecting -> {
                dismiss(TAG_ERROR)
                if (fragments.findFragmentByTag(TAG_CONNECTING) == null) {
                    // The port is left out: the SRV lookup that may change it comes later.
                    val host = sessions.session.value?.targetServer?.host
                    val title = activity.getString(R.string.connecting_to_server, host) + torSuffix
                    ConnectingDialogFragment.newInstance(title).showNow(fragments, TAG_CONNECTING)
                }
            }
            is SessionState.ConnectionLost -> {
                dismiss(TAG_CONNECTING)
                if (errorShown) dismiss(TAG_ERROR) else showError(state.reason, reconnecting = true)
            }
            is SessionState.Disconnected -> {
                dismiss(TAG_CONNECTING)
                val reason = state.reason
                when {
                    reason == null || errorShown -> dismiss(TAG_ERROR)
                    reason is DisconnectReason.TlsUntrusted -> offerTrust(reason.chain, changed = false)
                    reason is DisconnectReason.TlsCertificateChanged -> offerTrust(reason.chain, changed = true)
                    else -> showError(reason, reconnecting = false)
                }
            }
            SessionState.Connected -> {
                dismiss(TAG_CONNECTING)
                dismiss(TAG_ERROR)
            }
        }
    }

    private fun dismiss(tag: String) {
        (fragments.findFragmentByTag(tag) as? DialogFragment)?.dismissNow()
    }

    private fun showError(reason: DisconnectReason?, reconnecting: Boolean) {
        val message = reason?.let(notices::disconnectReason)
        val title = activity.getString(R.string.connectionRefused) + torSuffix
        val dialog = when {
            message != null && reconnecting -> ConnectionErrorDialogFragment.newInstance(
                Kind.RECONNECTING,
                title,
                message + "\n\n" + activity.getString(
                    R.string.attempting_reconnect,
                    (reason as? DisconnectReason.Network)?.cause?.message ?: "unknown",
                ),
            )
            reason.isWrongPassword -> ConnectionErrorDialogFragment.newInstance(
                Kind.WRONG_PASSWORD,
                activity.getString(R.string.invalid_password),
                message.orEmpty(),
            )
            else -> ConnectionErrorDialogFragment.newInstance(
                Kind.OTHER,
                title,
                message ?: activity.getString(R.string.unknown),
            )
        }
        val shown = fragments.findFragmentByTag(TAG_ERROR) as? ConnectionErrorDialogFragment
        if (shown?.content == dialog.content) return
        shown?.dismissNow()
        dialog.showNow(fragments, TAG_ERROR)
    }

    private fun onErrorAction(action: Action, password: String) {
        when (action) {
            Action.CANCEL_RECONNECT -> sessions.cancelReconnect()
            Action.RECONNECT_WITH_PASSWORD -> {
                val server = sessions.session.value?.targetServer ?: return
                listener.reconnectWithPassword(server.copy(password = password))
            }
            Action.ACKNOWLEDGE -> sessions.markErrorShown()
        }
    }

    /** Offers once to trust the server's certificate, which is unknown or, if [changed], not the pinned one. */
    private fun offerTrust(chain: List<X509Certificate>, changed: Boolean) {
        dismiss(TAG_ERROR)
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
        Toast.makeText(activity, R.string.trust_added, Toast.LENGTH_LONG).show()
        listener.reconnect(server)
    }

    private fun onTrustFailed(e: Exception) {
        Log.w(TAG, "Could not trust the certificate", e)
        Toast.makeText(activity, R.string.trust_add_failed, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val TAG = "ConnectionDialogs"
        const val TAG_CONNECTING = "connecting"
        const val TAG_ERROR = "connection_error"
        const val TAG_CERTIFICATE = "certificate"
        const val TAG_PERMISSION_DENIED = "permission_denied"

        val DisconnectReason?.isWrongPassword: Boolean
            get() = this is DisconnectReason.Rejected &&
                type in setOf(RejectType.WRONG_USER_PASSWORD, RejectType.WRONG_SERVER_PASSWORD)
    }
}
