package se.lublin.mumla.channel

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.mumla.testing.serverState

class ChannelSearchProviderTest {
    private val provider = ChannelSearchProvider()
    private val model = serverState {
        channel(0, "Root")
        channel(1, "Lounge")
        channel(2, "Slow lane", parent = 1)
        user(1, "Lotta")
        user(2, "Bob", channel = 1)
        user(3, "Carlo", channel = 2)
    }

    @Test
    fun `channels and users anywhere in the tree match by name, ignoring case`() {
        assertThat(provider.channelsMatching(model, "lo").map { it.id }).containsExactly(1, 2).inOrder()
        assertThat(provider.usersMatching(model, "lo").map { it.session }).containsExactly(1, 3).inOrder()
    }

    @Test
    fun `nothing matches without a tree`() {
        val empty = serverState {}

        assertThat(provider.channelsMatching(empty, "")).isEmpty()
        assertThat(provider.usersMatching(empty, "")).isEmpty()
    }
}
