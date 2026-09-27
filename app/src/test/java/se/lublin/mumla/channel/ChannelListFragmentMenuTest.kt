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

import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.R
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.addUnderChatParent
import se.lublin.mumla.testing.hostWith
import se.lublin.mumla.testing.stubConnected

/** The channel list's own menu items; the audio chooser is the activity's. */
@RunWith(RobolectricTestRunner::class)
class ChannelListFragmentMenuTest {

    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var fragment: ChannelListFragment
    private val session = mockk<IHumlaSession>(relaxed = true)

    @Before
    fun setUp() {
        session.stubConnected()
        controller = hostWith(session)
        fragment = ChannelListFragment.newInstance(pinned = false)
        controller.get().addUnderChatParent(fragment, "list")
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
    fun theAudioChooserIsNotTheListsAnyMore() {
        assertThat(prepared().findItem(R.id.menu_audio_device)).isNull()
    }

    /** They are the channel screen's, so the chat tab has them too. */
    @Test
    fun muteAndDeafenAreNotTheListsAnyMore() {
        assertThat(prepared().findItem(R.id.menu_mute_button)).isNull()
        assertThat(prepared().findItem(R.id.menu_deafen_button)).isNull()
    }
}
