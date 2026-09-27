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

import com.google.protobuf.MessageLite
import se.lublin.humla.model.ServerState
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.session.HumlaEvent

fun channelFrame(
    id: Int,
    parent: Int? = null,
    name: String? = null,
    more: Mumble.ChannelState.Builder.() -> Unit = {},
): Mumble.ChannelState = Mumble.ChannelState.newBuilder().setChannelId(id).apply {
    if (parent != null) setParent(parent)
    if (name != null) setName(name)
    more()
}.build()

fun linksFrame(id: Int, vararg linked: Int): Mumble.ChannelState =
    Mumble.ChannelState.newBuilder().setChannelId(id).addAllLinks(linked.toList()).build()

fun userFrame(session: Int, build: Mumble.UserState.Builder.() -> Unit = {}): Mumble.UserState =
    Mumble.UserState.newBuilder().setSession(session).apply(build).build()

fun userRemoveFrame(session: Int, actor: Int = 0, reason: String = "", ban: Boolean = false): Mumble.UserRemove =
    Mumble.UserRemove.newBuilder().setSession(session).setActor(actor).setReason(reason).setBan(ban).build()

fun serverSync(session: Int, welcome: String = ""): Mumble.ServerSync =
    Mumble.ServerSync.newBuilder().setSession(session).setWelcomeText(welcome).build()

/** Runs frames through [ServerReducer], keeping the latest snapshot and every event. */
internal class ReducerHarness(var state: ServerState = ServerState.empty()) {
    val events = mutableListOf<HumlaEvent>()

    fun feed(vararg frames: MessageLite): ServerState {
        for (frame in frames) state = ServerReducer.reduce(state, frame, events::add)
        return state
    }

    fun local(input: LocalInput): ServerState = ServerReducer.reduce(state, input).also { state = it }

    val notices: List<HumlaEvent.Notice> get() = events.filterIsInstance<HumlaEvent.Notice>()
}

/** A synchronized server of [channels] channels, four below each, and [users] users spread over them. */
fun syncFrames(channels: Int, users: Int): List<MessageLite> = buildList {
    add(channelFrame(0, name = "Root"))
    for (id in 1 until channels) {
        add(channelFrame(id, parent = (id - 1) / 4, name = "channel-$id") { position = id % 7 })
    }
    for (session in 1..users) {
        add(userFrame(session) { setName("user-$session").setChannelId((session * 5) % channels).setHash("h$session") })
    }
    add(serverSync(1, "hi"))
}
