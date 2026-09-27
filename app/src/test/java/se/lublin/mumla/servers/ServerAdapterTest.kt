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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.cancel
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

    private inner class TestAdapter(pings: ServerPings) : ServerAdapter<Server>(pings, { clicked += it }, { it }) {
        override val rowLayout: Int get() = R.layout.server_list_row

        override val popupMenuResource: Int get() = R.menu.popup_favourite_server
        override fun onPopupItemClick(server: Server, menuItem: MenuItem) = false
    }

    private fun pings(allowed: Boolean = true) = ServerPings(scope, { allowed }, pinger, dispatcher)

    private fun adapter(servers: List<Server> = listOf(server), pings: ServerPings = pings()) =
        TestAdapter(pings).also { it.submitList(servers) }

    /** Binds the first row into a new holder, as the list does, and returns the row. */
    private fun ServerAdapter<Server>.bindFirst(): View {
        val holder = onCreateViewHolder(parent, 0)
        onBindViewHolder(holder, 0)
        return holder.itemView
    }

    @Test
    fun aServerIsPingedOnceNoMatterHowOftenItsRowIsBound() {
        val pings = pings()
        val adapter = adapter(pings = pings)

        repeat(3) { adapter.bindFirst() }
        scope.advanceUntilIdle()
        adapter.setReplies(pings.replies.value)
        repeat(3) { adapter.bindFirst() }
        scope.advanceUntilIdle()

        assertThat(sockets.get()).isEqualTo(1)
    }

    @Test
    fun aFailedPingShowsTheServerOffline() {
        val pings = pings()
        val adapter = adapter(pings = pings)
        adapter.bindFirst()
        scope.advanceUntilIdle()
        adapter.setReplies(pings.replies.value)

        val row = adapter.bindFirst()

        val status = row.findViewById<TextView>(R.id.server_row_version_status)
        assertThat(status.visibility).isEqualTo(View.VISIBLE)
        assertThat(status.text.toString()).isEqualTo(context.getString(R.string.offline))
    }

    @Test
    fun aReplyRepaintsTheCardsAtItsAddress() {
        val twin = Server(2, "twin", server.host, server.port, "other", "")
        val other = Server(3, "other", "192.0.2.1", 64738, "me", "")
        val pings = pings()
        val adapter = adapter(listOf(server, other, twin), pings)
        val repainted = mutableListOf<Int>()
        adapter.registerAdapterDataObserver(object : androidx.recyclerview.widget.RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
                repainted += positionStart
            }
        })

        pings.request(server)
        scope.advanceUntilIdle()
        adapter.setReplies(pings.replies.value)

        assertThat(repainted).containsExactly(0, 2)
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
        val adapter = adapter(pings = pings(allowed = false))

        val row = adapter.bindFirst()
        scope.advanceUntilIdle()

        assertThat(sockets.get()).isEqualTo(0)
        val status = row.findViewById<TextView>(R.id.server_row_version_status)
        assertThat(status.visibility).isEqualTo(View.VISIBLE)
        assertThat(status.text.toString()).isEqualTo("\u2013")
        assertThat(row.findViewById<View>(R.id.server_row_ping_progress).visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun aTappedCardGoesToTheClickHandler() {
        val adapter = adapter(pings = pings(allowed = false))

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
