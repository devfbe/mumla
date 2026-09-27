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
import androidx.lifecycle.ViewModelProvider
import io.mockk.mockk
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.R
import se.lublin.mumla.channel.ChatTargetViewModel
import se.lublin.mumla.db.MumlaDatabase

/** An activity in the app theme, for fragments that need nothing from their host. */
open class ThemedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Mumla)
        super.onCreate(savedInstanceState)
    }
}

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
    val chatTargets: ChatTargetViewModel get() = ViewModelProvider(this)[ChatTargetViewModel::class.java]

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).also { it.id = CONTAINER_ID }

    companion object {
        const val CONTAINER_ID = 0x0f0f0f
    }
}
