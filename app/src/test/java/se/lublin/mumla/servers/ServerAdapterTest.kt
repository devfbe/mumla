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

    private val clicked = mutableListOf<Server>()

    private open inner class TestAdapter(pingsAllowed: (() -> Boolean)?) : ServerAdapter<Server>(
        context, scope, { clicked += it }, pinger, dispatcher,
        pingsAllowed ?: { !se.lublin.mumla.Settings.getInstance(context).isTorEnabled },
    ) {
        override val rowLayout: Int get() = R.layout.server_list_row

        override val popupMenuResource: Int get() = R.menu.popup_favourite_server
        override fun onPopupItemClick(server: Server, menuItem: MenuItem) = false
    }

    private fun adapter(servers: List<Server> = listOf(server), pingsAllowed: Boolean = true) =
        TestAdapter { pingsAllowed }.also { it.submitList(servers) }

    /** Binds the first row into a new holder, as the list does, and returns the row. */
    private fun ServerAdapter<Server>.bindFirst(): View {
        val holder = onCreateViewHolder(parent, 0)
        onBindViewHolder(holder, 0)
        return holder.itemView
    }

    @Test
    fun aServerIsPingedOnceNoMatterHowOftenItsRowIsBound() {
        val adapter = adapter()

        repeat(3) { adapter.bindFirst() }
        scope.advanceUntilIdle()
        repeat(3) { adapter.bindFirst() }
        scope.advanceUntilIdle()

        assertThat(sockets.get()).isEqualTo(1)
    }

    @Test
    fun aFailedPingShowsTheServerOffline() {
        val adapter = adapter()
        adapter.bindFirst()
        scope.advanceUntilIdle()

        val row = adapter.bindFirst()

        val status = row.findViewById<TextView>(R.id.server_row_version_status)
        assertThat(status.visibility).isEqualTo(View.VISIBLE)
        assertThat(status.text.toString()).isEqualTo(context.getString(R.string.offline))
    }

    @Test
    fun noPingRunsOnceTheOwningScopeIsCancelled() {
        val adapter = adapter()
        scope.cancel()

        adapter.bindFirst()
        scope.advanceUntilIdle()

        assertThat(sockets.get()).isEqualTo(0)
    }

    @Test
    fun withPingsDisallowedNothingIsSentAndTheStatusIsADash() {
        val adapter = adapter(pingsAllowed = false)

        val row = adapter.bindFirst()
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
        val adapter = TestAdapter(pingsAllowed = null).also { it.submitList(listOf(server)) }

        adapter.bindFirst()
        scope.advanceUntilIdle()

        assertThat(sockets.get()).isEqualTo(0)
    }

    @Test
    fun aTappedCardGoesToTheClickHandler() {
        val adapter = adapter(pingsAllowed = false)

        adapter.bindFirst().performClick()

        assertThat(clicked).containsExactly(server)
    }

    @Test
    fun serversAtOneAddressShareOnePing() {
        val twin = Server(2, "twin", server.host, server.port, "other", "")
        val adapter = adapter(listOf(server, twin))

        adapter.bindFirst()
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 1)
        scope.advanceUntilIdle()

        assertThat(sockets.get()).isEqualTo(1)
    }
}
