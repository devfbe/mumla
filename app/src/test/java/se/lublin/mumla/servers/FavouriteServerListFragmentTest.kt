package se.lublin.mumla.servers

import android.content.DialogInterface
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowDialog
import se.lublin.humla.model.Server
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.drainMainUntil
import se.lublin.mumla.testing.host

@RunWith(RobolectricTestRunner::class)
class FavouriteServerListFragmentTest {
    private val activity = Robolectric.buildActivity(ServiceHostActivity::class.java).setup().get()
    private val server = Server(3, "Home", "home.example", 0, "me", null)

    private fun show(servers: List<Server>): FavouriteServerListFragment {
        every { activity.database.getServers() } returns servers
        val fragment = activity.host(FavouriteServerListFragment())
        idleMainLooper()
        return fragment
    }

    private val FavouriteServerListFragment.grid: RecyclerView
        get() = requireView().findViewById(R.id.server_list_grid)
    private val FavouriteServerListFragment.empty: View
        get() = requireView().findViewById(R.id.server_list_grid_empty)

    @Test
    fun `without servers the empty notice shows`() {
        val fragment = show(emptyList())

        assertThat(fragment.grid.adapter!!.itemCount).isEqualTo(0)
        assertThat(fragment.empty.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun `a stored server gets a card, and deleting it removes card and row`() {
        val fragment = show(listOf(server))
        assertThat(fragment.grid.adapter!!.itemCount).isEqualTo(1)
        assertThat(fragment.empty.visibility).isEqualTo(View.GONE)

        fragment.deleteServer(server)
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMainLooper()

        verify { activity.database.removeServer(server) }
        // The list is diffed off the main thread.
        drainMainUntil(description = "the empty notice") { fragment.empty.visibility == View.VISIBLE }
    }
}
