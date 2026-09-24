package se.lublin.mumla.channel

import androidx.annotation.DrawableRes
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.TalkState
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.stubConnected

/**
 * The user row's talk-state icon. The flags form a priority list, so each priority case turns on
 * one more flag than the case before it and expects that flag's icon.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class TalkStateIconTest(
    @Suppress("unused") private val name: String,
    private val user: FakeUser,
    @param:DrawableRes private val icon: Int,
) {
    @Test
    fun theRowShowsTheIconOfTheHighestPriorityState() {
        val context = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
        val root = FakeChannel(0).apply { addUser(user) }
        val session = mockk<IHumlaSession>(relaxed = true)
        every { session.getChannel(0) } returns root
        val service = mockk<IHumlaService>(relaxed = true).stubConnected(session)
        val adapter = ChannelListAdapter(
            context, service, MumlaRepository(mockk(relaxed = true), Dispatchers.Unconfined),
            mockk<FragmentManager>(relaxed = true), false, true,
        )
        val position = adapter.getUserPosition(SESSION)
        val parent = RecyclerView(context).apply { layoutManager = LinearLayoutManager(context) }
        val holder = adapter.onCreateViewHolder(parent, adapter.getItemViewType(position))

        adapter.onBindViewHolder(holder, position)

        val drawable = holder.itemView.findViewById<android.widget.ImageView>(R.id.user_row_talk_highlight).drawable
        assertThat(shadowOf(drawable).createdFromResId).isEqualTo(icon)
    }

    companion object {
        private const val SESSION = 1

        private val priority: List<Pair<(FakeUser) -> Unit, Int>> = listOf(
            { u: FakeUser -> u.suppressed = true } to R.drawable.outline_circle_suppressed,
            { u: FakeUser -> u.muted = true } to R.drawable.outline_circle_server_muted,
            { u: FakeUser -> u.selfMuted = true } to R.drawable.outline_circle_muted,
            { u: FakeUser -> u.deafened = true } to R.drawable.outline_circle_server_deafened,
            { u: FakeUser -> u.selfDeafened = true } to R.drawable.outline_circle_deafened,
        )
        private val priorityNames =
            listOf("suppressed", "server muted", "self muted", "server deafened", "self deafened")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = buildList {
            // Every talk state, enumerated, so one added later fails here until it is given an icon.
            for (state in TalkState.entries) {
                val icon = if (state == TalkState.PASSIVE) R.drawable.outline_circle_talking_off
                else R.drawable.outline_circle_talking_on
                add(arrayOf("talk state $state", FakeUser(SESSION).apply { this.state = state }, icon))
            }
            for (n in priority.indices) {
                val user = FakeUser(SESSION).apply { state = TalkState.TALKING }
                priority.take(n + 1).forEach { (flag, _) -> flag(user) }
                add(arrayOf("${priorityNames[n]} over the states before it", user, priority[n].second))
            }
        }
    }
}
