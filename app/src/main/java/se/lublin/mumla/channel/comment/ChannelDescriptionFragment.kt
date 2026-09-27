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

package se.lublin.mumla.channel.comment

import androidx.core.os.bundleOf
import se.lublin.humla.IHumlaService
import se.lublin.humla.session.HumlaEvent

class ChannelDescriptionFragment : AbstractCommentFragment() {

    private val channelId: Int get() = requireArguments().getInt(ARG_CHANNEL)

    override fun requestComment(service: IHumlaService) {
        if (!service.isConnected) return
        observeComment(service) { event ->
            (event as? HumlaEvent.ChannelStateUpdated)?.channel?.takeIf { it.id == channelId }?.description
        }
        service.session.requestChannelDescription(channelId)
    }

    override fun editComment(service: IHumlaService, comment: String) {
        // Channel descriptions cannot be edited here yet.
    }

    companion object {
        private const val ARG_CHANNEL = "channel"

        /** The description of [channelId]; [description] is null until it is fetched. */
        fun newInstance(channelId: Int, description: String?) = ChannelDescriptionFragment().apply {
            arguments = bundleOf(ARG_CHANNEL to channelId, ARG_COMMENT to description, ARG_EDITING to false)
        }
    }
}
