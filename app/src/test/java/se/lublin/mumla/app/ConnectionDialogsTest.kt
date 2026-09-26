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

import android.content.DialogInterface
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.HumlaService.ConnectionState
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.model.Server
import se.lublin.humla.net.HumlaCertificateGenerator
import se.lublin.humla.protobuf.Mumble
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.util.MumlaTrustStore
import java.io.ByteArrayOutputStream

/** The connection dialogs survive a configuration change, and so do the choices made in them. */
@RunWith(RobolectricTestRunner::class)
class ConnectionDialogsTest {

    class HostActivity : ThemedActivity() {
        lateinit var dialogs: ConnectionDialogs

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            dialogs = ConnectionDialogs(this, Settings.getInstance(this), listener) { service }
        }
    }

    private val server = Server(1, "Home", "example.org", 64738, "me", null)
    private val controller: ActivityController<HostActivity> =
        Robolectric.buildActivity(HostActivity::class.java).setup()

    private fun recreate(): HostActivity {
        controller.recreate()
        idleMainLooper()
        return controller.get()
    }

    private fun dialog(tag: String): AlertDialog? =
        (controller.get().supportFragmentManager.findFragmentByTag(tag) as? DialogFragment)?.dialog as? AlertDialog

    @Test
    fun theConnectingDialogSurvivesRotationAndItsCancelDisconnects() {
        every { service.connectionState } returns ConnectionState.CONNECTING
        controller.get().dialogs.update(service)

        recreate().dialogs.update(service)
        val dialog = checkNotNull(dialog("connecting"))
        assertThat(dialog.isShowing).isTrue()

        dialog.cancel()
        idleMainLooper()

        verify { service.disconnect() }
    }

    @Test
    fun aWrongPasswordEnteredAfterRotationReconnectsWithIt() {
        every { service.connectionState } returns ConnectionState.CONNECTION_LOST
        every { service.isErrorShown } returns false
        every { service.isReconnecting } returns false
        every { service.connectionError } returns
            HumlaException(Mumble.Reject.newBuilder().setType(Mumble.Reject.RejectType.WrongServerPW).build())
        controller.get().dialogs.update(service)
        checkNotNull(dialog("connection_error")).findViewById<EditText>(R.id.connection_password)!!.setText("sec")

        recreate().dialogs.update(service)
        val dialog = checkNotNull(dialog("connection_error"))
        assertThat(dialog.findViewById<EditText>(R.id.connection_password)!!.text.toString()).isEqualTo("sec")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMainLooper()

        assertThat(server.password).isEqualTo("sec")
        verify { listener.reconnectWithPassword(server) }
    }

    @Test
    fun aStateWithoutDialogDismissesTheShownOne() {
        every { service.connectionState } returns ConnectionState.CONNECTING
        controller.get().dialogs.update(service)
        assertThat(controller.get().dialogs.isShowing).isTrue()

        every { service.connectionState } returns ConnectionState.CONNECTED
        controller.get().dialogs.update(service)

        assertThat(controller.get().dialogs.isShowing).isFalse()
    }

    @Test
    fun aCertificateTrustedAfterRotationIsPinnedAndReconnects() {
        val certificate = HumlaCertificateGenerator.generateCertificate(ByteArrayOutputStream())
        controller.get().dialogs.showUntrustedCertificate(server, certificate, changed = true)
        idleMainLooper()

        val activity = recreate()
        val dialog = checkNotNull(dialog("certificate"))
        assertThat(dialog.isShowing).isTrue()
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idleMainLooper()

        assertThat(MumlaTrustStore.getTrustStore(activity).getCertificateAlias(certificate)).isNotNull()
        verify { listener.reconnect(match { it.host == server.host }) }
    }

    private companion object {
        val listener: ConnectionDialogs.Listener = mockk(relaxed = true)
        val service: IMumlaService = mockk(relaxed = true)
    }

    init {
        io.mockk.clearMocks(listener, service)
        every { service.targetServer } returns server
    }
}
