package se.lublin.mumla.channel.comment

import android.os.Bundle
import androidx.fragment.app.DialogFragment
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.IUser
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubConnected

@RunWith(RobolectricTestRunner::class)
class CommentObserverTest {

    private val mumla: IMumlaService = mockk<IMumlaService>(relaxed = true).stubConnected(mockk(relaxed = true))
    private val activity = Robolectric.buildActivity(ServiceHostActivity::class.java).setup().get()
        .also { it.bind(mumla) }
    private val observer = slot<IHumlaObserver>()

    init {
        every { mumla.registerObserver(capture(observer)) } returns Unit
    }

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
    fun aUserCommentObserverIsUnregisteredWhenTheDialogClosesWithoutAReply() {
        val fragment = UserCommentFragment()
        show(fragment, Bundle().apply { putInt("session", 7) })
        assertThat(observer.isCaptured).isTrue()

        close(fragment)

        verify(exactly = 1) { mumla.unregisterObserver(observer.captured) }
    }

    @Test
    fun aChannelDescriptionObserverIsUnregisteredWhenTheDialogClosesWithoutAReply() {
        val fragment = ChannelDescriptionFragment()
        show(fragment, Bundle().apply { putInt("channel", 3) })
        assertThat(observer.isCaptured).isTrue()

        close(fragment)

        verify(exactly = 1) { mumla.unregisterObserver(observer.captured) }
    }

    @Test
    fun anObserverThatGotItsReplyIsNotUnregisteredTwice() {
        val fragment = UserCommentFragment()
        show(fragment, Bundle().apply { putInt("session", 7) })
        val user = mockk<IUser> {
            every { session } returns 7
            every { comment } returns "hello"
        }
        observer.captured.onUserStateUpdated(user)

        close(fragment)

        verify(exactly = 1) { mumla.unregisterObserver(observer.captured) }
    }
}
