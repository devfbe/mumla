package se.lublin.mumla.service

import android.Manifest
import android.app.Application
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
import se.lublin.mumla.R

@RunWith(RobolectricTestRunner::class)
class MumlaMessageNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notification = MumlaMessageNotification(context)

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted() = shadowOf(manager).allNotifications.single()

    private fun lines() =
        posted().extras.getCharSequenceArray(NotificationCompat.EXTRA_TEXT_LINES)!!.map { it.toString() }

    @Test
    fun onlyTheNewestFiveMessagesAreListedButAllAreCounted() {
        for (i in 1..8) notification.show("alice", "message $i")

        assertThat(lines()).containsExactlyElementsIn(
            (4..8).map { context.getString(R.string.notification_message, "alice", "message $it") },
        ).inOrder()
        assertThat(posted().number).isEqualTo(8)
        assertThat(posted().extras.getCharSequence(NotificationCompat.EXTRA_TITLE_BIG).toString())
            .isEqualTo(context.resources.getQuantityString(R.plurals.notification_unread_many, 8, 8))
    }

    @Test
    fun aLongMessageIsCutShortInTheListAndTheText() {
        notification.show("alice", "x".repeat(5000))

        val text = posted().extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString()
        assertThat(text.length).isAtMost(200)
        assertThat(text).endsWith("…")
        assertThat(lines().single().length).isLessThan(250)
    }

    @Test
    fun dismissStartsTheCountAfresh() {
        notification.show("alice", "one")
        notification.show("alice", "two")
        notification.dismiss()

        notification.show("bob", "three")

        assertThat(lines()).containsExactly(context.getString(R.string.notification_message, "bob", "three"))
        assertThat(posted().number).isEqualTo(1)
    }
}
