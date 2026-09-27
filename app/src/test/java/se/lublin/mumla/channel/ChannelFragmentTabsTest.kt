package se.lublin.mumla.channel

import android.view.Window
import android.widget.EditText
import androidx.appcompat.view.menu.MenuBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import com.google.android.material.tabs.TabLayout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.HumlaEvent
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.addNow
import se.lublin.mumla.testing.channelRow
import se.lublin.mumla.testing.hostWith
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubEvents
import se.lublin.mumla.testing.textMessage

/**
 * The channel screen's tabs: its menu has the items of the shown tab, and the tabs share the chat
 * target.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelFragmentTabsTest {
    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var fragment: ChannelFragment

    private val session = mockk<IHumlaSession>(relaxed = true)

    @Before
    fun setUp() {
        session.stubConnected()
        controller = hostWith(session)
        fragment = controller.get().addNow(ChannelFragment(), "channel", inContent = true)
        idleMainLooper()
    }

    private fun menuTitles(): List<String> {
        val activity = controller.get()
        val menu = MenuBuilder(activity)
        activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu)
        activity.onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)
        return (0 until menu.size()).map { menu.getItem(it).title.toString() }
    }

    private fun titles(vararg ids: Int): List<String> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return ids.map { context.getString(it) }
    }

    private fun showTab(position: Int) {
        fragment.requireView().findViewById<ViewPager2>(R.id.channel_view_pager).currentItem = position
        idleMainLooper()
    }

    /**
     * Transmit mode and noise suppression are in the audio panel; mute and deafen in the control
     * bar, see `ChannelFragmentControlBarTest`.
     */
    @Test
    fun `the channel tab offers only the list's search`() {
        assertThat(menuTitles()).containsExactlyElementsIn(titles(R.string.search))
    }

    @Test
    fun `the chat tab offers only the clear item`() {
        showTab(1)

        assertThat(menuTitles()).containsExactlyElementsIn(titles(R.string.clearChat))

        showTab(0)

        assertThat(menuTitles()).containsExactlyElementsIn(titles(R.string.search))
    }

    private fun chatTab() =
        fragment.requireView().findViewById<TabLayout>(R.id.channel_tabs).getTabAt(1)!!

    private fun receive(body: String) {
        session.stubEvents().tryEmit(HumlaEvent.TextMessage(textMessage(body, actor = 2, actorName = "alice")))
        idleMainLooper()
    }

    @Test
    fun `messages received on the channel tab are counted on the chat tab`() {
        receive("one")
        receive("two")

        val badge = chatTab().badge!!
        assertThat(badge.isVisible).isTrue()
        assertThat(badge.number).isEqualTo(2)
        assertThat(badge.contentDescription.toString()).isEqualTo(
            controller.get().resources.getQuantityString(R.plurals.unread_messages, 2, 2),
        )
    }

    @Test
    fun `showing the chat tab clears the count, and nothing counts while it is shown`() {
        receive("one")

        showTab(1)
        assertThat(chatTab().badge).isNull()
        receive("two")
        assertThat(chatTab().badge).isNull()

        showTab(0)
        receive("three")
        assertThat(chatTab().badge!!.number).isEqualTo(1)
    }

    @Test
    fun `a target picked in the channel tab is where the chat tab sends`() {
        val lounge = channelRow(5, "Lounge", userCount = null)
        val list = fragment.childFragmentManager.fragments.filterIsInstance<ChannelListFragment>().single()
        list.onChannelClick(lounge)

        showTab(1)

        val chat = fragment.childFragmentManager.fragments.filterIsInstance<ChannelChatFragment>().single()
        val editor = chat.requireView().findViewById<EditText>(R.id.chatTextEdit)
        val expected = controller.get().getString(R.string.messageToChannel, "Lounge")
        assertThat(editor.hint.toString()).isEqualTo(expected)
    }
}
