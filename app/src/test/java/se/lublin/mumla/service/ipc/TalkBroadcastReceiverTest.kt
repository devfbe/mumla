package se.lublin.mumla.service.ipc

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession

@RunWith(RobolectricTestRunner::class)
class TalkBroadcastReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session = mockk<IHumlaSession>(relaxed = true)
    private val service = mockk<IHumlaService> {
        every { isConnected } returns true
        every { HumlaSession() } returns session
    }

    private fun talk(status: String) =
        Intent(TalkBroadcastReceiver.BROADCAST_TALK).putExtra(TalkBroadcastReceiver.EXTRA_TALK_STATUS, status)

    @Test
    fun broadcastsAreIgnoredWhileOtherAppsMayNotControlPushToTalk() {
        TalkBroadcastReceiver(service) { false }.onReceive(context, talk(TalkBroadcastReceiver.TALK_STATUS_ON))

        verify(exactly = 0) { session.setTalkingState(any()) }
    }

    @Test
    fun broadcastsControlPushToTalkWhenAllowed() {
        val receiver = TalkBroadcastReceiver(service) { true }

        receiver.onReceive(context, talk(TalkBroadcastReceiver.TALK_STATUS_ON))
        verify { session.setTalkingState(true) }

        receiver.onReceive(context, talk(TalkBroadcastReceiver.TALK_STATUS_OFF))
        verify { session.setTalkingState(false) }
    }
}
