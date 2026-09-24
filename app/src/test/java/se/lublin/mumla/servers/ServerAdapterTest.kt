package se.lublin.mumla.servers

import android.content.Context
import android.view.MenuItem
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ServerAdapterTest {
    private val context: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), R.style.Theme_Mumla)
    private val parent = FrameLayout(context)
    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val sockets = AtomicInteger()
    private val server = Server(1, "s", "127.0.0.1", 64738, "me", "")

    /** Every ping gets no answer; counts the sockets the pinger opened. */
    private val pinger = ServerPinger {
        sockets.incrementAndGet()
        object : DatagramSocket() {
            override fun send(p: DatagramPacket) = throw IOException("unreachable")
        }
    }

    private fun adapter(servers: MutableList<Server> = mutableListOf(server), pingsAllowed: Boolean = true) =
        object : ServerAdapter<Server>(
            context, R.layout.server_list_row, servers, scope, pinger, dispatcher, { pingsAllowed },
        ) {
            override val popupMenuResource: Int get() = R.menu.popup_favourite_server
            override fun onPopupItemClick(server: Server, menuItem: MenuItem) = false
        }

    @Test
    fun aServerIsPingedOnceNoMatterHowOftenItsRowIsBound() {
        val adapter = adapter()

        repeat(3) { adapter.getView(0, null, parent) }
        scope.advanceUntilIdle()
        repeat(3) { adapter.getView(0, null, parent) }
        scope.advanceUntilIdle()

        assertThat(sockets.get()).isEqualTo(1)
    }

    @Test
    fun aFailedPingShowsTheServerOffline() {
        val adapter = adapter()
        adapter.getView(0, null, parent)
        scope.advanceUntilIdle()

        val row = adapter.getView(0, null, parent)

        val status = row.findViewById<TextView>(R.id.server_row_version_status)
        assertThat(status.visibility).isEqualTo(View.VISIBLE)
        assertThat(status.text.toString()).isEqualTo(context.getString(R.string.offline))
    }

    @Test
    fun noPingRunsOnceTheOwningScopeIsCancelled() {
        val adapter = adapter()
        scope.cancel()

        adapter.getView(0, null, parent)
        scope.advanceUntilIdle()

        assertThat(sockets.get()).isEqualTo(0)
    }

    @Test
    fun withPingsDisallowedNothingIsSentAndTheStatusIsADash() {
        val adapter = adapter(pingsAllowed = false)

        val row = adapter.getView(0, null, parent)
        scope.advanceUntilIdle()

        assertThat(sockets.get()).isEqualTo(0)
        val status = row.findViewById<TextView>(R.id.server_row_version_status)
        assertThat(status.visibility).isEqualTo(View.VISIBLE)
        assertThat(status.text.toString()).isEqualTo("\u2013")
        assertThat(row.findViewById<View>(R.id.server_row_ping_progress).visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun byDefaultTorDisallowsPings() {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().putBoolean("useTor", true).commit()
        val adapter = object : ServerAdapter<Server>(
            context, R.layout.server_list_row, mutableListOf(server), scope, pinger, dispatcher,
        ) {
            override val popupMenuResource: Int get() = R.menu.popup_favourite_server
            override fun onPopupItemClick(server: Server, menuItem: MenuItem) = false
        }

        adapter.getView(0, null, parent)
        scope.advanceUntilIdle()

        assertThat(sockets.get()).isEqualTo(0)
    }
}
