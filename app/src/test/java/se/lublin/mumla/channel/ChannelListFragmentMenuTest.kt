/*
 * Copyright (C) 2026 The Mumla contributors
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

package se.lublin.mumla.channel

import android.os.Bundle
import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.R
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.ChatTargetParentFragment
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.stubConnected

/** The channel list's own menu items; the audio chooser is the activity's. */
@RunWith(RobolectricTestRunner::class)
class ChannelListFragmentMenuTest {

    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var fragment: ChannelListFragment
    private val session = mockk<IHumlaSession>(relaxed = true)

    @Before
    fun setUp() {
        val service = mockk<IMumlaService>(relaxed = true).stubConnected(session)
        controller = Robolectric.buildActivity(ServiceHostActivity::class.java).setup()
        controller.get().bind(service)
        val parent = ChatTargetParentFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .add(parent, "parent").commitNow()
        fragment = ChannelListFragment()
        fragment.arguments = Bundle().apply { putBoolean("pinned", false) }
        parent.childFragmentManager.beginTransaction().add(fragment, "list").commitNow()
    }

    private val activity: ServiceHostActivity get() = controller.get()

    /** The real menu resource, inflated and prepared the way the action bar does it. */
    private fun prepared(): Menu {
        val menu = PopupMenu(activity, View(activity)).menu
        activity.menuInflater.inflate(R.menu.fragment_channel_list, menu)
        fragment.onPrepareMenu(menu)
        return menu
    }

    @Test
    fun theMuteAndDeafenItemsAreTitledWithWhatATapDoes() {
        val self = FakeUser(1)
        every { session.sessionUser } returns self
        prepared().let { menu ->
            assertThat(menu.findItem(R.id.menu_mute_button).title).isEqualTo(activity.getString(R.string.mute))
            assertThat(menu.findItem(R.id.menu_deafen_button).title).isEqualTo(activity.getString(R.string.deafen))
        }

        self.selfMuted = true
        self.selfDeafened = true

        prepared().let { menu ->
            assertThat(menu.findItem(R.id.menu_mute_button).title).isEqualTo(activity.getString(R.string.unmute))
            assertThat(menu.findItem(R.id.menu_deafen_button).title).isEqualTo(activity.getString(R.string.undeafen))
        }
    }

    @Test
    fun theAudioChooserIsNotTheListsAnyMore() {
        assertThat(prepared().findItem(R.id.menu_audio_device)).isNull()
    }
}
