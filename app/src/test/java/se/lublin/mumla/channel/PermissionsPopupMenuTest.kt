package se.lublin.mumla.channel

import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaService
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Channel
import se.lublin.humla.session.HumlaEvent
import se.lublin.mumla.R
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubEvents

@RunWith(RobolectricTestRunner::class)
class PermissionsPopupMenuTest {

    private val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val anchor = View(activity).also { activity.setContentView(it) }
    private val session: IHumlaSession = mockk(relaxed = true)
    private val service: IHumlaService = mockk<IHumlaService>(relaxed = true).stubConnected(session)
    private val events = service.stubEvents()
    private val channel = Channel(3, false)
    private val prepared = mutableListOf<Int>()

    private val listener = object : PermissionsPopupMenu.IOnMenuPrepareListener {
        override fun onMenuPrepare(menu: Menu, permissions: Int) {
            prepared += permissions
        }

        override fun onMenuItemClick(item: MenuItem): Boolean = false
    }

    private fun popup() = PermissionsPopupMenu(activity, anchor, R.menu.context_channel, listener, channel, service)

    @Test
    fun unknownPermissionsAreRequestedAndTheMenuIsPreparedWhenTheyArrive() {
        val popup = popup()
        popup.show()
        assertThat(prepared).isEmpty()
        verify { session.requestPermissions(3) }

        channel.permissions = 0x40
        events.tryEmit(HumlaEvent.ChannelPermissionsUpdated(Channel(4, false)))
        events.tryEmit(HumlaEvent.ChannelPermissionsUpdated(channel))
        idleMainLooper()

        assertThat(prepared).containsExactly(0x40)
        popup.dismiss()
    }

    @Test
    fun knownPermissionsPrepareTheMenuAtOnce() {
        channel.permissions = 0x10
        popup().apply { show() }.dismiss()

        assertThat(prepared).containsExactly(0x10)
    }

    @Test
    fun theMenuListensOnlyWhileShown() {
        val popup = popup()
        popup.show()
        assertThat(events.subscriptionCount.value).isEqualTo(1)

        popup.dismiss()
        idleMainLooper()

        assertThat(events.subscriptionCount.value).isEqualTo(0)
    }

    @Test
    fun theRootChannelUsesTheServerWidePermissions() {
        every { session.permissions } returns 0x20
        PermissionsPopupMenu(activity, anchor, R.menu.context_channel, listener, Channel(0, false), service)
            .apply { show() }
            .dismiss()

        assertThat(prepared).containsExactly(0x20)
    }
}
