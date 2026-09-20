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
import android.widget.ImageView
import android.widget.TextView
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.humla.model.TalkState
import se.lublin.mumla.R

/**
 * Simple adapter to display the users in a single channel.
 * Created by andrew on 24/11/13.
 */
class ChannelAdapter(
    private val context: Context,
    private var channel: IChannel,
) : BaseAdapter() {

    override fun getCount(): Int = channel.users.size

    override fun getItem(position: Int): Any = channel.users[position]

    override fun getItemId(position: Int): Long =
        channel.users[position]?.userId?.toLong() ?: -1L

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView
            ?: LayoutInflater.from(context).inflate(R.layout.overlay_user_row, parent, false)
        val user = getItem(position) as IUser
        v.findViewById<TextView>(R.id.user_row_name).text = user.name

        val state = v.findViewById<ImageView>(R.id.user_row_state)
        state.setImageResource(
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

        return v
    }

    fun setChannel(channel: IChannel) {
        this.channel = channel
        notifyDataSetChanged()
    }

    fun getChannel(): IChannel = channel
}
