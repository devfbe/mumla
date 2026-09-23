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
import android.os.Looper
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
import se.lublin.mumla.R
import se.lublin.mumla.app.DrawerAdapter
import se.lublin.mumla.app.MumlaActivity

/**
 * The foreground notification: what it shows, what its buttons reach, and when it holds the
 * service in the foreground.
 *
 * Every assertion reads a result back from an object the notification does not own -- the
 * notification manager, the service's foreground state, the registered receivers, a listener
 * called through the button's own PendingIntent -- because that is all this class does (spec
 * 4.04, sweep by effect).
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

    /** The only line that differs between the Java class and its Kotlin conversion. */
    private fun MumlaConnectionNotification.configure(text: String, actions: Boolean) {
        customContentText = text
        actionsShown = actions
    }

    private fun posted(): Notification = shadowOf(notificationManager).getNotification(NOTIFICATION_ID)

    private fun ourReceivers() = shadowOf(app).registeredReceivers.filter {
        it.intentFilter.hasAction("b_mute")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    // ---- what the notification shows -------------------------------------------------------

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
        assertThat(n.priority).isEqualTo(NotificationCompat.PRIORITY_DEFAULT)
        assertThat(n.channelId).isEqualTo(CHANNEL_ID)
    }

    @Test
    fun theChannelIsCreatedWithTheConnectedLabelAtDefaultImportance() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        val channel = notificationManager.getNotificationChannel(CHANNEL_ID)
        assertThat(channel).isNotNull()
        assertThat(channel.name.toString()).isEqualTo(service.getString(R.string.connected))
        assertThat(channel.importance).isEqualTo(NotificationManager.IMPORTANCE_DEFAULT)
    }

    @Test
    fun tappingTheNotificationOpensTheServerDrawerOfTheMainActivity() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        val content = shadowOf(shadowOf(service).lastForegroundNotification.contentIntent)
        assertThat(content.isActivity).isTrue()
        assertThat(content.savedIntent.component?.className).isEqualTo(MumlaActivity::class.java.name)
        assertThat(content.savedIntent.getIntExtra(MumlaActivity.EXTRA_DRAWER_FRAGMENT, -1))
            .isEqualTo(DrawerAdapter.ITEM_SERVER)
        assertThat(content.isImmutable).isTrue()
    }

    /**
     * Extras are not part of a PendingIntent's identity, and MumlaMessageNotification asks for the
     * same activity under the same request code. Both carry ITEM_SERVER today, so the only thing
     * that shows the flag is the flag.
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

    // ---- what the buttons reach -------------------------------------------------------------

    /** Fires each button's own PendingIntent, i.e. both halves of the wiring at once. */
    @Test
    fun eachActionReachesItsOwnCallback() {
        val notification = MumlaConnectionNotification.create(service, "Connected", listener)
        notification.configure("Connected", actions = true)
        notification.show()

        for ((index, expected) in listOf("mute", "deafen", "overlay").withIndex()) {
            listener.calls.clear()
            shadowOf(service).lastForegroundNotification.actions[index].actionIntent.send()
            idle()
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
            .containsExactly("b_mute", "b_deafen", "b_overlay")
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
        idle()
        assertThat(listener.calls).isEmpty()
    }

    @Test
    fun hideWithoutShowTouchesNothing() {
        MumlaConnectionNotification.create(service, "Connecting", listener).hide()

        assertThat(ourReceivers()).isEmpty()
    }

    // ---- spec A6: the foreground start is made once, and a refusal is survivable -----------

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
     * ForegroundServiceStartNotAllowedException is an IllegalStateException, so this is the test
     * that tells "catch the refusal" apart from "catch everything of its supertype": a manifest
     * that lost its foregroundServiceType is a build defect and must not turn into a chat line.
     */
    @Test
    fun anyOtherFailureOfTheForegroundStartStillPropagates() {
        shadowOf(service).setThrowInStartForeground(IllegalStateException("a defect, not a refusal"))
        val notification = MumlaConnectionNotification.create(service, "Connecting", listener)

        assertThrows(IllegalStateException::class.java) { notification.show() }
    }

    /**
     * The platform re-checks the background-start restriction on EVERY startForeground call,
     * "regardless of whether stopForeground() has been called or not" (ActiveServices,
     * setServiceForegroundInnerLocked, the `mStartForegroundCount >= 1` arm). A text change sent
     * through startForeground while the screen is off is therefore refused exactly like a fresh
     * start -- which is the complaint this task closes. From the second show on, the platform
     * here refuses every start, and the update must still arrive.
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
        idle()
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

    // ---- the foreground service type --------------------------------------------------------

    @Test
    fun onAndroid14AndLaterTheForegroundIsTypedAsMicrophone() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        assertThat(service.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
    }

    /**
     * Below 34 the call without a type takes every type the manifest declares
     * (microphone|mediaPlayback); the typed call would narrow that to microphone.
     */
    @Test
    @Config(sdk = [33])
    fun belowAndroid14TheForegroundTakesTheManifestTypes() {
        MumlaConnectionNotification.create(service, "Connecting", listener).show()

        assertThat(shadowOf(service).isForegroundStopped).isFalse()
        assertThat(service.foregroundServiceType).isEqualTo(ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
    }

    private companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "connected_channel"
    }
}
