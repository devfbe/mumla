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

package se.lublin.mumla.channel

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState
import se.lublin.mumla.R
import se.lublin.mumla.databinding.OverlayUserRowBinding

/**
 * Displays the users in a single channel. Holds one snapshot of the users, refreshed in
 * [notifyDataSetChanged], so `getCount()` and `getItem(position)` always describe the same moment
 * even while the protocol thread changes the model.
 */
class ChannelAdapter(
    private val context: Context,
    private var channel: IChannel,
) : BaseAdapter() {

    // Copied: `Channel.users` returns an unmodifiable view of the live list.
    private var users: List<IUser?> = channel.users.toList()

    override fun getCount(): Int = users.size

    override fun getItem(position: Int): Any? = users[position]

    override fun getItemId(position: Int): Long = users[position]?.userId?.toLong() ?: -1L

    override fun notifyDataSetChanged() {
        users = channel.users.toList()
        super.notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = convertView?.let(OverlayUserRowBinding::bind)
            ?: OverlayUserRowBinding.inflate(LayoutInflater.from(context), parent, false)
        val user = getItem(position) as IUser
        binding.userRowName.text = user.name
        binding.userRowState.setImageResource(
            when {
                user.isSelfDeafened -> R.drawable.outline_circle_deafened
                user.isSelfMuted -> R.drawable.outline_circle_muted
                user.isDeafened -> R.drawable.outline_circle_server_deafened
                user.isMuted -> R.drawable.outline_circle_server_muted
                user.isSuppressed -> R.drawable.outline_circle_suppressed
                user.talkState == TalkState.TALKING -> R.drawable.outline_circle_talking_on
                else -> R.drawable.outline_circle_talking_off
            }
        )

        return binding.root
    }

    fun setChannel(channel: IChannel) {
        this.channel = channel
        notifyDataSetChanged()
    }

    fun getChannel(): IChannel = channel
}
