package se.lublin.mumla.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
    private val conversation = MumlaMessageNotification.Conversation("me", "Lobby", "Home")

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted(): Notification = shadowOf(manager).allNotifications.single()

    private fun style() = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted())!!

    private fun texts() = style().messages.map { it.text.toString() }

    private fun reply() = posted().actions.single()

    @Test
    fun onlyTheNewestFiveMessagesAreShownButAllAreCounted() {
        for (i in 1..8) notification.show("alice", "message $i")

        assertThat(texts()).containsExactlyElementsIn((4..8).map { "message $it" }).inOrder()
        assertThat(posted().number).isEqualTo(8)
    }

    @Test
    fun aLongMessageIsCutShort() {
        notification.show("alice", "x".repeat(5000))

        val text = texts().single()
        assertThat(text.length).isAtMost(200)
        assertThat(text).endsWith("…")
    }

    @Test
    fun dismissStartsTheCountAfresh() {
        notification.show("alice", "one")
        notification.show("alice", "two")
        notification.dismiss()

        notification.show("bob", "three")

        assertThat(texts()).containsExactly("three")
        assertThat(posted().number).isEqualTo(1)
    }

    @Test
    fun itIsAConversationInTheChannelWithEachSenderAsAPerson() {
        notification.show("alice", "hi", conversation)
        notification.show("bob", "hello", conversation)

        val style = style()
        assertThat(style.conversationTitle.toString()).isEqualTo("Lobby")
        assertThat(style.isGroupConversation).isTrue()
        assertThat(style.user.name.toString()).isEqualTo("me")
        assertThat(style.messages.map { it.person?.name.toString() }).containsExactly("alice", "bob").inOrder()
        assertThat(posted().extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString()).isEqualTo("Home")
        assertThat(posted().channelId).isEqualTo(NotificationChannels.MESSAGES)
    }

    @Test
    fun withoutOurNameTheUserIsYou() {
        notification.show("alice", "hi")

        assertThat(style().user.name.toString()).isEqualTo(context.getString(R.string.notification_you))
    }

    @Test
    fun theReplyActionTakesTextAndGoesToTheServiceInAMutableIntent() {
        notification.show("alice", "hi", conversation)

        val action = reply()
        assertThat(action.remoteInputs.single().label.toString())
            .isEqualTo(context.getString(R.string.notification_reply))
        assertThat(action.semanticAction).isEqualTo(Notification.Action.SEMANTIC_ACTION_REPLY)
        assertThat(action.actionIntent.isImmutable).isFalse()
        val intent = shadowOf(action.actionIntent).savedIntent
        assertThat(intent.component?.className).isEqualTo(MumlaService::class.java.name)
        assertThat(intent.action).isEqualTo(MumlaMessageNotification.ACTION_REPLY)
        assertThat(posted().contentIntent.isImmutable).isTrue()
    }

    @Test
    fun theTypedTextIsReadBackFromTheReplyIntent() {
        val intent = Intent(MumlaMessageNotification.ACTION_REPLY)
        notification.show("alice", "hi")
        val input = reply().remoteInputs.single()
        android.app.RemoteInput.addResultsToIntent(
            arrayOf(input), intent, Bundle().apply { putCharSequence(input.resultKey, "on my way") },
        )

        assertThat(MumlaMessageNotification.replyText(intent)).isEqualTo("on my way")
    }

    @Test
    fun ourReplyJoinsTheConversationSilentlyAndCountsNothing() {
        notification.show("alice", "hi", conversation)
        // A message alerts: the platform's default, alerting for every notification.
        assertThat(posted().groupAlertBehavior).isEqualTo(Notification.GROUP_ALERT_ALL)

        notification.showReply("on my way", conversation)

        val last = style().messages.last()
        assertThat(last.text.toString()).isEqualTo("on my way")
        assertThat(last.person).isNull()
        assertThat(posted().number).isEqualTo(1)
        // How NotificationCompat silences a notification: it alerts only as a group summary.
        assertThat(posted().groupAlertBehavior).isEqualTo(Notification.GROUP_ALERT_SUMMARY)
    }

    @Test
    fun theChannelsShareAGroupAndSayWhatTheyAreFor() {
        notification.show("alice", "hi")

        assertThat(manager.getNotificationChannelGroup(NotificationChannels.GROUP)).isNotNull()
        val ids = listOf(NotificationChannels.CONNECTION, NotificationChannels.MESSAGES, NotificationChannels.RECONNECT)
        for (id in ids) {
            val channel = manager.getNotificationChannel(id)
            assertThat(channel.group).isEqualTo(NotificationChannels.GROUP)
            assertThat(channel.description).isNotEmpty()
        }
    }
}
