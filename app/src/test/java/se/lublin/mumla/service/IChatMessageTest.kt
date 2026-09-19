package se.lublin.mumla.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Message

@RunWith(RobolectricTestRunner::class)
class IChatMessageTest {

    @Test
    fun textMessageExposesUnderlyingMessage() {
        val msg = Message(7, "alice", emptyList(), emptyList(), emptyList(), "<b>hi</b>")
        val chat = IChatMessage.TextMessage(msg)
        assertThat(chat.body).isEqualTo("<b>hi</b>")
        assertThat(chat.receivedTime).isEqualTo(msg.receivedTime)
        assertThat(chat.message).isSameInstanceAs(msg)
    }

    @Test
    fun infoMessageCarriesTypeAndBody() {
        val before = System.currentTimeMillis()
        val chat = IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.WARNING, "careful")
        assertThat(chat.type).isEqualTo(IChatMessage.InfoMessage.Type.WARNING)
        assertThat(chat.body).isEqualTo("careful")
        assertThat(chat.receivedTime).isAtLeast(before)
    }

    @Test
    fun visitorDispatchesOnConcreteType() {
        val seen = mutableListOf<String>()
        val visitor = object : IChatMessage.Visitor {
            override fun visit(message: IChatMessage.TextMessage) { seen += "text" }
            override fun visit(message: IChatMessage.InfoMessage) { seen += "info" }
        }
        IChatMessage.TextMessage(Message("x")).accept(visitor)
        IChatMessage.InfoMessage(IChatMessage.InfoMessage.Type.INFO, "y").accept(visitor)
        assertThat(seen).containsExactly("text", "info").inOrder()
    }
}
