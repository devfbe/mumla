package se.lublin.mumla.servers

import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.R
import se.lublin.mumla.db.PublicServer

@RunWith(RobolectricTestRunner::class)
class PublicServerAdapterTest {
    private fun server(name: String, country: String?) =
        PublicServer(name, null, null, country, null, "$name.example", 64738, null, null)

    private val servers = listOf(server("Bravo", "Sweden"), server("alpha", "Norway"), server("Charlie", null))
    private val adapter = PublicServerAdapter(
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Mumla),
        servers,
        listener = { },
        scope = TestScope(),
        onServerClick = { },
    )

    private fun shown() = adapter.shownServers.map { it.name }

    @Test
    fun `filtering matches name and country, in list order, and forgets an earlier sort`() {
        adapter.sort(compareBy { it.name.lowercase() })
        adapter.filter("A", "")
        assertThat(shown()).containsExactly("Bravo", "alpha", "Charlie").inOrder()

        adapter.filter("", "NOR")
        assertThat(shown()).containsExactly("alpha")
    }

    @Test
    fun `sorting orders the shown servers`() {
        adapter.filter("R", "")
        adapter.sort(compareBy { it.name })

        assertThat(shown()).containsExactly("Bravo", "Charlie").inOrder()
    }
}
