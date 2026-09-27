package se.lublin.mumla.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Both user-visible notifications reach the notification manager across the API 33 boundary.
 *
 * On 31 and 32 the platform does not define POST_NOTIFICATIONS, so `Context.checkSelfPermission`
 * answers "denied"; `ContextCompat.checkSelfPermission` falls back to
 * `areNotificationsEnabled()`. So the API 31 cases deny the permission and still expect the
 * notification, and one case turns notifications off to pin that the user's choice is honoured.
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

    private fun showMessage() = MumlaMessageNotification(context).show("alice", "hello")

    /**
     * Below API 33 ContextCompat.registerReceiver emulates RECEIVER_NOT_EXPORTED behind
     * [DYNAMIC_RECEIVER_PERMISSION] and throws unless the app holds it. A real install grants it
     * (androidx.core declares it at signature level); Robolectric grants no install-time
     * permissions, so it is granted here.
     */
    private fun showReconnect(): MumlaReconnectNotification {
        shadowOf(context as Application)
            .grantPermissions(context.packageName + DYNAMIC_RECEIVER_PERMISSION)
        return MumlaReconnectNotification(context, NoopActions).also { it.show("connection lost") }
    }

    /**
     * The declaration comes from androidx.core's manifest. If it disappears while minSdk < 33,
     * MumlaReconnectNotification.show() throws on Android 12.
     */
    @Test
    fun `the merged manifest declares the permission ContextCompat needs below api 33`() {
        val info = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)

        assertThat(info.requestedPermissions?.toList())
            .contains(context.packageName + DYNAMIC_RECEIVER_PERMISSION)
    }

    /** Shows each kind of notification afresh and checks whether it reached the manager. */
    private fun assertEachKindIsPosted(posted: Boolean) {
        val kinds = listOf<Pair<String, () -> Unit>>("message" to { showMessage() }, "reconnect" to { showReconnect() })
        for ((kind, show) in kinds) {
            notificationManager.cancelAll()
            show()
            assertWithMessage(kind).that(postedNotifications()).hasSize(if (posted) 1 else 0)
        }
    }

    @Test
    fun `notifications are posted on api 31 although the permission cannot be held`() {
        assumeBelowTiramisu()
        denyPostNotifications()

        assertEachKindIsPosted(true)
    }

    @Test
    fun `a chat message is dropped on api 31 when the user turned notifications off`() {
        assumeBelowTiramisu()
        shadowOf(notificationManager).setNotificationsEnabled(false)

        showMessage()

        assertThat(postedNotifications()).isEmpty()
    }

    @Test
    fun `notifications are posted on api 33 when the permission is granted`() {
        assumeTiramisuOrLater()
        grantPostNotifications()

        assertEachKindIsPosted(true)
    }

    @Test
    fun `notifications are dropped on api 33 when the permission is denied`() {
        assumeTiramisuOrLater()
        denyPostNotifications()

        assertEachKindIsPosted(false)
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
    }
}
