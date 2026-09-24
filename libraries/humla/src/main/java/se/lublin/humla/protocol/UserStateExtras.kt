/*
 * Copyright (C) 2026 The Mumla authors
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

package se.lublin.humla.protocol

import se.lublin.humla.model.LocalVolumes
import se.lublin.humla.model.User
import se.lublin.humla.protobuf.Mumble

/** Looks up [user]'s local volume again when it appears or its identity changes. */
internal fun applyLocalVolume(user: User, msg: Mumble.UserState, isNew: Boolean, localVolumes: LocalVolumes) {
    if (isNew || msg.hasHash() || msg.hasName()) user.localVolume = localVolumes.volumeFor(user)
}

/** The channels [user] starts or stops listening to; false if the frame changes none. */
internal fun applyListening(user: User, msg: Mumble.UserState, channels: ChannelTree): Boolean {
    var changed = false
    for (id in msg.listeningChannelAddList) {
        val channel = channels[id] ?: continue
        channel.addListener(user)
        changed = true
    }
    for (id in msg.listeningChannelRemoveList) {
        changed = channels[id]?.removeListener(user) == true || changed
    }
    return changed
}
