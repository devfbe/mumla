package se.lublin.humla.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test

class WhisperTargetListTest {
    private val root = ChannelState(0)
    private fun target() = WhisperTargetChannel(root, false, false, null)

    @Test
    fun `fills 30 slots, frees and reuses them`() {
        val list = WhisperTargetList()
        assertThat(list[1]).isNull()

        val targets = List(30) { target() }
        for (i in 0 until 30) {
            val id = list.append(targets[i])
            assertThat(id).isEqualTo((i + 1).toByte())
            assertThat(list[id]).isSameInstanceAs(targets[i])
        }
        assertWithMessage("full").that(list.append(targets[0])).isEqualTo((-1).toByte())

        list.free(5)
        assertThat(list[5]).isNull()
        val replacement = target()
        assertThat(list.append(replacement)).isEqualTo(5.toByte())
        assertThat(list[5]).isSameInstanceAs(replacement)

        list.clear()
        assertThat(list.append(target())).isEqualTo(1.toByte())
    }

    @Test
    fun `rejects slots outside 1 to 30`() {
        val list = WhisperTargetList()
        assertThrows(IndexOutOfBoundsException::class.java) { list[-1] }
        assertThrows(IndexOutOfBoundsException::class.java) { list[31] }
        assertThrows(IndexOutOfBoundsException::class.java) { list.free(-1) }
        assertThrows(IndexOutOfBoundsException::class.java) { list.free(31) }
    }
}
