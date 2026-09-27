package se.lublin.mumla.service.ipc

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.SessionState
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubState

@RunWith(RobolectricTestRunner::class)
class TalkBroadcastReceiverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val session = mockk<IHumlaSession>(relaxed = true).also { installSession(it.stubConnected()) }
    private val sessions = SessionManager.get(context)

    private fun talk(status: String) =
        Intent(TalkBroadcastReceiver.BROADCAST_TALK).putExtra(TalkBroadcastReceiver.EXTRA_TALK_STATUS, status)

    @Test
    fun broadcastsAreIgnoredWhileOtherAppsMayNotControlPushToTalk() {
        TalkBroadcastReceiver(sessions) { false }.onReceive(context, talk(TalkBroadcastReceiver.TALK_STATUS_ON))

        verify(exactly = 0) { session.setTalkingState(any()) }
    }

    @Test
    fun broadcastsAreIgnoredWithoutAConnectedSession() {
        session.stubState(SessionState.Reconnecting(null))

        TalkBroadcastReceiver(sessions) { true }.onReceive(context, talk(TalkBroadcastReceiver.TALK_STATUS_ON))

        verify(exactly = 0) { session.setTalkingState(any()) }
    }

    @Test
    fun broadcastsControlPushToTalkWhenAllowed() {
        val receiver = TalkBroadcastReceiver(sessions) { true }

        receiver.onReceive(context, talk(TalkBroadcastReceiver.TALK_STATUS_ON))
        verify { session.setTalkingState(true) }

        receiver.onReceive(context, talk(TalkBroadcastReceiver.TALK_STATUS_OFF))
        verify { session.setTalkingState(false) }
    }
}
