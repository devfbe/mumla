package se.lublin.mumla.channel

import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.ChannelState
import se.lublin.mumla.R
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.serverState
import se.lublin.mumla.testing.stubModel

@RunWith(RobolectricTestRunner::class)
class PermissionsPopupMenuTest {

    private val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val anchor = View(activity).also { activity.setContentView(it) }
    private val session: IHumlaSession = mockk<IHumlaSession>(relaxed = true).stubConnected()
    private val prepared = mutableListOf<Int>()

    private val listener = object : PermissionsPopupMenu.IOnMenuPrepareListener {
        override fun onMenuPrepare(menu: Menu, permissions: Int) {
            prepared += permissions
        }

        override fun onMenuItemClick(item: MenuItem): Boolean = false
    }

    private fun model(permissions: Int = 0, rootPermissions: Int = 0) = serverState(permissions = rootPermissions) {
        channel(0, "Root")
        channel(ChannelState(3, "Three", parent = 0, permissions = permissions))
        channel(4, "Four")
    }

    private fun popup(channel: Int = 3) =
        PermissionsPopupMenu(activity, anchor, R.menu.context_channel, listener, channel, session)

    @Test
    fun unknownPermissionsAreRequestedAndTheMenuIsPreparedWhenTheyArrive() {
        val model = session.stubModel(model())
        val popup = popup()
        popup.show()
        assertThat(prepared).isEmpty()
        verify { session.actions.requestPermissions(3) }

        model.value = model(permissions = 0x40)
        idleMainLooper()

        assertThat(prepared).containsExactly(0x40)
        popup.dismiss()
    }

    @Test
    fun knownPermissionsPrepareTheMenuAtOnce() {
        session.stubModel(model(permissions = 0x10))

        popup().apply { show() }.dismiss()

        assertThat(prepared).containsExactly(0x10)
    }

    @Test
    fun theMenuListensOnlyWhileShown() {
        val model = session.stubModel(model())
        val popup = popup()
        popup.show()
        assertThat(model.subscriptionCount.value).isEqualTo(1)

        popup.dismiss()
        idleMainLooper()

        assertThat(model.subscriptionCount.value).isEqualTo(0)
    }

    @Test
    fun theRootChannelUsesTheServerWidePermissions() {
        session.stubModel(model(rootPermissions = 0x20))

        popup(channel = 0).apply { show() }.dismiss()

        assertThat(prepared).containsExactly(0x20)
    }
}
