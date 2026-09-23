package se.lublin.mumla.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.R

/**
 * The prompt shown when a session ended with an error: what it says, and what its three
 * intents reach. Whether it is posted at all across the POST_NOTIFICATIONS boundary is
 * NotificationPostingTest's.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaReconnectNotificationTest {
    class RecordingActions : MumlaReconnectNotification.OnActionListener {
        val calls = mutableListOf<String>()
        override fun onReconnectNotificationDismissed() {
            calls += "dismissed"
        }

        override fun reconnect() {
            calls += "reconnect"
        }

        override fun cancelReconnect() {
            calls += "cancel"
        }
    }

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)
    private val actions = RecordingActions()

    @Before
    fun grant() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted(): Notification? = shadowOf(notificationManager).getNotification(NOTIFICATION_ID)

    private fun ourReceivers() = shadowOf(context).registeredReceivers.filter {
        it.intentFilter.hasAction("b_reconnect")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun theErrorIsTheTextUnderTheDisconnectedTitle() {
        MumlaReconnectNotification.show(context, "socket reset", false, actions)

        val n = posted()!!
        assertThat(n.extras.getString(Notification.EXTRA_TITLE)).isEqualTo(context.getString(R.string.mumlaDisconnected))
        assertThat(n.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("socket reset")
        assertThat(n.smallIcon.resId).isEqualTo(R.drawable.ic_stat_notify)
        @Suppress("DEPRECATION")
        assertThat(n.priority).isEqualTo(NotificationCompat.PRIORITY_MAX)
        assertThat(n.channelId).isEqualTo(CHANNEL_ID)
    }

    @Test
    fun theChannelIsNamedFromAResourceAtDefaultImportance() {
        MumlaReconnectNotification.show(context, "socket reset", false, actions)

        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
        assertThat(channel).isNotNull()
        // Shown in the system's notification settings, so translatable like every other label.
        assertThat(channel.name.toString()).isEqualTo(context.getString(R.string.connection_lost_reconnecting))
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
    }

    @Test
    fun withoutAutoReconnectItOffersReconnectAndCanBeSwipedAway() {
        MumlaReconnectNotification.show(context, "socket reset", false, actions)

        val n = posted()!!
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(0)
        assertThat(n.actions.map { it.title.toString() }).containsExactly(context.getString(R.string.reconnect))
        @Suppress("DEPRECATION")
        assertThat(n.actions.single().icon).isEqualTo(R.drawable.ic_action_move)
        n.actions.single().actionIntent.send()
        idle()
        assertThat(actions.calls).containsExactly("reconnect")
    }

    @Test
    fun withAutoReconnectItOffersCancelAndStays() {
        MumlaReconnectNotification.show(context, "socket reset", true, actions)

        val n = posted()!!
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(n.actions.map { it.title.toString() }).containsExactly(context.getString(R.string.cancel_reconnect))
        @Suppress("DEPRECATION")
        assertThat(n.actions.single().icon).isEqualTo(R.drawable.ic_action_delete_dark)
        n.actions.single().actionIntent.send()
        idle()
        assertThat(actions.calls).containsExactly("cancel")
    }

    @Test
    fun swipingItAwayIsReported() {
        MumlaReconnectNotification.show(context, "socket reset", false, actions)

        posted()!!.deleteIntent.send()
        idle()

        assertThat(actions.calls).containsExactly("dismissed")
    }

    @Test
    fun everyIntentIsAddressedToThisAppAndImmutable() {
        MumlaReconnectNotification.show(context, "socket reset", false, actions)
        MumlaReconnectNotification.show(context, "socket reset", true, actions)

        val n = posted()!!
        for (pending in listOf(n.deleteIntent, n.actions.single().actionIntent).map { shadowOf(it) }) {
            assertThat(pending.isBroadcast).isTrue()
            assertThat(pending.isImmutable).isTrue()
            assertThat(pending.savedIntent.`package`).isEqualTo(context.packageName)
        }
    }

    @Test
    fun theReceiverIsNotExported() {
        MumlaReconnectNotification.show(context, "socket reset", false, actions)

        val receiver = ourReceivers().single()
        assertThat(receiver.flags and Context.RECEIVER_NOT_EXPORTED).isNotEqualTo(0)
        assertThat(receiver.intentFilter.actionsIterator().asSequence().toList())
            .containsExactly("b_dismiss", "b_reconnect", "b_cancel_reconnect")
    }

    @Test
    fun hideCancelsTheNotificationAndStopsListening() {
        val notification = MumlaReconnectNotification.show(context, "socket reset", false, actions)
        val reconnect = posted()!!.actions.single().actionIntent

        notification.hide()

        assertThat(posted()).isNull()
        assertThat(ourReceivers()).isEmpty()
        reconnect.send()
        idle()
        assertThat(actions.calls).isEmpty()
    }

    @Test
    fun hidingTwiceIsHarmless() {
        val notification = MumlaReconnectNotification.show(context, "socket reset", false, actions)
        notification.hide()

        notification.hide()

        assertThat(posted()).isNull()
    }

    private companion object {
        const val NOTIFICATION_ID = 3
        const val CHANNEL_ID = "reconnecting_channel"
    }
}
