/*
 * Copyright (C) 2016 Andrew Comminos <andrew@comminos.com>
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

package se.lublin.mumla.channel

import android.app.Dialog
import android.os.Bundle
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.net.Permissions
import se.lublin.mumla.databinding.FragmentChannelEditBinding
import se.lublin.mumla.R
import se.lublin.mumla.ui.ServiceViewModel

/**
 * Creates a channel under the channel in argument "parent" if "adding" is set. Editing an
 * existing channel is not implemented.
 */
class ChannelEditFragment : DialogFragment() {

    private val serviceModel: ServiceViewModel by activityViewModels()

    private val isAdding get() = requireArguments().getBoolean("adding")
    private val parent get() = requireArguments().getInt("parent")

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val binding = FragmentChannelEditBinding.inflate(layoutInflater)
        val nameField = binding.channelEditName
        val descriptionField = binding.channelEditDescription
        val positionField = binding.channelEditPosition
        val temporaryBox = binding.channelEditTemporary

        // If we can only make temporary channels, remove the option.
        serviceModel.service.value?.takeIf { it.isConnected }?.session?.let { session ->
            val combined = session.permissions or (session.getChannel(parent)?.permissions ?: 0)
            val canMakeChannel = (combined and Permissions.MAKE_CHANNEL) != 0
            val canMakeTempChannel = (combined and Permissions.MAKE_TEMP_CHANNEL) != 0
            val onlyTemp = canMakeTempChannel && !canMakeChannel
            temporaryBox.isChecked = onlyTemp
            temporaryBox.isEnabled = !onlyTemp
        }

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (isAdding) R.string.channel_add else R.string.channel_edit)
            .setView(binding.root)
            .setPositiveButton(if (isAdding) R.string.add else R.string.save) { _, _ ->
                val session = serviceModel.service.value?.takeIf { it.isConnected }?.session
                if (isAdding && session != null) {
                    session.createChannel(
                        parent,
                        nameField.text.toString(),
                        descriptionField.text.toString(),
                        positionField.text.toString().toIntOrNull() ?: 0,
                        temporaryBox.isChecked,
                    )
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }
}
