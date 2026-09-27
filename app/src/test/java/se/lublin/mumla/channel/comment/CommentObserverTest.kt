package se.lublin.mumla.channel.comment

import androidx.fragment.app.DialogFragment
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.UserState
import se.lublin.humla.IHumlaSession
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubModel

@RunWith(RobolectricTestRunner::class)
class CommentObserverTest {

    private val session: IHumlaSession = mockk<IHumlaSession>(relaxed = true).stubConnected()
    private val model = session.stubModel(withComment(7, null))
    private val activity = Robolectric.buildActivity(ServiceHostActivity::class.java).setup().get()
        .also { it.bind(session) }

    /** Whatever else follows the model (the session's own observers) all along. */
    private val othersListening = model.subscriptionCount.value

    private val listeners: Int get() = model.subscriptionCount.value - othersListening

    private fun withComment(user: Int, comment: String?) = serverState {
        channel(0, "Root")
        user(UserState(7, "Ann", 0, comment = if (user == 7) comment else null))
        user(UserState(8, "Bob", 0, comment = if (user == 8) comment else null))
    }

    private fun show(fragment: DialogFragment) {
        fragment.show(activity.supportFragmentManager, "comment")
        idleMainLooper()
    }

    private fun close(fragment: DialogFragment) {
        fragment.dismiss()
        idleMainLooper()
    }

    @Test
    fun aUserCommentStopsWaitingWhenTheDialogClosesWithoutAReply() {
        val fragment = UserCommentFragment.newInstance(7, comment = null, editing = false)
        show(fragment)
        assertThat(listeners).isEqualTo(1)
        verify { session.actions.requestComment(7) }

        close(fragment)

        assertThat(listeners).isEqualTo(0)
    }

    @Test
    fun aChannelDescriptionStopsWaitingWhenTheDialogClosesWithoutAReply() {
        val fragment = ChannelDescriptionFragment.newInstance(3, description = null)
        show(fragment)
        assertThat(listeners).isEqualTo(1)
        verify { session.actions.requestChannelDescription(3) }

        close(fragment)

        assertThat(listeners).isEqualTo(0)
    }

    @Test
    fun theReplyStopsTheWaitingAndClosingAfterwardsIsHarmless() {
        val fragment = UserCommentFragment.newInstance(7, comment = null, editing = false)
        show(fragment)

        model.value = withComment(7, "hello")
        idleMainLooper()
        assertThat(listeners).isEqualTo(0)

        close(fragment)
        assertThat(listeners).isEqualTo(0)
    }

    @Test
    fun anotherUsersCommentKeepsTheWaiting() {
        val fragment = UserCommentFragment.newInstance(7, comment = null, editing = false)
        show(fragment)

        model.value = withComment(8, "not yours")
        idleMainLooper()

        assertThat(listeners).isEqualTo(1)
        close(fragment)
    }
}
