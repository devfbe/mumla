package se.lublin.mumla.servers

import android.os.Bundle
import android.os.Looper
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.db.DatabaseProvider
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.db.PublicServer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class PublicServerListFragmentTest {

    class HostActivity : AppCompatActivity(), FavouriteServerListFragment.ServerConnectHandler, DatabaseProvider {
        private val db: MumlaDatabase = mockk(relaxed = true)

        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Mumla)
            super.onCreate(savedInstanceState)
        }

        override fun connectToServer(server: Server) = Unit
        override fun connectToPublicServer(server: PublicServer) = Unit
        override fun getDatabase(): MumlaDatabase = db
    }

    private val downloads = AtomicInteger()
    private val activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(activity)

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
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
        shadowOf(Looper.getMainLooper()).idle()
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
}
