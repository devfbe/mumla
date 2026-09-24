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
package se.lublin.humla.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.protobuf.Mumble
import se.lublin.humla.protocol.ModelHandler

class LocalVolumesTest {

    private val server = Server(-1, "test", "example.org", 64738, "me", "")

    @Test
    fun `a user is keyed by certificate hash, else by name on this server`() {
        assertThat(LocalVolumes.keyOf(User(1, "Ann").apply { hash = "abc" }, server)).isEqualTo("cert:abc")
        assertThat(LocalVolumes.keyOf(User(1, "Ann"), server)).isEqualTo("name:example.org:64738:Ann")
        assertThat(LocalVolumes.keyOf(User(1, "Ann").apply { hash = "" }, server))
            .isEqualTo("name:example.org:64738:Ann")
        assertThat(LocalVolumes.keyOf(User(1, null), server)).isNull()
        assertThat(LocalVolumes.keyOf(User(1, "Ann"), null)).isNull()
    }

    private fun userState(session: Int, build: Mumble.UserState.Builder.() -> Unit): Mumble.UserState =
        Mumble.UserState.newBuilder().setSession(session).apply(build).build()

    @Test
    fun `a remembered volume is applied when the user appears and follows identity changes`() {
        val volumes = LocalVolumes(server, mapOf("cert:abc" to 1.5f, "name:example.org:64738:Bob" to 0.5f))
        val handler = ModelHandler({}, null, null, volumes)
        handler.onMessage(Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root").build())

        handler.onMessage(userState(2) { setName("Ann").setHash("abc").setChannelId(0) })
        handler.onMessage(userState(3) { setName("Bob").setChannelId(0) })
        handler.onMessage(userState(4) { setName("Eve").setChannelId(0) })

        assertThat(handler.getUser(2)!!.localVolume).isEqualTo(1.5f)
        assertThat(handler.getUser(3)!!.localVolume).isEqualTo(0.5f)
        assertThat(handler.getUser(4)!!.localVolume).isEqualTo(1f)

        handler.onMessage(userState(3) { setName("Robert") })
        assertThat(handler.getUser(3)!!.localVolume).isEqualTo(1f)
    }

    @Test
    fun `a volume set during the session is kept for a user who comes back`() {
        val volumes = LocalVolumes(server)
        val handler = ModelHandler({}, null, null, volumes)
        handler.onMessage(Mumble.ChannelState.newBuilder().setChannelId(0).setName("Root").build())
        handler.onMessage(userState(2) { setName("Ann").setHash("abc").setChannelId(0) })

        volumes.set(handler.getUser(2)!!, 0.25f)
        assertThat(handler.getUser(2)!!.localVolume).isEqualTo(0.25f)

        handler.onMessage(Mumble.UserRemove.newBuilder().setSession(2).build())
        handler.onMessage(userState(7) { setName("Ann").setHash("abc").setChannelId(0) })
        assertThat(handler.getUser(7)!!.localVolume).isEqualTo(0.25f)
    }
}
