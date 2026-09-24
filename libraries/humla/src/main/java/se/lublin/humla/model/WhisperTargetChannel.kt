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

package se.lublin.humla.model

import se.lublin.humla.protobuf.Mumble

/** A whisper to a channel, optionally with its linked channels, subchannels, or only one group. */
class WhisperTargetChannel(
    private val channel: IChannel,
    private val includeLinked: Boolean,
    private val includeSubchannels: Boolean,
    private val groupRestriction: String?,
) : WhisperTarget {
    override val name: String?
        get() = channel.name

    override fun createTarget(): Mumble.VoiceTarget.Target =
        Mumble.VoiceTarget.Target.newBuilder().apply {
            links = includeLinked
            children = includeSubchannels
            groupRestriction?.let { group = it }
            channelId = channel.id
        }.build()
}
