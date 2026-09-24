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
import io.mockk.mockk
import se.lublin.mumla.R
import se.lublin.mumla.channel.ChatTargetProvider
import se.lublin.mumla.db.DatabaseProvider
import se.lublin.mumla.db.MumlaDatabase
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.util.HumlaServiceFragment
import se.lublin.mumla.util.HumlaServiceProvider

/** An activity in the app theme, for fragments that need nothing from their host. */
open class ThemedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Mumla)
        super.onCreate(savedInstanceState)
    }
}

/** A host that hands out a service without a database, for fragments that must refuse it. */
open class ServiceOnlyHostActivity : ThemedActivity(), HumlaServiceProvider {
    // Not `var service`: its generated getter would clash with the interface's getService().
    private var bound: IMumlaService? = null
    val serviceFragments = mutableListOf<HumlaServiceFragment>()

    fun bind(service: IMumlaService?) {
        bound = service
    }

    override fun getService(): IMumlaService? = bound
    override fun addServiceFragment(fragment: HumlaServiceFragment) {
        serviceFragments += fragment
    }

    override fun removeServiceFragment(fragment: HumlaServiceFragment) {
        serviceFragments -= fragment
    }
}

/** The usual host of the service-backed fragments: a bindable service and a relaxed database mock. */
class ServiceHostActivity : ServiceOnlyHostActivity(), DatabaseProvider {
    private val db: MumlaDatabase = mockk(relaxed = true)
    var menuInvalidations = 0
        private set

    override fun getDatabase(): MumlaDatabase = db

    override fun invalidateOptionsMenu() {
        menuInvalidations++
        super.invalidateOptionsMenu()
    }
}

/**
 * The parent fragment that holds the chat target for its children. It shows an empty container
 * with id [CONTAINER_ID] to add children into.
 */
class ChatTargetParentFragment : Fragment(), ChatTargetProvider {
    var target: ChatTargetProvider.ChatTarget? = null
    val listeners = mutableListOf<ChatTargetProvider.OnChatTargetSelectedListener>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(requireContext()).also { it.id = CONTAINER_ID }

    override fun getChatTarget(): ChatTargetProvider.ChatTarget? = target
    override fun setChatTarget(target: ChatTargetProvider.ChatTarget?) {
        this.target = target
    }

    override fun registerChatTargetListener(listener: ChatTargetProvider.OnChatTargetSelectedListener) {
        listeners += listener
    }

    override fun unregisterChatTargetListener(listener: ChatTargetProvider.OnChatTargetSelectedListener) {
        listeners -= listener
    }

    companion object {
        const val CONTAINER_ID = 0x0f0f0f
    }
}
