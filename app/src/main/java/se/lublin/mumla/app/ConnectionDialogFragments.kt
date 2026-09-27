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

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.widget.EditText
import androidx.core.os.BundleCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.databinding.CertificateInfoBinding
import se.lublin.mumla.util.toHex
import java.io.ByteArrayInputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/*
 * The dialogs of [ConnectionDialogs]. They report the user's choice as a fragment result, which
 * the activity acts on, so that a choice made after a configuration change still arrives.
 */

private const val ARG_TITLE = "title"
private const val ARG_MESSAGE = "message"
private const val ARG_KIND = "kind"
private const val ARG_SERVER = "server"
private const val ARG_CERTIFICATE = "certificate"
private const val ARG_CHANGED = "changed"

/** A progress dialog while connecting; cancelling it reports [REQUEST_CANCELLED]. */
class ConnectingDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(requireArguments().getString(ARG_TITLE))
            .setView(R.layout.dialog_progress)
            .create()

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        setFragmentResult(REQUEST_CANCELLED, Bundle.EMPTY)
    }

    companion object {
        const val REQUEST_CANCELLED = "connecting_cancelled"

        fun newInstance(title: String) = ConnectingDialogFragment().apply { arguments = bundleOf(ARG_TITLE to title) }
    }
}

/** Why a connection failed; its button reports [REQUEST_KEY] with one of the [Action]s. */
class ConnectionErrorDialogFragment : DialogFragment() {
    enum class Kind { RECONNECTING, WRONG_PASSWORD, OTHER }

    enum class Action { CANCEL_RECONNECT, RECONNECT_WITH_PASSWORD, ACKNOWLEDGE }

    init {
        isCancelable = false
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(args.getString(ARG_TITLE))
            .setMessage(args.getString(ARG_MESSAGE))
        when (Kind.valueOf(requireNotNull(args.getString(ARG_KIND)))) {
            Kind.RECONNECTING -> builder.setPositiveButton(R.string.cancel_reconnect) { _, _ ->
                report(Action.CANCEL_RECONNECT)
            }
            Kind.WRONG_PASSWORD -> {
                val passwordField = EditText(requireContext()).apply {
                    id = R.id.connection_password
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setHint(R.string.password)
                }
                builder.setView(passwordField)
                    .setPositiveButton(R.string.reconnect) { _, _ ->
                        report(Action.RECONNECT_WITH_PASSWORD, passwordField.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> report(Action.ACKNOWLEDGE) }
            }
            Kind.OTHER -> builder.setPositiveButton(android.R.string.ok) { _, _ -> report(Action.ACKNOWLEDGE) }
        }
        return builder.create()
    }

    private fun report(action: Action, password: String? = null) {
        setFragmentResult(REQUEST_KEY, bundleOf(RESULT_ACTION to action.name, RESULT_PASSWORD to password))
    }

    /** What the dialog shows, to tell whether a new one would differ. */
    val content: String
        get() = listOf(ARG_KIND, ARG_TITLE, ARG_MESSAGE).joinToString("|") { arguments?.getString(it).orEmpty() }

    companion object {
        const val REQUEST_KEY = "connection_error"
        const val RESULT_ACTION = "action"
        const val RESULT_PASSWORD = "password"

        fun newInstance(kind: Kind, title: String, message: String) = ConnectionErrorDialogFragment().apply {
            arguments = bundleOf(ARG_KIND to kind.name, ARG_TITLE to title, ARG_MESSAGE to message)
        }
    }
}

/**
 * Offers to trust a server's unknown certificate or, if it [changed], warns that it is not the one
 * trusted before. Accepting reports [REQUEST_KEY] with the server and the certificate.
 */
class CertificateTrustDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val server = requireNotNull(BundleCompat.getParcelable(args, ARG_SERVER, Server::class.java))
        val encoded = requireNotNull(args.getByteArray(ARG_CERTIFICATE))
        val accept = DialogInterface.OnClickListener { _, _ ->
            setFragmentResult(REQUEST_KEY, bundleOf(ARG_SERVER to server, ARG_CERTIFICATE to encoded))
        }
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(certificateInfoView(encoded))
        return if (args.getBoolean(ARG_CHANGED)) {
            // Cancel is the positive, default-looking choice: replacing the pin is what an attacker wants.
            builder.setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.certificate_changed_title)
                .setMessage(getString(R.string.certificate_changed_message, server.host))
                .setPositiveButton(android.R.string.cancel, null)
                .setNegativeButton(R.string.certificate_changed_accept, accept)
                .create()
        } else {
            builder.setTitle(R.string.untrusted_certificate)
                .setPositiveButton(R.string.allow, accept)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        }
    }

    private fun certificateInfoView(encoded: ByteArray) = CertificateInfoBinding.inflate(layoutInflater).also {
        it.certificateInfoText.text = try {
            val certificate = decodeCertificate(encoded)
            getString(
                R.string.certificate_info,
                certificate.subjectX500Principal.name,
                certificate.notBefore.toString(),
                certificate.notAfter.toString(),
                fingerprint("SHA-1", encoded),
                fingerprint("SHA-256", encoded),
            )
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Could not read the certificate", e)
            getString(R.string.unknown)
        }
    }.root

    companion object {
        private const val TAG = "CertificateTrustDialog"
        const val REQUEST_KEY = "certificate_trusted"

        fun newInstance(server: Server, certificate: X509Certificate, changed: Boolean) =
            CertificateTrustDialogFragment().apply {
                arguments = bundleOf(
                    ARG_SERVER to server,
                    ARG_CERTIFICATE to certificate.encoded,
                    ARG_CHANGED to changed,
                )
            }

        /** The server and certificate of a [REQUEST_KEY] result. */
        fun parseResult(result: Bundle): Pair<Server, X509Certificate> = Pair(
            requireNotNull(BundleCompat.getParcelable(result, ARG_SERVER, Server::class.java)),
            decodeCertificate(requireNotNull(result.getByteArray(ARG_CERTIFICATE))),
        )

        private fun decodeCertificate(encoded: ByteArray): X509Certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate

        /** The [algorithm] digest of [data] as colon-separated hex, e.g. "ab:cd:...". */
        private fun fingerprint(algorithm: String, data: ByteArray): String =
            MessageDigest.getInstance(algorithm).digest(data).toHex().chunked(2).joinToString(":")
    }
}
