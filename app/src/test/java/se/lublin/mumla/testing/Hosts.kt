/*
 * Copyright (C) 2026 The Mumla authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.testing

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.RecyclerView
import io.mockk.mockk
import org.robolectric.Robolectric
import se.lublin.humla.IHumlaSession
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.channel.ChatViewModel
import se.lublin.mumla.db.MumlaDatabase

private const val TALL_LIST_WIDTH = 480
private const val TALL_LIST_HEIGHT = 4000

/** An activity in the app theme, for fragments that need nothing from their host. */
open class ThemedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Mumla)
        super.onCreate(savedInstanceState)
    }
}

/** Lays a list out tall enough for all its rows, e.g. the drawer's, and returns the rows. */
fun RecyclerView.laidOutRows(): List<View> {
    idleMainLooper()
    measure(0, 0)
    layout(0, 0, TALL_LIST_WIDTH, TALL_LIST_HEIGHT)
    return (0 until childCount).map(this::getChildAt)
}

/** Adds [fragment] to this activity's content view, now. */
fun <F : Fragment> FragmentActivity.host(fragment: F): F {
    supportFragmentManager.beginTransaction().add(android.R.id.content, fragment).commitNow()
    return fragment
}

/** Hosts [fragment] in a new [ThemedActivity]. */
fun <F : Fragment> hostInThemedActivity(fragment: F): F =
    Robolectric.buildActivity(ThemedActivity::class.java).setup().get().host(fragment)

/**
 * The usual host of the service-backed fragments: [bind] publishes a service to them, and a relaxed
 * mock is the app's database.
 */
class ServiceHostActivity : ThemedActivity() {
    val database: MumlaDatabase = installDatabase(mockk(relaxed = true))
    var menuInvalidations = 0
        private set

    /** Makes [session] the current session, as a connect does. */
    fun bind(session: IHumlaSession) = installSession(session)

    override fun invalidateOptionsMenu() {
        menuInvalidations++
        super.invalidateOptionsMenu()
    }
}

/**
 * The parent fragment that holds the chat target for its children, as `ChannelFragment` does. It
 * shows an empty container with id [CONTAINER_ID] to add children into.
 */
class ChatTargetParentFragment : Fragment() {
    val chat: ChatViewModel get() = ViewModelProvider(this, ChatViewModel.Factory)[ChatViewModel::class.java]

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).also { it.id = CONTAINER_ID }

    companion object {
        const val CONTAINER_ID = 0x0f0f0f
    }
}
