package se.lublin.mumla.service

import android.content.Context
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.TalkState
import se.lublin.humla.model.UserState
import se.lublin.mumla.R
import se.lublin.mumla.testing.ThemedActivity

/** The overlay's user list: the users and talk states it was last given, one row each. */
@RunWith(RobolectricTestRunner::class)
class OverlayUserAdapterTest {
    private val context: Context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val users = (1..3).map { UserState(it, "user-$it", 0) }
    private val adapter = OverlayUserAdapter(context).apply { submit(users, emptyMap()) }

    @Test
    fun theRowsAreTheSubmittedUsersUntilTheNextSubmission() {
        assertThat(adapter.count).isEqualTo(3)
        assertThat(adapter.getItem(2)).isEqualTo(users[2])
        assertThat(adapter.getItemId(2)).isEqualTo(3L)

        adapter.submit(users.drop(1), emptyMap())

        assertThat(adapter.count).isEqualTo(2)
    }

    @Test
    fun aSubmissionIsAnnouncedToTheList() {
        var changes = 0
        adapter.registerDataSetObserver(object : android.database.DataSetObserver() {
            override fun onChanged() {
                changes++
            }
        })

        adapter.submit(users, mapOf(1 to TalkState.TALKING))

        assertThat(changes).isEqualTo(1)
    }

    @Test
    fun theStateIconFollowsTheStatePriority() {
        fun iconFor(user: UserState, talking: Boolean = true): Int {
            adapter.submit(listOf(user), if (talking) mapOf(user.session to TalkState.TALKING) else emptyMap())
            val view = adapter.getView(0, null, FrameLayout(context))
            return shadowOf(view.findViewById<ImageView>(R.id.user_row_state).drawable).createdFromResId
        }
        val user = users[0]

        assertThat(iconFor(user, talking = false)).isEqualTo(R.drawable.outline_circle_talking_off)
        assertThat(iconFor(user)).isEqualTo(R.drawable.outline_circle_talking_on)
        assertThat(iconFor(user.copy(isSuppressed = true))).isEqualTo(R.drawable.outline_circle_suppressed)
        assertThat(iconFor(user.copy(isSuppressed = true, isMuted = true)))
            .isEqualTo(R.drawable.outline_circle_server_muted)
        val serverDeafened = user.copy(isMuted = true, isDeafened = true)
        assertThat(iconFor(serverDeafened)).isEqualTo(R.drawable.outline_circle_server_deafened)
        // Unlike the channel list, the overlay puts the self flags above the server ones.
        assertThat(iconFor(serverDeafened.copy(isSelfMuted = true))).isEqualTo(R.drawable.outline_circle_muted)
        assertThat(iconFor(serverDeafened.copy(isSelfMuted = true, isSelfDeafened = true)))
            .isEqualTo(R.drawable.outline_circle_deafened)
    }

    @Test
    fun theRowShowsTheUsersName() {
        val view = adapter.getView(1, null, FrameLayout(context))

        assertThat(view.findViewById<TextView>(R.id.user_row_name).text.toString()).isEqualTo("user-2")
    }
}
