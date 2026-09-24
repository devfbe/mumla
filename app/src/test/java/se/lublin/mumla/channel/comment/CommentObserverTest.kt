package se.lublin.mumla.channel.comment

import android.os.Bundle
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
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
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IUser
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.R
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.util.HumlaServiceFragment
import se.lublin.mumla.util.HumlaServiceProvider

@RunWith(RobolectricTestRunner::class)
class CommentObserverTest {

    class HostActivity : AppCompatActivity(), HumlaServiceProvider {
        val mumla: IMumlaService = mockk(relaxed = true)

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Mumla)
            super.onCreate(savedInstanceState)
        }

        override fun getService(): IMumlaService = mumla
        override fun addServiceFragment(fragment: HumlaServiceFragment) = Unit
        override fun removeServiceFragment(fragment: HumlaServiceFragment) = Unit
    }

    private val activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
    private val observer = slot<IHumlaObserver>()

    init {
        every { activity.mumla.isConnected } returns true
        every { activity.mumla.HumlaSession() } returns mockk<IHumlaSession>(relaxed = true)
        every { activity.mumla.registerObserver(capture(observer)) } returns Unit
    }

    private fun show(fragment: DialogFragment, args: Bundle) {
        fragment.arguments = args
        fragment.show(activity.supportFragmentManager, "comment")
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun close(fragment: DialogFragment) {
        fragment.dismiss()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun aUserCommentObserverIsUnregisteredWhenTheDialogClosesWithoutAReply() {
        val fragment = UserCommentFragment()
        show(fragment, Bundle().apply { putInt("session", 7) })
        assertThat(observer.isCaptured).isTrue()

        close(fragment)

        verify(exactly = 1) { activity.mumla.unregisterObserver(observer.captured) }
    }

    @Test
    fun aChannelDescriptionObserverIsUnregisteredWhenTheDialogClosesWithoutAReply() {
        val fragment = ChannelDescriptionFragment()
        show(fragment, Bundle().apply { putInt("channel", 3) })
        assertThat(observer.isCaptured).isTrue()

        close(fragment)

        verify(exactly = 1) { activity.mumla.unregisterObserver(observer.captured) }
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

        verify(exactly = 1) { activity.mumla.unregisterObserver(observer.captured) }
    }
}
