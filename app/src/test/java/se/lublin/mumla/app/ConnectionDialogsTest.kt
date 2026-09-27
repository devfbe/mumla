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

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.Server
import se.lublin.humla.net.HumlaCertificateGenerator
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.RejectType
import se.lublin.humla.session.SessionState
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.message
import se.lublin.mumla.testing.stubState
import se.lublin.mumla.util.MumlaTrustStore
import java.io.ByteArrayOutputStream
import java.io.IOException

/** The connection dialogs survive a configuration change, and so do the choices made in them. */
@RunWith(RobolectricTestRunner::class)
class ConnectionDialogsTest {

    class HostActivity : ThemedActivity() {
        lateinit var dialogs: ConnectionDialogs

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            dialogs = ConnectionDialogs(this, Settings.getInstance(this), listener, SessionManager.get(this))
        }
    }

    private val server = Server(1, "Home", "example.org", 64738, "me", null)
    private val session: IHumlaSession = mockk(relaxed = true) {
        every { targetServer } returns server
    }
    private val sessions = SessionManager.get(ApplicationProvider.getApplicationContext<Context>())
    private val controller: ActivityController<HostActivity> =
        Robolectric.buildActivity(HostActivity::class.java).setup()

    init {
        io.mockk.clearMocks(listener)
        installSession(session)
    }

    private fun show(state: SessionState) {
        session.stubState(state)
        controller.get().dialogs.update()
    }

    private fun recreate(): HostActivity {
        controller.recreate()
        idleMainLooper()
        return controller.get()
    }

    private fun dialog(tag: String): AlertDialog? =
        (controller.get().supportFragmentManager.findFragmentByTag(tag) as? DialogFragment)?.dialog as? AlertDialog

    @Test
    fun theConnectingDialogSurvivesRotationAndItsCancelDisconnects() {
        show(SessionState.Connecting)

        recreate().dialogs.update()
        val dialog = checkNotNull(dialog("connecting"))
        assertThat(dialog.isShowing).isTrue()

        dialog.cancel()
        idleMainLooper()

        verify { session.disconnect() }
    }

    @Test
    fun aWrongPasswordEnteredAfterRotationReconnectsWithIt() {
        show(SessionState.Disconnected(DisconnectReason.Rejected(RejectType.WRONG_SERVER_PASSWORD, "")))
        checkNotNull(dialog("connection_error")).findViewById<EditText>(R.id.connection_password)!!.setText("sec")

        recreate().dialogs.update()
        val dialog = checkNotNull(dialog("connection_error"))
        assertThat(dialog.findViewById<EditText>(R.id.connection_password)!!.text.toString()).isEqualTo("sec")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMainLooper()

        verify { listener.reconnectWithPassword(server.copy(password = "sec")) }
    }

    /** A lost connection that is being retried says so, with the network's reason. */
    @Test
    fun aLostConnectionShowsTheReconnectingErrorUntilItIsCancelled() {
        show(SessionState.ConnectionLost(2_000L, 1, DisconnectReason.Network("reset", IOException("boom"))))

        val dialog = checkNotNull(dialog("connection_error"))
        val message = dialog.message()
        assertThat(message).contains("reset")
        assertThat(message).contains("boom")
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        idleMainLooper()

        verify { session.cancelReconnect() }
        assertThat(sessions.errorShown.value).isTrue()
    }

    @Test
    fun anAcknowledgedErrorIsNotShownAgain() {
        show(SessionState.Disconnected(DisconnectReason.Failed("bad certificate", null)))
        assertThat(controller.get().dialogs.isShowing).isTrue()

        sessions.markErrorShown()
        controller.get().dialogs.update()

        assertThat(controller.get().dialogs.isShowing).isFalse()
    }

    @Test
    fun aStateWithoutDialogDismissesTheShownOne() {
        show(SessionState.Connecting)
        assertThat(controller.get().dialogs.isShowing).isTrue()

        show(SessionState.Connected)

        assertThat(controller.get().dialogs.isShowing).isFalse()
    }

    /** The prompt comes from the state, once; after rotation the restored dialog pins and reconnects. */
    @Test
    fun aChangedCertificateIsOfferedOnceAndTrustedAfterRotation() {
        val certificate = HumlaCertificateGenerator.generateCertificate(ByteArrayOutputStream())
        show(SessionState.Disconnected(DisconnectReason.TlsCertificateChanged(listOf(certificate))))
        idleMainLooper()
        assertThat(sessions.errorShown.value).isTrue()

        val activity = recreate()
        activity.dialogs.update()
        val dialog = checkNotNull(dialog("certificate"))
        assertThat(dialog.isShowing).isTrue()
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        idleMainLooper()

        assertThat(MumlaTrustStore.getTrustStore(activity).getCertificateAlias(certificate)).isNotNull()
        verify { listener.reconnect(match { it.host == server.host }) }
    }

    private companion object {
        val listener: ConnectionDialogs.Listener = mockk(relaxed = true)
    }
}
