package se.lublin.mumla.service

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import se.lublin.mumla.MainScreen
import se.lublin.mumla.R
import se.lublin.mumla.app.DrawerAdapter
import se.lublin.mumla.app.MumlaActivity
import se.lublin.mumla.testing.idleMainLooper

/**
 * The foreground notification: what it shows, what its buttons reach, and when it holds the
 * service in the foreground. Every assertion reads back from an object the notification does not
 * own (notification manager, foreground state, receivers, the buttons' own PendingIntents).
 */
@RunWith(RobolectricTestRunner::class)
class MumlaConnectionNotificationTest {
    /** A service with no behavior of its own; only its foreground state is under test. */
    class HostService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null
    }

    /** Counts each action separately, so a button wired to the wrong callback is visible. */
    class RecordingListener : MumlaConnectionNotification.OnActionListener {
        val calls = mutableListOf<String>()
        override fun onMuteToggled() {
            calls += "mute"
        }

        override fun onDeafenToggled() {
            calls += "deafen"
        }

        override fun onOverlayToggled() {
            calls += "overlay"
        }

        override fun onReconnectCancelled() {
            calls += "cancelReconnect"
        }
    }

    private val controller: ServiceController<HostService> = Robolectric.buildService(HostService::class.java).create()
    private val service = controller.get()
    private val listener = RecordingListener()
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager
        get() = service.getSystemService(NotificationManager::class.java)

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun MumlaConnectionNotification.configure(text: String, actions: Boolean) {
        customContentText = text
        actionsShown = actions
    }

    private fun posted(): Notification = shadowOf(notificationManager).getNotification(NOTIFICATION_ID)

    private fun ourReceivers() = shadowOf(app).registeredReceivers.filter {
        it.intentFilter.hasAction("b_mute")
    }


    @Test
    fun showPutsTheServiceInTheForegroundWithTheContentText() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        val shadow = shadowOf(service)
        assertThat(shadow.isForegroundStopped).isFalse()
        assertThat(shadow.lastForegroundNotificationId).isEqualTo(NOTIFICATION_ID)
        assertThat(shadow.lastForegroundNotification.extras.getString(Notification.EXTRA_TEXT))
            .isEqualTo("Connecting")
    }

    @Test
    fun theNotificationIsAnOngoingCallWithTheAppIconAndNoTimestamp() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        val n = shadowOf(service).lastForegroundNotification
        assertThat(n.smallIcon.resId).isEqualTo(R.drawable.ic_stat_notify)
        assertThat(n.category).isEqualTo(Notification.CATEGORY_CALL)
        assertThat(n.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN, true)).isFalse()
        @Suppress("DEPRECATION")
        assertThat(n.priority).isEqualTo(NotificationCompat.PRIORITY_LOW)
        assertThat(n.channelId).isEqualTo(CHANNEL_ID)
    }

    @Test
    fun aTextChangeDoesNotAlertAgain() {
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)
        notification.show()
        notification.customContentText = "Connected"
        notification.show()

        assertThat(posted().flags and Notification.FLAG_ONLY_ALERT_ONCE).isNotEqualTo(0)
        assertThat(posted().extras.getString(Notification.EXTRA_TEXT)).isEqualTo("Connected")
    }

    @Test
    fun theChannelOfEarlierVersionsIsDeleted() {
        notificationManager.createNotificationChannel(
            android.app.NotificationChannel("connected_channel", "old", NotificationManager.IMPORTANCE_DEFAULT),
        )

        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        assertThat(notificationManager.getNotificationChannel("connected_channel")).isNull()
    }

    @Test
    fun theChannelIsCreatedWithTheConnectedLabelAtLowImportance() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
        assertThat(channel).isNotNull()
        assertThat(channel.name.toString()).isEqualTo(service.getString(R.string.connected))
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun tappingTheNotificationOpensTheServerDrawerOfTheMainActivity() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        val content = shadowOf(shadowOf(service).lastForegroundNotification.contentIntent)
        assertThat(content.isActivity).isTrue()
        assertThat(content.savedIntent.component?.className).isEqualTo(MumlaActivity::class.java.name)
        assertThat(content.savedIntent.getIntExtra(MainScreen.EXTRA_SCREEN, -1))
            .isEqualTo(DrawerAdapter.ITEM_SERVER)
        assertThat(content.isImmutable).isTrue()
    }

    /**
     * Extras are not part of a PendingIntent's identity, and MumlaMessageNotification asks for the
     * same activity under the same request code, so the flag itself is asserted.
     */
    @Test
    fun theContentIntentReplacesAnyEarlierOneSoItsExtraIsTheOneSent() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        val content = shadowOf(shadowOf(service).lastForegroundNotification.contentIntent)
        assertThat(content.flags and android.app.PendingIntent.FLAG_CANCEL_CURRENT).isNotEqualTo(0)
    }

    @Test
    fun noActionsAreShownUntilAskedFor() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        assertThat(shadowOf(service).lastForegroundNotification.actions).isNull()
    }

    @Test
    fun theThreeActionsAreMuteDeafenAndOverlayInThatOrder() {
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)
        notification.configure("Connected", actions = true)
        notification.show()

        val actions = shadowOf(service).lastForegroundNotification.actions
        assertThat(actions.map { it.title.toString() }).containsExactly(
            service.getString(R.string.mute),
            service.getString(R.string.deafen),
            service.getString(R.string.overlay),
        ).inOrder()
        assertThat(actions.map { it.icon }).containsExactly(
            R.drawable.ic_action_microphone,
            R.drawable.ic_action_audio,
            R.drawable.ic_action_channels,
        ).inOrder()
        for (action in actions) {
            val pending = shadowOf(action.actionIntent)
            assertThat(pending.isBroadcast).isTrue()
            assertThat(pending.isImmutable).isTrue()
            // Addressed to this app only, so no other app's receiver can see the button press.
            assertThat(pending.savedIntent.`package`).isEqualTo(service.packageName)
        }
    }

    /** Fires each button's own PendingIntent, i.e. both halves of the wiring at once. */
    @Test
    fun eachActionReachesItsOwnCallback() {
        val notification = MumlaConnectionNotification.create(service, "Connected", listener)
        notification.configure("Connected", actions = true)
        notification.show()

        for ((index, expected) in listOf("mute", "deafen", "overlay").withIndex()) {
            listener.calls.clear()
            shadowOf(service).lastForegroundNotification.actions[index].actionIntent.send()
            idleMainLooper()
            assertThat(listener.calls).containsExactly(expected)
        }
    }

    @Test
    fun theActionReceiverIsNotExported() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        val receivers = ourReceivers()
        assertThat(receivers).hasSize(1)
        assertThat(receivers.single().flags and Context.RECEIVER_NOT_EXPORTED).isNotEqualTo(0)
        assertThat(receivers.single().intentFilter.actionsIterator().asSequence().toList())
            .containsExactly("b_mute", "b_deafen", "b_overlay", "b_foreground_cancel_reconnect")
    }

    // ---- ConnectionLost / Reconnecting: the only thing to offer is giving up ----------------

    @Test
    fun theCancelReconnectActionIsTheOnlyOneWhileReconnecting() {
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)
        notification.show()
        notification.customContentText = "Connection lost"
        notification.cancelReconnectShown = true
        notification.show()

        val action = posted().actions.single()
        assertThat(action.title.toString()).isEqualTo(service.getString(R.string.cancel_reconnect))
        assertThat(action.icon).isEqualTo(R.drawable.ic_action_delete_dark)
        val pending = shadowOf(action.actionIntent)
        assertThat(pending.isBroadcast).isTrue()
        assertThat(pending.isImmutable).isTrue()
        assertThat(pending.savedIntent.`package`).isEqualTo(service.packageName)
    }

    @Test
    fun theCancelReconnectActionReachesItsCallbackThroughTheOneReceiver() {
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)
        notification.show()
        notification.cancelReconnectShown = true
        notification.show()

        posted().actions.single().actionIntent.send()
        idleMainLooper()

        assertThat(listener.calls).containsExactly("cancelReconnect")
        assertThat(ourReceivers()).hasSize(1)
    }

    @Test
    fun noCancelReconnectActionUntilAskedFor() {
        val notification = MumlaConnectionNotification.create(service, "Connected", listener)
        notification.configure("Connected", actions = true)
        notification.show()

        assertThat(posted().actions.map { it.title.toString() })
            .doesNotContain(service.getString(R.string.cancel_reconnect))
    }

    @Test
    fun hideLeavesTheForegroundRemovesTheNotificationAndStopsListening() {
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)
        notification.configure("Connected", actions = true)
        notification.show()
        val mute = shadowOf(service).lastForegroundNotification.actions[0].actionIntent

        notification.hide()

        assertThat(shadowOf(service).isForegroundStopped).isTrue()
        assertThat(shadowOf(service).notificationShouldRemoved).isTrue()
        assertThat(ourReceivers()).isEmpty()
        mute.send()
        idleMainLooper()
        assertThat(listener.calls).isEmpty()
    }

    @Test
    fun hideWithoutShowTouchesNothing() {
        MumlaConnectionNotification.create(service, "Connecting", listener).hide()

        assertThat(ourReceivers()).isEmpty()
    }

    // ---- the foreground start is made once, and a refusal is survivable ---------------------

    @Test
    fun showReportsThatTheServiceIsInTheForeground() {
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)

        assertThat(notification.show()).isTrue()
        assertThat(notification.isForeground).isTrue()
    }

    @Test
    fun aRefusedForegroundStartIsReportedInsteadOfCrashing() {
        shadowOf(service).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("not allowed from the background"),
        )
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)

        assertThat(notification.show()).isFalse()
        assertThat(notification.isForeground).isFalse()
        // Nothing can press a button on a notification that is not there.
        assertThat(ourReceivers()).isEmpty()
    }

    @Test
    fun aSecurityExceptionIsReportedInsteadOfCrashing() {
        shadowOf(service).setThrowInStartForeground(SecurityException("missing FOREGROUND_SERVICE_MICROPHONE"))
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)

        assertThat(notification.show()).isFalse()
        assertThat(notification.isForeground).isFalse()
        assertThat(ourReceivers()).isEmpty()
    }

    /**
     * ForegroundServiceStartNotAllowedException is an IllegalStateException; only the refusal is
     * caught, since a manifest without a foregroundServiceType is a build defect.
     */
    @Test
    fun anyOtherFailureOfTheForegroundStartStillPropagates() {
        shadowOf(service).setThrowInStartForeground(IllegalStateException("a defect, not a refusal"))
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)

        assertThrows(IllegalStateException::class.java) { notification.show() }
    }

    /**
     * The platform re-checks the background-start restriction on every startForeground call, so a
     * text update while the screen is off is refused like a fresh start. From the second show on
     * every start is refused here, and the update must still arrive.
     */
    @Test
    fun aSecondShowUpdatesTheNotificationWithoutAnotherForegroundStart() {
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)
        notification.show()
        shadowOf(service).setThrowInStartForeground(
            ForegroundServiceStartNotAllowedException("not allowed from the background"),
        )

        notification.configure("Connected", actions = true)

        assertThat(notification.show()).isTrue()
        assertThat(notification.isForeground).isTrue()
        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(posted().extras.getString(Notification.EXTRA_TEXT)).isEqualTo("Connected")
        assertThat(posted().actions).hasLength(3)
    }

    @Test
    fun aSecondShowDoesNotListenTwice() {
        val notification = MumlaConnectionNotification.create(service, "Connected", listener)
        notification.configure("Connected", actions = true)
        notification.show()

        notification.show()

        assertThat(ourReceivers()).hasSize(1)
        posted().actions[0].actionIntent.send()
        idleMainLooper()
        assertThat(listener.calls).containsExactly("mute")
    }

    @Test
    fun afterHideTheNextShowAsksThePlatformAgain() {
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)
        notification.show()
        notification.hide()
        assertThat(notification.isForeground).isFalse()

        shadowOf(service).setThrowInStartForeground(SecurityException("refused"))
        assertThat(notification.show()).isFalse()
        shadowOf(service).setThrowInStartForeground(null)
        assertThat(notification.show()).isTrue()

        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(ourReceivers()).hasSize(1)
    }

    @Test
    fun onAndroid14AndLaterTheForegroundIsTypedAsMicrophone() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        assertThat(service.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    /** Below 34 the call without a type takes every type the manifest declares. */
    @Test
    @Config(sdk = [33])
    fun belowAndroid14TheForegroundTakesTheManifestTypes() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(service.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
    }

    private companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "connection_status"
    }
}
