package se.lublin.mumla.channel

import android.view.Window
import android.widget.EditText
import androidx.appcompat.view.menu.MenuBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.UserState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.addNow
import se.lublin.mumla.testing.channelRow
import se.lublin.mumla.testing.hostWith
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubModel

/**
 * The channel screen's tabs: its menu has its own items first, then those of the shown tab, and
 * the tabs share the chat target. The audio chooser is the activity's, see `MumlaActivityAudioDeviceMenuTest`.
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

    @Test
    fun `the channel tab adds the list's items after mute, deafen and the input method`() {
        assertThat(menuTitles()).containsExactlyElementsIn(
            titles(
                R.string.mute, R.string.deafen, R.string.audioInputMethod, R.string.search,
                R.string.noiseSuppression,
            ),
        ).inOrder()
    }

    @Test
    fun `the chat tab keeps mute and deafen and adds only the clear item`() {
        showTab(1)

        assertThat(menuTitles()).containsExactlyElementsIn(
            titles(R.string.mute, R.string.deafen, R.string.audioInputMethod, R.string.clearChat),
        ).inOrder()

        showTab(0)

        assertThat(menuTitles()).containsExactlyElementsIn(
            titles(
                R.string.mute, R.string.deafen, R.string.audioInputMethod, R.string.search,
                R.string.noiseSuppression,
            ),
        ).inOrder()
    }

    private fun me(muted: Boolean) = serverState(self = 1) {
        channel(0, "Root")
        user(UserState(1, "me", 0, isSelfMuted = muted, isSelfDeafened = muted))
    }

    @Test
    fun `mute and deafen are titled with what a tap does, on the chat tab too`() {
        val model = session.stubModel(me(muted = false))
        idleMainLooper()
        showTab(1)
        assertThat(menuTitles()).containsAtLeastElementsIn(titles(R.string.mute, R.string.deafen)).inOrder()

        model.value = me(muted = true)
        idleMainLooper()

        assertThat(menuTitles()).containsAtLeastElementsIn(titles(R.string.unmute, R.string.undeafen)).inOrder()
    }

    @Test
    fun `a tap on mute on the chat tab mutes`() {
        session.stubModel(me(muted = false))
        idleMainLooper()
        showTab(1)
        val activity = controller.get()
        val menu = MenuBuilder(activity)
        activity.onCreatePanelMenu(Window.FEATURE_OPTIONS_PANEL, menu)
        activity.onPreparePanel(Window.FEATURE_OPTIONS_PANEL, null, menu)

        activity.onMenuItemSelected(Window.FEATURE_OPTIONS_PANEL, menu.findItem(R.id.menu_mute_button))

        verify { session.actions.setSelfMuteDeafState(true, false) }
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
