package se.lublin.mumla.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import se.lublin.humla.model.Channel
import se.lublin.humla.model.IMessage
import se.lublin.humla.model.User

/**
 * Pins that both user-visible notifications actually reach the notification manager across the
 * API 33 boundary, where POST_NOTIFICATIONS was introduced.
 *
 * On 31 and 32 -- both inside this app's minSdk -- the platform does not define
 * POST_NOTIFICATIONS, so `Context.checkSelfPermission` answers "denied" for every app.
 * `ContextCompat.checkSelfPermission` is what makes the gate in both notification classes correct
 * there: below 33 it answers with `NotificationManagerCompat.areNotificationsEnabled()` instead of
 * asking the package manager. Replacing it with the platform method silently drops every
 * notification on Android 12 and 12L, so the API 31 cases below deny the permission outright and
 * still expect the notification, and one case turns notifications off to pin that the gate keeps
 * honouring the user's choice rather than posting unconditionally.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 33])
class NotificationPostingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val notificationManager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private fun grantPostNotifications() =
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun denyPostNotifications() =
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun postedNotifications() = shadowOf(notificationManager).allNotifications

    private val message = object : IMessage {
        override fun getActor(): Int = 1
        override fun getActorName(): String = "alice"
        override fun getTargetChannels(): List<Channel> = emptyList()
        override fun getTargetTrees(): List<Channel> = emptyList()
        override fun getTargetUsers(): List<User> = emptyList()
        override fun getMessage(): String = "hello"
        override fun getReceivedTime(): Long = 0L
    }

    private fun showMessage() = MumlaMessageNotification(context).show(message)

    /**
     * Below API 33 ContextCompat.registerReceiver emulates RECEIVER_NOT_EXPORTED by registering
     * the receiver behind [DYNAMIC_RECEIVER_PERMISSION] and throws unless the app holds it. A real
     * install grants it, because androidx.core's own manifest declares it at signature level and
     * the app signs itself; Robolectric grants no install-time permission at all (it reports even
     * INTERNET as denied), so the grant is made explicit here. That the merged manifest really
     * carries the declaration is a separate test.
     */
    private fun showReconnect(): MumlaReconnectNotification {
        shadowOf(context as Application)
            .grantPermissions(context.packageName + DYNAMIC_RECEIVER_PERMISSION)
        return MumlaReconnectNotification.show(context, "connection lost", false, NoopActions)
    }

    /**
     * The declaration comes from androidx.core's own manifest, not from ours. If a future
     * androidx.core drops it while minSdk is still below 33, MumlaReconnectNotification.show()
     * starts throwing on Android 12 instead of showing the reconnect prompt.
     */
    @Test
    fun `the merged manifest declares the permission ContextCompat needs below api 33`() {
        val info = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)

        assertThat(info.requestedPermissions?.toList())
            .contains(context.packageName + DYNAMIC_RECEIVER_PERMISSION)
    }

    @Test
    fun `a chat message is posted on api 31 although the permission cannot be held`() {
        assumeBelowTiramisu()
        denyPostNotifications()

        showMessage()

        assertThat(postedNotifications()).hasSize(1)
    }

    @Test
    fun `a reconnect prompt is posted on api 31 although the permission cannot be held`() {
        assumeBelowTiramisu()
        denyPostNotifications()

        showReconnect()

        assertThat(postedNotifications()).hasSize(1)
    }

    @Test
    fun `a chat message is dropped on api 31 when the user turned notifications off`() {
        assumeBelowTiramisu()
        shadowOf(notificationManager).setNotificationsEnabled(false)

        showMessage()

        assertThat(postedNotifications()).isEmpty()
    }

    @Test
    fun `a chat message is posted on api 33 when the permission is granted`() {
        assumeTiramisuOrLater()
        grantPostNotifications()

        showMessage()

        assertThat(postedNotifications()).hasSize(1)
    }

    @Test
    fun `a reconnect prompt is posted on api 33 when the permission is granted`() {
        assumeTiramisuOrLater()
        grantPostNotifications()

        showReconnect()

        assertThat(postedNotifications()).hasSize(1)
    }

    @Test
    fun `a chat message is dropped on api 33 when the permission is denied`() {
        assumeTiramisuOrLater()
        denyPostNotifications()

        showMessage()

        assertThat(postedNotifications()).isEmpty()
    }

    @Test
    fun `a reconnect prompt is dropped on api 33 when the permission is denied`() {
        assumeTiramisuOrLater()
        denyPostNotifications()

        showReconnect()

        assertThat(postedNotifications()).isEmpty()
    }

    private fun assumeBelowTiramisu() =
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)

    private fun assumeTiramisuOrLater() =
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

    private companion object {
        const val DYNAMIC_RECEIVER_PERMISSION = ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    }

    private object NoopActions : MumlaReconnectNotification.OnActionListener {
        override fun onReconnectNotificationDismissed() {}
        override fun reconnect() {}
        override fun cancelReconnect() {}
    }
}
