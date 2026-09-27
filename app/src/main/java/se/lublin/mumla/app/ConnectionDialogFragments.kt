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
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.model.Server
import se.lublin.humla.util.HumlaLog
import se.lublin.mumla.R
import se.lublin.mumla.databinding.CertificateInfoBinding
import se.lublin.mumla.util.getServer
import se.lublin.mumla.util.putServer
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
private const val ARG_INPUT = "input"
private const val ARG_SERVER = "server"
private const val ARG_CERTIFICATE = "certificate"
private const val ARG_CHANGED = "changed"

/**
 * Why a connection to a server failed, with a field for the name or password if changing it may
 * help. Its buttons report [REQUEST_KEY] with an [Action] and the server to retry, as entered.
 */
class ConnectionErrorDialogFragment : DialogFragment() {
    enum class Action { RETRY, EDIT, CLOSE }

    init {
        isCancelable = false
    }

    private val input get() = FailureInput.valueOf(requireNotNull(requireArguments().getString(ARG_INPUT)))

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val field = inputField(input)
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(args.getString(ARG_TITLE))
            .setMessage(args.getString(ARG_MESSAGE))
            .setPositiveButton(R.string.retry) { _, _ -> report(Action.RETRY, field?.text?.toString()) }
            .setNeutralButton(R.string.edit_server) { _, _ -> report(Action.EDIT, null) }
            .setNegativeButton(R.string.close) { _, _ -> report(Action.CLOSE, null) }
        if (field != null) {
            val padding = resources.getDimensionPixelSize(R.dimen.padding_medium)
            builder.setView(FrameLayout(requireContext()).apply {
                setPadding(padding, 0, padding, 0)
                addView(field)
            })
        }
        return builder.create()
    }

    private fun inputField(input: FailureInput): EditText? {
        val (type, hint) = when (input) {
            FailureInput.NONE -> return null
            FailureInput.USERNAME -> InputType.TYPE_CLASS_TEXT to R.string.server_username
            FailureInput.PASSWORD ->
                (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) to R.string.password
        }
        return EditText(requireContext()).apply {
            id = R.id.connection_input
            inputType = type
            setHint(hint)
        }
    }

    /** Reports [action] for the server, with the name or password changed to [entered] if there is a field. */
    private fun report(action: Action, entered: String?) {
        val server = requireNotNull(requireArguments().getServer(ARG_SERVER))
        val retried = when {
            entered == null -> server
            input == FailureInput.USERNAME -> server.copy(username = entered.trim().ifEmpty { server.username })
            else -> server.copy(password = entered)
        }
        setFragmentResult(
            REQUEST_KEY,
            bundleOf(RESULT_ACTION to action.name, ARG_INPUT to input.name).apply { putServer(ARG_SERVER, retried) },
        )
    }

    /** What the dialog shows, to tell whether a new one would differ. */
    val content: String
        get() = listOf(ARG_INPUT, ARG_TITLE, ARG_MESSAGE).joinToString("|") { arguments?.getString(it).orEmpty() }

    /** The choice a [REQUEST_KEY] result reports: [action] on [server], whose [input] the user may have changed. */
    data class Result(val action: Action, val server: Server, val input: FailureInput) {
        companion object {
            fun from(bundle: Bundle) = Result(
                Action.valueOf(requireNotNull(bundle.getString(RESULT_ACTION))),
                requireNotNull(bundle.getServer(ARG_SERVER)),
                FailureInput.valueOf(requireNotNull(bundle.getString(ARG_INPUT))),
            )
        }
    }

    companion object {
        const val REQUEST_KEY = "connection_error"
        private const val RESULT_ACTION = "action"

        fun newInstance(title: String, message: String, input: FailureInput, server: Server) =
            ConnectionErrorDialogFragment().apply {
                arguments = bundleOf(ARG_TITLE to title, ARG_MESSAGE to message, ARG_INPUT to input.name)
                    .apply { putServer(ARG_SERVER, server) }
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
        val server = requireNotNull(args.getServer(ARG_SERVER))
        val encoded = requireNotNull(args.getByteArray(ARG_CERTIFICATE))
        val accept = DialogInterface.OnClickListener { _, _ ->
            setFragmentResult(
                REQUEST_KEY,
                bundleOf(ARG_CERTIFICATE to encoded).apply { putServer(ARG_SERVER, server) },
            )
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
            HumlaLog.w(TAG, "Could not read the certificate", e)
            getString(R.string.unknown)
        }
    }.root

    companion object {
        private const val TAG = "CertificateTrustDialog"
        const val REQUEST_KEY = "certificate_trusted"

        fun newInstance(server: Server, certificate: X509Certificate, changed: Boolean) =
            CertificateTrustDialogFragment().apply {
                arguments = bundleOf(
                    ARG_CERTIFICATE to certificate.encoded,
                    ARG_CHANGED to changed,
                ).apply { putServer(ARG_SERVER, server) }
            }

        /** The server and certificate of a [REQUEST_KEY] result. */
        fun parseResult(result: Bundle): Pair<Server, X509Certificate> = Pair(
            requireNotNull(result.getServer(ARG_SERVER)),
            decodeCertificate(requireNotNull(result.getByteArray(ARG_CERTIFICATE))),
        )

        private fun decodeCertificate(encoded: ByteArray): X509Certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(encoded)) as X509Certificate

        /** The [algorithm] digest of [data] as colon-separated hex, e.g. "ab:cd:...". */
        private fun fingerprint(algorithm: String, data: ByteArray): String =
            MessageDigest.getInstance(algorithm).digest(data).toHex().chunked(2).joinToString(":")
    }
}
