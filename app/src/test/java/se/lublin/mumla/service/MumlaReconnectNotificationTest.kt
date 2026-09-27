package se.lublin.mumla.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R

/**
 * The prompt shown when a session ended with an error: what it says and what its three intents
 * reach. Posting across the POST_NOTIFICATIONS boundary is in NotificationPostingTest.
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
    }

    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)
    private val actions = RecordingActions()

    @Before
    fun grant() {
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun show(error: String) = MumlaReconnectNotification(context, actions).also { it.show(error) }

    /** A second prompt replaces the first and registers nothing twice. */
    @Test
    fun showingAgainReplacesThePrompt() {
        val notification = show("first")

        notification.show("second")

        assertThat(posted()!!.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("second")
        assertThat(ourReceivers()).hasSize(1)
    }

    private fun posted(): Notification? = shadowOf(notificationManager).getNotification(NOTIFICATION_ID)

    private fun ourReceivers() = shadowOf(context).registeredReceivers.filter {
        it.intentFilter.hasAction("b_reconnect")
    }


    @Test
    fun theErrorIsTheTextUnderTheDisconnectedTitle() {
        show("socket reset")

        val n = posted()!!
        assertThat(n.extras.getString(Notification.EXTRA_TITLE))
            .isEqualTo(context.getString(R.string.mumlaDisconnected))
        assertThat(n.extras.getString(Notification.EXTRA_TEXT)).isEqualTo("socket reset")
        assertThat(n.smallIcon.resId).isEqualTo(R.drawable.ic_stat_notify)
        @Suppress("DEPRECATION")
        assertThat(n.priority).isEqualTo(NotificationCompat.PRIORITY_MAX)
        assertThat(n.channelId).isEqualTo(CHANNEL_ID)
    }

    @Test
    fun theChannelIsNamedFromAResourceAtDefaultImportance() {
        show("socket reset")

        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
        assertThat(channel).isNotNull()
        // Shown in the system's notification settings, so translatable like every other label.
        assertThat(channel.name.toString()).isEqualTo(context.getString(R.string.connection_lost_reconnecting))
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
    }

    @Test
    fun itOffersReconnectAndCanBeSwipedAway() {
        show("socket reset")

        val n = posted()!!
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isEqualTo(0)
        assertThat(n.actions.map { it.title.toString() }).containsExactly(context.getString(R.string.reconnect))
        @Suppress("DEPRECATION")
        assertThat(n.actions.single().icon).isEqualTo(R.drawable.ic_action_move)
        n.actions.single().actionIntent.send()
        idleMainLooper()
        assertThat(actions.calls).containsExactly("reconnect")
    }

    @Test
    fun swipingItAwayIsReported() {
        show("socket reset")

        posted()!!.deleteIntent.send()
        idleMainLooper()

        assertThat(actions.calls).containsExactly("dismissed")
    }

    @Test
    fun everyIntentIsAddressedToThisAppAndImmutable() {
        show("socket reset")

        val n = posted()!!
        for (pending in listOf(n.deleteIntent, n.actions.single().actionIntent).map { shadowOf(it) }) {
            assertThat(pending.isBroadcast).isTrue()
            assertThat(pending.isImmutable).isTrue()
            assertThat(pending.savedIntent.`package`).isEqualTo(context.packageName)
        }
    }

    @Test
    fun theReceiverIsNotExported() {
        show("socket reset")

        val receiver = ourReceivers().single()
        assertThat(receiver.flags and Context.RECEIVER_NOT_EXPORTED).isNotEqualTo(0)
        assertThat(receiver.intentFilter.actionsIterator().asSequence().toList())
            .containsExactly("b_dismiss", "b_reconnect")
    }

    @Test
    fun hideCancelsTheNotificationAndStopsListening() {
        val notification = show("socket reset")
        val reconnect = posted()!!.actions.single().actionIntent

        notification.hide()

        assertThat(posted()).isNull()
        assertThat(ourReceivers()).isEmpty()
        reconnect.send()
        idleMainLooper()
        assertThat(actions.calls).isEmpty()
    }

    @Test
    fun hidingTwiceIsHarmless() {
        val notification = show("socket reset")
        notification.hide()

        notification.hide()

        assertThat(posted()).isNull()
    }

    private companion object {
        const val NOTIFICATION_ID = 3
        const val CHANNEL_ID = "reconnecting_channel"
    }
}
