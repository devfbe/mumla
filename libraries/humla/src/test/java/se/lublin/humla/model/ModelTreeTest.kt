package se.lublin.humla.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ModelTreeTest {
    @Test
    fun `user moves update direct and recursive counts`() {
        val root = Channel(0, false)
        val user = User(0, "Test user")
        user.channel = root
        assertThat(root.users).hasSize(1)
        assertThat(root.subchannelUserCount).isEqualTo(1)

        val sub = Channel(1, false)
        root.addSubchannel(sub)
        User(1, "Test user in subchannel").channel = sub
        assertThat(root.users).hasSize(1)
        assertThat(root.subchannelUserCount).isEqualTo(2)

        user.channel = sub
        assertThat(root.users).isEmpty()
        assertThat(root.subchannelUserCount).isEqualTo(2)
        assertThat(sub.users).hasSize(2)
    }
}
