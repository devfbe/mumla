package se.lublin.mumla.channel.comment

import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.IUser
import se.lublin.humla.session.HumlaEvent
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubEvents

@RunWith(RobolectricTestRunner::class)
class CommentObserverTest {

    private val mumla: IMumlaService = mockk<IMumlaService>(relaxed = true).stubConnected(mockk(relaxed = true))
    private val events = mumla.stubEvents()
    private val activity = Robolectric.buildActivity(ServiceHostActivity::class.java).setup().get()
        .also { it.bind(mumla) }

    private val listeners: Int get() = events.subscriptionCount.value

    private fun show(fragment: DialogFragment, args: Bundle) {
        fragment.arguments = args
        fragment.show(activity.supportFragmentManager, "comment")
        idleMainLooper()
    }

    private fun close(fragment: DialogFragment) {
        fragment.dismiss()
        idleMainLooper()
    }

    @Test
    fun aUserCommentStopsListeningWhenTheDialogClosesWithoutAReply() {
        val fragment = UserCommentFragment()
        show(fragment, Bundle().apply { putInt("session", 7) })
        assertThat(listeners).isEqualTo(1)

        close(fragment)

        assertThat(listeners).isEqualTo(0)
    }

    @Test
    fun aChannelDescriptionStopsListeningWhenTheDialogClosesWithoutAReply() {
        val fragment = ChannelDescriptionFragment()
        show(fragment, Bundle().apply { putInt("channel", 3) })
        assertThat(listeners).isEqualTo(1)

        close(fragment)

        assertThat(listeners).isEqualTo(0)
    }

    @Test
    fun theReplyStopsTheListeningAndClosingAfterwardsIsHarmless() {
        val fragment = UserCommentFragment()
        show(fragment, Bundle().apply { putInt("session", 7) })
        val user = mockk<IUser> {
            every { session } returns 7
            every { comment } returns "hello"
        }

        events.tryEmit(HumlaEvent.UserStateUpdated(user))
        idleMainLooper()
        assertThat(listeners).isEqualTo(0)

        close(fragment)
        assertThat(listeners).isEqualTo(0)
    }

    @Test
    fun anotherUsersStateKeepsTheListening() {
        val fragment = UserCommentFragment()
        show(fragment, Bundle().apply { putInt("session", 7) })
        val other = mockk<IUser> {
            every { session } returns 8
            every { comment } returns "not yours"
        }

        events.tryEmit(HumlaEvent.UserStateUpdated(other))
        idleMainLooper()

        assertThat(listeners).isEqualTo(1)
        close(fragment)
    }
}
