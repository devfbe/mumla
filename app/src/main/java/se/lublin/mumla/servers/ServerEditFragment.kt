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
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.os.BundleCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.DialogServerEditBinding

/**
 * Edits a server's address and credentials. A valid entry is delivered as a fragment result
 * under [REQUEST_KEY] to the fragment manager showing the dialog; read it with [Result.from].
 */
class ServerEditFragment : DialogFragment() {

    private lateinit var binding: DialogServerEditBinding

    private val server: Server?
        get() = BundleCompat.getParcelable(requireArguments(), ARG_SERVER, Server::class.java)

    private val action: Action get() = Action.valueOf(requireArguments().getString(ARG_ACTION)!!)

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogServerEditBinding.inflate(layoutInflater)
        binding.serverEditUsername.hint = Settings.getInstance(requireContext()).defaultUsername
        server?.let { old ->
            binding.serverEditName.setText(old.name)
            binding.serverEditHost.setText(old.host)
            if (old.port != 0) binding.serverEditPort.setText(old.port.toString())
            binding.serverEditUsername.setText(old.username)
            binding.serverEditPassword.setText(old.password)
        }
        if (requireArguments().getBoolean(ARG_IGNORE_TITLE)) {
            binding.serverEditNameTitle.visibility = View.GONE
            binding.serverEditName.visibility = View.GONE
        }
        val actionName = when (action) {
            Action.ADD -> getString(R.string.add)
            Action.EDIT -> getString(android.R.string.ok)
            Action.CONNECT -> getString(R.string.connect)
        }
        return MaterialAlertDialogBuilder(requireActivity())
            .setPositiveButton(actionName, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setView(binding.root)
            .create()
    }

    override fun onStart() {
        super.onStart()
        // Replaces the positive button's listener so that an invalid entry does not dismiss.
        (requireDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            if (validate()) {
                setFragmentResult(REQUEST_KEY, Result(action, createServer()).toBundle())
                dismiss()
            }
        }
    }

    private fun createServer(): Server {
        val username = binding.serverEditUsername.text.toString().trim()
            .ifEmpty { binding.serverEditUsername.hint.toString() }
        return Server(
            id = server?.id ?: -1,
            name = binding.serverEditName.text.toString().trim(),
            host = binding.serverEditHost.text.toString().trim(),
            // 0 means "not configured"; consumers fall back to SRV lookup or the default port.
            port = binding.serverEditPort.text.toString().toIntOrNull() ?: 0,
            username = username,
            password = binding.serverEditPassword.text.toString(),
        )
    }

    /** Shows an error on the first invalid field, if any; returns whether all are valid. */
    private fun validate(): Boolean {
        val port = binding.serverEditPort.text.toString()
        when {
            binding.serverEditHost.text.isEmpty() -> binding.serverEditHost.error = getString(R.string.invalid_host)
            port.isNotEmpty() && port.toIntOrNull() !in 1..MAX_PORT ->
                binding.serverEditPort.error = getString(R.string.invalid_port_range)
            else -> return true
        }
        return false
    }

    enum class Action { CONNECT, EDIT, ADD }

    /** What the user confirmed: [server] to [action]. */
    data class Result(val action: Action, val server: Server) {
        fun toBundle() = Bundle().apply {
            putString(ARG_ACTION, action.name)
            putParcelable(ARG_SERVER, server)
        }

        companion object {
            fun from(bundle: Bundle) = Result(
                Action.valueOf(bundle.getString(ARG_ACTION)!!),
                BundleCompat.getParcelable(bundle, ARG_SERVER, Server::class.java)!!,
            )
        }
    }

    companion object {
        const val REQUEST_KEY = "server_edit"
        private const val ARG_SERVER = "server"
        private const val ARG_ACTION = "action"
        private const val ARG_IGNORE_TITLE = "ignore_title"
        private const val MAX_PORT = 65535

        /**
         * A dialog to [action] a server, prefilled from [server] if given. With [ignoreTitle] the
         * name field is hidden, as for a quick connect.
         */
        fun newInstance(server: Server?, action: Action, ignoreTitle: Boolean) = ServerEditFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ARG_SERVER, server)
                putString(ARG_ACTION, action.name)
                putBoolean(ARG_IGNORE_TITLE, ignoreTitle)
            }
        }
    }
}
