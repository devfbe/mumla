package se.lublin.mumla.channel

import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.R
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.idleMainLooper

@RunWith(RobolectricTestRunner::class)
class PermissionsPopupMenuTest {

    private val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val anchor = View(activity).also { activity.setContentView(it) }
    private val permissions = MutableStateFlow(0)
    private var requests = 0
    private val prepared = mutableListOf<Int>()

    private val listener = object : PermissionsPopupMenu.IOnMenuPrepareListener {
        override fun onMenuPrepare(menu: Menu, permissions: Int) {
            prepared += permissions
        }

        override fun onMenuItemClick(item: MenuItem): Boolean = false
    }

    private fun popup() = PermissionsPopupMenu(activity, anchor, R.menu.context_channel, listener, permissions) {
        requests++
    }

    @Test
    fun unknownPermissionsAreRequestedAndTheMenuIsPreparedWhenTheyArrive() {
        val popup = popup()
        popup.show()
        assertThat(prepared).isEmpty()
        assertThat(requests).isEqualTo(1)

        permissions.value = 0x40
        idleMainLooper()

        assertThat(prepared).containsExactly(0x40)
        popup.dismiss()
    }

    @Test
    fun knownPermissionsPrepareTheMenuAtOnceAndAgainWhenTheyChange() {
        permissions.value = 0x10
        val popup = popup()
        popup.show()

        permissions.value = 0x30
        idleMainLooper()

        assertThat(prepared).containsExactly(0x10, 0x30).inOrder()
        assertThat(requests).isEqualTo(0)
        popup.dismiss()
    }

    @Test
    fun theMenuListensOnlyWhileShown() {
        val popup = popup()
        popup.show()
        assertThat(permissions.subscriptionCount.value).isEqualTo(1)

        popup.dismiss()
        idleMainLooper()

        assertThat(permissions.subscriptionCount.value).isEqualTo(0)
    }
}
