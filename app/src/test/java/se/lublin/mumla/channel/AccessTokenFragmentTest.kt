package se.lublin.mumla.channel

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.R
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.drainMainUntil
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubConnected

@RunWith(RobolectricTestRunner::class)
class AccessTokenFragmentTest {
    private val session = mockk<IHumlaSession>(relaxed = true)
    private val activity = Robolectric.buildActivity(ServiceHostActivity::class.java).setup().get().also {
        it.bind(mockk<IMumlaService>(relaxed = true).stubConnected(session))
    }
    private val fragment = AccessTokenFragment.newInstance(SERVER)

    private val list: RecyclerView get() = fragment.requireView().findViewById(R.id.tokenList)

    private fun shownTokens(): List<String> {
        list.measure(0, 0)
        list.layout(0, 0, 1000, 2000)
        return (0 until list.childCount).map {
            list.getChildAt(it).findViewById<TextView>(R.id.tokenItemTitle).text.toString()
        }
    }

    private fun show(stored: List<String>) {
        every { activity.database.getAccessTokens(SERVER) } returns stored
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
        idleMainLooper()
    }

    @Test
    fun `stored tokens are listed, and added and removed ones are stored and sent`() {
        show(listOf("red", "blue"))
        assertThat(shownTokens()).containsExactly("red", "blue").inOrder()

        fragment.requireView().findViewById<EditText>(R.id.tokenField).setText(" green ")
        fragment.requireView().findViewById<View>(R.id.tokenAddButton).performClick()
        drainMainUntil(description = "the added token") { shownTokens().size == 3 }
        verify { activity.database.addAccessToken(SERVER, "green") }
        verify { session.sendAccessTokens(listOf("red", "blue", "green")) }

        list.getChildAt(0).findViewById<View>(R.id.tokenItemDelete).performClick()
        drainMainUntil(description = "the removed token") { shownTokens() == listOf("blue", "green") }
        verify { activity.database.removeAccessToken(SERVER, "red") }
        verify { session.sendAccessTokens(listOf("blue", "green")) }
    }

    private companion object {
        const val SERVER = 4L
    }
}
