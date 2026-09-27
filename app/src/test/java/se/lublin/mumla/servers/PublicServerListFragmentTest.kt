package se.lublin.mumla.servers

import android.content.DialogInterface
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import se.lublin.humla.model.Server
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.db.PublicServer
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.drainMainUntil
import se.lublin.mumla.testing.host
import se.lublin.mumla.testing.laidOutRows
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class PublicServerListFragmentTest {

    private val downloads = AtomicInteger()
    private val activity = Robolectric.buildActivity(ServiceHostActivity::class.java).setup().get()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(activity)

    private fun server(name: String, country: String?, countryCode: String? = null) =
        PublicServer(name, null, country, countryCode, "$name.example", 64738, null, null)

    private val servers = listOf(server("Bravo", "Sweden"), server("alpha", "Norway"), server("Charlie", null))

    /** Answers pings with the users and latency the server's name maps to; 0 users and 10 ms otherwise. */
    private fun pinger(users: Map<String, Int> = emptyMap(), latency: Map<String, Int> = emptyMap()) =
        mockk<ServerPinger> {
            coEvery { ping(any()) } answers {
                val server = firstArg<Server>()
                val reply = ByteBuffer.allocate(24)
                    .putInt(0, MIN_MATCH_VERSION).putInt(12, users[server.name] ?: 0).putInt(16, 50).array()
                ServerInfoResponse(server, reply, latency[server.name] ?: 10)
            }
        }

    /** Hosts the fragment with a download that counts its attempts and yields [downloaded] in turn. */
    private fun showFragment(
        vararg downloaded: List<PublicServer>?,
        pinger: ServerPinger = pinger(),
    ): PublicServerListFragment {
        val fragment = PublicServerListFragment()
        fragment.fetcher = mockk {
            coEvery { fetch() } answers { downloaded[minOf(downloads.getAndIncrement(), downloaded.lastIndex)] }
        }
        fragment.pinger = pinger
        activity.host(fragment)
        idleMainLooper()
        return fragment
    }

    private fun showList(pinger: ServerPinger = pinger()): PublicServerListFragment =
        showFragment(servers, pinger = pinger).also { fragment ->
            drainMainUntil(description = "the list") { fragment.shownNames().size == servers.size }
        }

    private fun PublicServerListFragment.grid() = requireView().findViewById<RecyclerView>(R.id.server_list_grid)

    private fun PublicServerListFragment.shownNames() =
        (grid().adapter as PublicServerAdapter).currentList.map { it.name }

    private fun PublicServerListFragment.visible(id: Int) = requireView().findViewById<View>(id).visibility == View.VISIBLE

    private fun PublicServerListFragment.chips() =
        requireView().findViewById<ChipGroup>(R.id.server_country_chips).children.map { it as Chip }.toList()

    private fun PublicServerListFragment.chip(text: String) = chips().single { it.text == text }

    private fun preparedMenu(fragment: PublicServerListFragment) =
        PopupMenu(activity, fragment.requireView()).menu.also { menu ->
            activity.menuInflater.inflate(R.menu.fragment_public_server_list, menu)
            fragment.onPrepareMenu(menu)
        }

    private fun latestDialog() = ShadowDialog.getLatestDialog() as AlertDialog

    @Test
    fun whileLoadingOnlyTheProgressShows() {
        val fragment = PublicServerListFragment()
        fragment.fetcher = mockk { coEvery { fetch() } coAnswers { awaitCancellation() } }
        activity.host(fragment)
        idleMainLooper()

        assertThat(fragment.visible(R.id.serverProgress)).isTrue()
        assertThat(fragment.visible(R.id.server_list_error)).isFalse()
        assertThat(fragment.visible(R.id.server_list_empty)).isFalse()
        assertThat(fragment.visible(R.id.server_list_controls)).isFalse()
    }

    @Test
    fun aFailedDownloadShowsAnErrorWithARetryThatDownloadsAgain() {
        val fragment = showFragment(null, servers)
        drainMainUntil(description = "the error") { fragment.visible(R.id.server_list_error) }
        assertThat(fragment.visible(R.id.serverProgress)).isFalse()
        assertThat(fragment.visible(R.id.server_list_controls)).isFalse()
        val heading = fragment.requireView().findViewById<TextView>(R.id.server_list_error_title)
        assertThat(ViewCompat.isAccessibilityHeading(heading)).isTrue()
        val retry = fragment.requireView().findViewById<Button>(R.id.server_list_retry)
        assertThat(retry.minimumHeight).isAtLeast((48 * activity.resources.displayMetrics.density).toInt())

        retry.performClick()
        drainMainUntil(description = "the list") { fragment.shownNames().size == servers.size }

        assertThat(downloads.get()).isEqualTo(2)
        assertThat(fragment.visible(R.id.server_list_error)).isFalse()
        assertThat(fragment.visible(R.id.server_list_controls)).isTrue()
    }

    @Test
    fun theDownloadedServersAreListedByUsersByDefault() {
        val fragment = showList()

        assertThat(fragment.shownNames()).containsExactly("Bravo", "alpha", "Charlie").inOrder()
        assertThat(fragment.visible(R.id.server_list_empty)).isFalse()
        val sort = fragment.requireView().findViewById<MaterialButtonToggleGroup>(R.id.server_sort)
        assertThat(sort.checkedButtonId).isEqualTo(R.id.server_sort_users)
    }

    @Test
    fun typingAQueryFiltersAndNoMatchShowsTheEmptyState() {
        val fragment = showList()
        val search = fragment.requireView().findViewById<EditText>(R.id.server_search)

        search.setText("ALPHA")
        drainMainUntil(description = "the filtered list") { fragment.shownNames() == listOf("alpha") }

        search.setText("nothing like it")
        drainMainUntil(description = "the empty state") { fragment.visible(R.id.server_list_empty) }
        assertThat(fragment.shownNames()).isEmpty()
    }

    @Test
    fun countryChipsFilterAndAllClearsThem() {
        val fragment = showList()
        assertThat(fragment.chips().map { it.text.toString() })
            .containsExactly(activity.getString(R.string.public_server_country_all), "Norway", "Sweden").inOrder()
        assertThat(fragment.chips().all { it.isCheckable }).isTrue()
        assertThat(fragment.chip(activity.getString(R.string.public_server_country_all)).isChecked).isTrue()

        fragment.chip("Norway").performClick()
        drainMainUntil(description = "Norway only") { fragment.shownNames() == listOf("alpha") }
        assertThat(fragment.chip("Norway").isChecked).isTrue()
        assertThat(fragment.chip(activity.getString(R.string.public_server_country_all)).isChecked).isFalse()

        fragment.chip("Sweden").performClick()
        drainMainUntil(description = "both countries") { fragment.shownNames().size == 2 }

        val all = fragment.chip(activity.getString(R.string.public_server_country_all))
        all.performClick()
        drainMainUntil(description = "all servers") { fragment.shownNames().size == servers.size }
        assertThat(all.isChecked).isTrue()
        assertThat(fragment.chip("Norway").isChecked).isFalse()

        all.performClick()
        assertThat(all.isChecked).isTrue()
    }

    @Test
    fun theSortControlReordersByPing() {
        val fragment = showList(
            pinger(
                users = mapOf("Bravo" to 1, "alpha" to 9, "Charlie" to 5),
                latency = mapOf("Bravo" to 5, "alpha" to 80, "Charlie" to 20),
            ),
        )
        fragment.grid().laidOutRows()
        drainMainUntil(description = "by users") { fragment.shownNames() == listOf("alpha", "Charlie", "Bravo") }

        fragment.requireView().findViewById<Button>(R.id.server_sort_ping).performClick()

        drainMainUntil(description = "by ping") { fragment.shownNames() == listOf("Bravo", "Charlie", "alpha") }
    }

    @Test
    fun theResultCountIsAnnouncedPolitelyOnceTypingPauses() {
        val fragment = showList()
        val count = fragment.requireView().findViewById<TextView>(R.id.server_list_count)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertThat(count.accessibilityLiveRegion).isEqualTo(View.ACCESSIBILITY_LIVE_REGION_POLITE)
        assertThat(count.text.toString()).isEqualTo("3 servers")

        fragment.requireView().findViewById<EditText>(R.id.server_search).setText("alpha")
        drainMainUntil(description = "the filtered list") { fragment.shownNames() == listOf("alpha") }
        assertThat(count.text.toString()).isEqualTo("3 servers")

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertThat(count.text.toString()).isEqualTo("1 server")
    }

    @Test
    fun onlyFindEmptyServerIsLeftInTheMenu() {
        val menu = preparedMenu(showList())

        assertThat(menu.size()).isEqualTo(1)
        val item = menu.findItem(R.id.menu_match_server)
        assertThat(item.isVisible).isTrue()
        assertThat(item.title.toString()).isEqualTo("Find empty server")
    }

    @Test
    fun findEmptyServerOffersAnEmptyServerInTheUsersCountry() {
        val fragment = showFragment(
            listOf(server("Busy", "United States", "US"), server("Empty", "United States", "US")),
            pinger = pinger(users = mapOf("Busy" to 3)),
        )
        drainMainUntil(description = "the list") { fragment.shownNames().size == 2 }

        fragment.onMenuItemSelected(preparedMenu(fragment).findItem(R.id.menu_match_server))
        latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).performClick()

        drainMainUntil(description = "the match") {
            ShadowDialog.getLatestDialog()?.findViewById<TextView>(android.R.id.message)?.text?.contains("Empty") == true
        }
        assertThat(latestDialog().getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
            .isEqualTo(activity.getString(R.string.connect))
    }

    @Test
    fun overTorTheListIsNotDownloadedAndANoticeExplainsWhy() {
        prefs.edit().putBoolean("useTor", true).commit()

        val fragment = showFragment(servers)

        assertThat(downloads.get()).isEqualTo(0)
        val notice = fragment.requireView().findViewById<TextView>(R.id.server_list_tor_notice)
        assertThat(notice.visibility).isEqualTo(View.VISIBLE)
        assertThat(notice.text.toString()).isEqualTo(activity.getString(R.string.public_server_list_tor_disabled))
        assertThat(fragment.visible(R.id.serverProgress)).isFalse()
        assertThat(fragment.visible(R.id.server_list_controls)).isFalse()
        assertThat(fragment.visible(R.id.server_list_error)).isFalse()
        assertThat(preparedMenu(fragment).findItem(R.id.menu_match_server).isVisible).isFalse()
    }
}
