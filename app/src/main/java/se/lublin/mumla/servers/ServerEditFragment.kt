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
package se.lublin.mumla.servers

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.DialogServerEditBinding
import se.lublin.mumla.util.getServer
import se.lublin.mumla.util.putServer

/**
 * Edits a server's address and credentials for the purpose its [Mode] names. A valid entry is
 * delivered as a fragment result under [REQUEST_KEY] to the fragment manager showing the dialog;
 * read it with [Result.from].
 */
class ServerEditFragment : DialogFragment() {

    private lateinit var binding: DialogServerEditBinding

    private val server: Server?
        get() = requireArguments().getServer(ARG_SERVER)

    private val mode: Mode get() = Mode.valueOf(requireArguments().getString(ARG_MODE)!!)

    private val defaultUsername: String get() = Settings.getInstance(requireContext()).defaultUsername

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogServerEditBinding.inflate(layoutInflater)
        binding.serverEditUsernameLayout.placeholderText = defaultUsername
        server?.let { old ->
            binding.serverEditName.setText(old.name)
            binding.serverEditHost.setText(old.host)
            if (old.port != 0) binding.serverEditPort.setText(old.port.toString())
            binding.serverEditUsername.setText(old.username)
            binding.serverEditPassword.setText(old.password)
        }
        binding.serverEditNameLayout.isVisible = mode != Mode.CONNECT
        clearErrorOnEdit(binding.serverEditHost, binding.serverEditHostLayout)
        clearErrorOnEdit(binding.serverEditPort, binding.serverEditPortLayout)
        val builder = MaterialAlertDialogBuilder(requireActivity())
            .setTitle(mode.title)
            .setPositiveButton(mode.primary.label, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setView(binding.root)
        mode.secondary?.let { builder.setNeutralButton(it.label, null) }
        return builder.create()
    }

    private fun clearErrorOnEdit(field: TextInputEditText, layout: TextInputLayout) {
        field.doAfterTextChanged { layout.error = null }
    }

    override fun onStart() {
        super.onStart()
        // Replaces the buttons' listeners so that an invalid entry does not dismiss.
        val dialog = requireDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { confirm(mode.primary.action) }
        mode.secondary?.let { choice ->
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { confirm(choice.action) }
        }
    }

    private fun confirm(action: Action) {
        if (!validate()) return
        setFragmentResult(REQUEST_KEY, Result(action, createServer()).toBundle())
        dismiss()
    }

    private fun createServer(): Server {
        val username = binding.serverEditUsername.text.toString().trim().ifEmpty { defaultUsername }
        return Server(
            id = server?.id ?: Server.NOT_SAVED,
            label = binding.serverEditName.text.toString().trim(),
            host = binding.serverEditHost.text.toString().trim(),
            // 0 means "not configured"; consumers fall back to SRV lookup or the default port.
            port = binding.serverEditPort.text.toString().toIntOrNull() ?: 0,
            username = username,
            password = binding.serverEditPassword.text.toString(),
        )
    }

    /** Shows an error on each invalid field, clears the others'; returns whether all are valid. */
    private fun validate(): Boolean {
        val port = binding.serverEditPort.text.toString()
        val portValid = port.isEmpty() || port.toIntOrNull() in 1..MAX_PORT
        val hostError = R.string.invalid_host.takeIf { binding.serverEditHost.text.isNullOrBlank() }
        val portError = R.string.invalid_port_range.takeUnless { portValid }
        binding.serverEditHostLayout.error = hostError?.let(::getString)
        binding.serverEditPortLayout.error = portError?.let(::getString)
        return hostError == null && portError == null
    }

    /** What the user chose to do with the entered server. */
    enum class Action { CONNECT, EDIT, ADD, ADD_AND_CONNECT }

    /** A dialog button: its [label], and the [action] it confirms. */
    data class Choice(val action: Action, @param:StringRes val label: Int)

    /** What the dialog is for: its [title], its [primary] button and an optional [secondary] one. */
    enum class Mode(@param:StringRes val title: Int, val primary: Choice, val secondary: Choice? = null) {
        /** A new server, to save or just to connect to. */
        ADD(
            R.string.server_add,
            Choice(Action.ADD, R.string.save),
            Choice(Action.CONNECT, R.string.server_connect_only),
        ),

        /** A saved server. */
        EDIT(R.string.edit_server, Choice(Action.EDIT, R.string.save)),

        /** An unsaved server to connect to, without a label. */
        CONNECT(R.string.connect, Choice(Action.CONNECT, R.string.connect)),

        /** A server from a mumble:// link. */
        LINK(
            R.string.connect,
            Choice(Action.ADD_AND_CONNECT, R.string.server_save_and_connect),
            Choice(Action.CONNECT, R.string.server_connect_only),
        ),
    }

    /** What the user confirmed: [server] to [action]. */
    data class Result(val action: Action, val server: Server) {
        fun toBundle() = Bundle().apply {
            putString(ARG_ACTION, action.name)
            putServer(ARG_SERVER, server)
        }

        companion object {
            fun from(bundle: Bundle) = Result(
                Action.valueOf(bundle.getString(ARG_ACTION)!!),
                requireNotNull(bundle.getServer(ARG_SERVER)),
            )
        }
    }

    companion object {
        const val REQUEST_KEY = "server_edit"
        private const val ARG_SERVER = "server"
        private const val ARG_ACTION = "action"
        private const val ARG_MODE = "mode"
        private const val MAX_PORT = 65535

        /** A dialog for [mode], prefilled from [server] if given. */
        fun newInstance(server: Server?, mode: Mode) = ServerEditFragment().apply {
            arguments = Bundle().apply {
                server?.let { putServer(ARG_SERVER, it) }
                putString(ARG_MODE, mode.name)
            }
        }
    }
}
