package se.lublin.mumla.servers

import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.preference.PreferenceManager
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.db.DatabaseProvider
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.PublicServer
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.drainMainUntil
import se.lublin.mumla.testing.idleMainLooper
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class PublicServerListFragmentTest {

    class HostActivity : ThemedActivity(), FavouriteServerListFragment.ServerConnectHandler, DatabaseProvider {
        private val db: MumlaDatabase = mockk(relaxed = true)

        override fun connectToServer(server: Server) = Unit
        override fun connectToPublicServer(server: PublicServer) = Unit
        override fun getDatabase(): MumlaDatabase = db
    }

    private val downloads = AtomicInteger()
    private val activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(activity)

    @After
    fun tearDown() {
    }

    /** Hosts the fragment with a download that counts its attempts and always fails. */
    private fun showFragment(): PublicServerListFragment {
        val fragment = PublicServerListFragment()
        fragment.fetcher = PublicServerFetcher {
            downloads.incrementAndGet()
            object : HttpURLConnection(URL("https://example.invalid/")) {
                override fun connect() = throw IOException("offline")
                override fun disconnect() = Unit
                override fun usingProxy() = false
            }
        }
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        idleMainLooper()
        return fragment
    }

    private fun preparedMenu(fragment: PublicServerListFragment) =
        PopupMenu(activity, fragment.requireView()).menu.also { menu ->
            activity.menuInflater.inflate(R.menu.fragment_public_server_list, menu)
            fragment.onPrepareMenu(menu)
        }

    @Test
    fun theMatchActionIsOfferedWithoutTor() {
        val menu = preparedMenu(showFragment())

        assertThat(menu.findItem(R.id.menu_match_server).isVisible).isTrue()
    }

    @Test
    fun theMatchActionIsHiddenOverTor() {
        prefs.edit().putBoolean("useTor", true).commit()

        val menu = preparedMenu(showFragment())

        assertThat(menu.findItem(R.id.menu_match_server).isVisible).isFalse()
    }

    @Test
    fun withoutTorTheListIsDownloaded() {
        val fragment = showFragment()
        drainMainUntil(description = "a download attempt") { downloads.get() > 0 }

        assertThat(downloads.get()).isEqualTo(1)
        assertThat(fragment.requireView().findViewById<View>(R.id.server_list_tor_notice).visibility)
            .isEqualTo(View.GONE)
    }

    @Test
    fun overTorTheListIsNotDownloadedAndANoticeExplainsWhy() {
        prefs.edit().putBoolean("useTor", true).commit()

        val view = showFragment().requireView()

        assertThat(downloads.get()).isEqualTo(0)
        val notice = view.findViewById<TextView>(R.id.server_list_tor_notice)
        assertThat(notice.visibility).isEqualTo(View.VISIBLE)
        assertThat(notice.text.toString()).isEqualTo(activity.getString(R.string.public_server_list_tor_disabled))
        assertThat(view.findViewById<View>(R.id.serverProgress).visibility).isEqualTo(View.GONE)
    }
}
