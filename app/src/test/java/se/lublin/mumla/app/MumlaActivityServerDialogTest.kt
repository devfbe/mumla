/*
 * Copyright (C) 2026 The Mumla Authors
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
package se.lublin.mumla.app

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.Server
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.db.MumlaRepository
import se.lublin.mumla.servers.FavouriteServerListFragment
import se.lublin.mumla.servers.PublicServerListFragment
import se.lublin.mumla.servers.ServerEditFragment
import se.lublin.mumla.servers.ServerEditFragment.Action
import se.lublin.mumla.testing.installDatabase
import se.lublin.mumla.testing.launchMumlaActivity

/** How MumlaActivity opens the server dialog and carries out what the user chose in it. */
@RunWith(RobolectricTestRunner::class)
class MumlaActivityServerDialogTest {
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val server = Server(Server.NOT_SAVED, "Home", "home.example", 64738, "me", null)
    private val database get() = MumlaRepository.get(app).database

    private fun MumlaActivity.deliver(action: Action) {
        supportFragmentManager.setFragmentResult(
            ServerEditFragment.REQUEST_KEY,
            ServerEditFragment.Result(action, server).toBundle(),
        )
        idleMainLooper()
    }

    /** A connect starts by asking for the microphone, which the test never grants. */
    private fun MumlaActivity.connectStarted() =
        shadowOf(this).lastRequestedPermission?.requestedPermissions?.toList() == listOf(Manifest.permission.RECORD_AUDIO)

    private fun MumlaActivity.shownScreen() = supportFragmentManager.findFragmentById(R.id.content_frame)

    @Test
    fun connectOnlyConnectsWithoutSaving() {
        val activity = launchMumlaActivity()

        activity.deliver(Action.CONNECT)

        assertThat(activity.connectStarted()).isTrue()
        verify(exactly = 0) { database.addServer(any()) }
    }

    @Test
    fun saveSavesWithoutConnecting() {
        val activity = launchMumlaActivity()

        activity.deliver(Action.ADD)

        verify { database.addServer(server) }
        assertThat(activity.connectStarted()).isFalse()
        assertThat(activity.shownScreen()).isInstanceOf(FavouriteServerListFragment::class.java)
    }

    @Test
    fun saveAndConnectSavesThenConnects() {
        val activity = launchMumlaActivity()
        every { database.addServer(server) } returns server.copy(id = 9)

        activity.deliver(Action.ADD_AND_CONNECT)

        verify { database.addServer(server) }
        assertThat(activity.connectStarted()).isTrue()
    }

    @Test
    fun aMumbleLinkOpensTheDialogToSaveAndConnect() {
        val link = Uri.parse("mumble://me@link.example:1234/")
        val intent = Intent(Intent.ACTION_VIEW, link, app, MumlaActivity::class.java)
        installDatabase(mockk(relaxed = true))
        // Not launchMumlaActivity: it dismisses the latest dialog, here the server dialog.
        val activity = Robolectric.buildActivity(MumlaActivity::class.java, intent).setup().get()
        idleMainLooper()

        val editor = activity.supportFragmentManager.fragments.filterIsInstance<ServerEditFragment>().single()
        val dialog = editor.requireDialog() as AlertDialog
        assertThat(dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
            .isEqualTo(app.getString(R.string.server_save_and_connect))
        assertThat(dialog.findViewById<EditText>(R.id.server_edit_host)!!.text.toString())
            .isEqualTo("link.example")
    }

    @Test
    fun browsingThePublicServersShowsTheirList() {
        // No download in a test.
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("useTor", true).commit()
        val activity = launchMumlaActivity()
        assertThat(activity.shownScreen()).isInstanceOf(FavouriteServerListFragment::class.java)

        activity.shownScreen()!!.requireView().findViewById<View>(R.id.server_list_empty_browse).performClick()
        idleMainLooper()

        assertThat(activity.shownScreen()).isInstanceOf(PublicServerListFragment::class.java)
        assertThat(activity.supportActionBar!!.title).isEqualTo(app.getString(R.string.drawer_public))
    }
}
