package se.lublin.mumla.channel

import android.os.Looper
import android.view.Window
import android.widget.EditText
import androidx.appcompat.view.menu.MenuBuilder
import androidx.test.core.app.ApplicationProvider
import androidx.viewpager2.widget.ViewPager2
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.R
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubEvents

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
        val service = mockk<IMumlaService>(relaxed = true).stubConnected(session)
        service.stubEvents()
        every { service.messageLog } returns MutableStateFlow(emptyList())
        controller = Robolectric.buildActivity(ServiceHostActivity::class.java).setup()
        controller.get().bind(service)
        fragment = ChannelFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment, "channel").commitNow()
        shadowOf(Looper.getMainLooper()).idle()
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
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `the channel tab adds the list's items after the input method`() {
        assertThat(menuTitles()).containsExactlyElementsIn(
            titles(
                R.string.audioInputMethod, R.string.mute, R.string.deafen, R.string.search,
                R.string.noiseSuppression,
            ),
        ).inOrder()
    }

    @Test
    fun `the chat tab adds only the clear item after the input method`() {
        showTab(1)

        assertThat(menuTitles()).containsExactlyElementsIn(titles(R.string.audioInputMethod, R.string.clearChat))
            .inOrder()

        showTab(0)

        assertThat(menuTitles()).containsExactlyElementsIn(
            titles(
                R.string.audioInputMethod, R.string.mute, R.string.deafen, R.string.search,
                R.string.noiseSuppression,
            ),
        ).inOrder()
    }

    @Test
    fun `a target picked in the channel tab is where the chat tab sends`() {
        val lounge = FakeChannel(5, "Lounge")
        val list = fragment.childFragmentManager.fragments.filterIsInstance<ChannelListFragment>().single()
        list.onChannelClick(lounge)

        showTab(1)

        val chat = fragment.childFragmentManager.fragments.filterIsInstance<ChannelChatFragment>().single()
        val editor = chat.requireView().findViewById<EditText>(R.id.chatTextEdit)
        val expected = controller.get().getString(R.string.messageToChannel, "Lounge")
        assertThat(editor.hint.toString()).isEqualTo(expected)
    }
}
