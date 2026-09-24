package se.lublin.mumla.db

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class PinnedChannelsTest {
    private val stored = mutableListOf(3, 1)
    private val database = mockk<MumlaDatabase>(relaxed = true) {
        every { getPinnedChannels(SERVER) } answers { stored.toList() }
        every { addPinnedChannel(SERVER, any()) } answers { stored += secondArg<Int>() }
        every { removePinnedChannel(SERVER, any()) } answers { stored -= secondArg<Int>() }
    }
    private val pins = MumlaRepository(database, Dispatchers.Unconfined).pinnedChannels

    @Test
    fun `the pins are read once and kept in pinning order`() {
        assertThat(pins.of(SERVER).value).containsExactly(3, 1).inOrder()
        assertThat(pins.isPinned(SERVER, 1)).isTrue()
        assertThat(pins.isPinned(SERVER, 2)).isFalse()

        verify(exactly = 1) { database.getPinnedChannels(SERVER) }
    }

    @Test
    fun `pinning and unpinning show at once and reach the database`() {
        pins.setPinned(SERVER, 2, pinned = true)
        assertThat(pins.of(SERVER).value).containsExactly(3, 1, 2).inOrder()

        pins.setPinned(SERVER, 3, pinned = false)
        assertThat(pins.of(SERVER).value).containsExactly(1, 2).inOrder()

        assertThat(stored).containsExactly(1, 2).inOrder()
    }

    @Test
    fun `whenLoaded answers at once once the pins are read`() {
        var seen: Set<Int>? = null
        pins.whenLoaded(SERVER) { seen = it }

        assertThat(seen).containsExactly(3, 1).inOrder()
    }

    private companion object {
        const val SERVER = 5L
    }
}
