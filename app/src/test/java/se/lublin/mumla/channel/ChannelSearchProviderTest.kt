package se.lublin.mumla.channel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelSearchProviderTest {
    private val provider = ChannelSearchProvider()
    private val root = FakeChannel(0, "Root").apply {
        addUser(FakeUser(1, "Lotta"))
        addSubchannel(FakeChannel(1, "Lounge")).apply {
            addUser(FakeUser(2, "Bob"))
            addSubchannel(FakeChannel(2, "Slow lane")).addUser(FakeUser(3, "Carlo"))
        }
    }

    @Test
    fun `channels and users anywhere in the tree match by name, ignoring case`() {
        assertThat(provider.channelsMatching(root, "lo").map { it.id }).containsExactly(1, 2).inOrder()
        assertThat(provider.usersMatching(root, "lo").map { it.session }).containsExactly(1, 3).inOrder()
    }

    @Test
    fun `nothing matches without a tree`() {
        assertThat(provider.channelsMatching(null, "")).isEmpty()
        assertThat(provider.usersMatching(null, "")).isEmpty()
    }
}
