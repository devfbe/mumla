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

package se.lublin.mumla.channel

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import se.lublin.humla.model.ChannelState
import se.lublin.mumla.R
import se.lublin.mumla.databinding.DialogChannelMoveBinding
import se.lublin.mumla.util.dp
import kotlin.math.roundToInt

private const val ROW_START_DP = 24f
private const val INDENT_DP = 16f

/** A channel the move dialog offers, [depth] levels below the root. */
internal data class MoveTarget(val id: Int, val name: String, val depth: Int)

/**
 * [channels], given in tree order (each parent before its subchannels), with their depth; only
 * those whose name contains [query], ignoring case, unless it is blank.
 */
internal fun moveTargets(channels: List<ChannelState>, query: String): List<MoveTarget> {
    val depths = HashMap<Int, Int>()
    val wanted = query.trim()
    return channels.mapNotNull { channel ->
        val depth = channel.parent?.let(depths::get)?.plus(1) ?: 0
        depths[channel.id] = depth
        val name = channel.name.orEmpty()
        MoveTarget(channel.id, name, depth).takeIf { name.contains(wanted, ignoreCase = true) }
    }
}

/** Asks where to move a user: the channel tree, indented, under a search field. */
internal fun showChannelMoveDialog(context: Context, channels: List<ChannelState>, onPicked: (Int) -> Unit) {
    val binding = DialogChannelMoveBinding.inflate(LayoutInflater.from(context))
    var dialog: AlertDialog? = null
    val adapter = MoveTargetAdapter { channel ->
        dialog?.dismiss()
        onPicked(channel)
    }
    binding.channelMoveList.adapter = adapter
    adapter.submitList(moveTargets(channels, ""))
    binding.channelMoveSearch.doAfterTextChanged { adapter.submitList(moveTargets(channels, it?.toString().orEmpty())) }
    dialog = MaterialAlertDialogBuilder(context)
        .setTitle(R.string.user_menu_move)
        .setView(binding.root)
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private class MoveTargetAdapter(private val onClick: (Int) -> Unit) :
    ListAdapter<MoveTarget, MoveTargetAdapter.Holder>(Diff) {

    class Holder(val view: TextView) : RecyclerView.ViewHolder(view)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.channel_move_row, parent, false) as TextView)

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val target = getItem(position)
        val view = holder.view
        val resources = view.resources
        val start = resources.dp(ROW_START_DP + INDENT_DP * target.depth).roundToInt()
        view.setPaddingRelative(start, view.paddingTop, view.paddingEnd, view.paddingBottom)
        view.text = target.name
        // The indentation shows the tree to the eye only.
        view.contentDescription = view.context.getString(R.string.a11y_channel_level, target.name, target.depth)
        view.setOnClickListener { onClick(target.id) }
    }

    private object Diff : DiffUtil.ItemCallback<MoveTarget>() {
        override fun areItemsTheSame(oldItem: MoveTarget, newItem: MoveTarget) = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: MoveTarget, newItem: MoveTarget) = oldItem == newItem
    }
}
