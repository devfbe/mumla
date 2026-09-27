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
import android.widget.TextView
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
import se.lublin.mumla.servers.ServerEditFragment
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.installSession
import se.lublin.mumla.testing.message
import se.lublin.mumla.testing.stubState
import se.lublin.mumla.util.MumlaTrustStore
import java.io.ByteArrayOutputStream
import java.io.IOException

/** The connection error dialogs survive a configuration change, and so do the choices made in them. */
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
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session: IHumlaSession = mockk(relaxed = true) {
        every { targetServer } returns server
    }
    private val sessions = SessionManager.get(context)
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

    private fun errorDialog(): AlertDialog = checkNotNull(dialog("connection_error"))

    private fun AlertDialog.title(): String =
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    private fun AlertDialog.click(button: Int) {
        getButton(button).performClick()
        idleMainLooper()
    }

    @Test
    fun connectingAndReconnectingShowNoDialog() {
        show(SessionState.Connecting)
        assertThat(controller.get().dialogs.isShowing).isFalse()

        show(SessionState.ConnectionLost(5_000L, 1, DisconnectReason.Network("reset", IOException("boom"))))
        assertThat(controller.get().dialogs.isShowing).isFalse()

        show(SessionState.Reconnecting(null))
        assertThat(controller.get().supportFragmentManager.fragments.filterIsInstance<DialogFragment>()).isEmpty()
    }

    @Test
    fun aFailureShowsItsTitleWithRetryEditAndClose() {
        show(SessionState.Disconnected(DisconnectReason.Rejected(RejectType.SERVER_FULL, "50 users max")))

        val dialog = errorDialog()
        assertThat(dialog.title()).isEqualTo(context.getString(R.string.failure_server_full_title))
        assertThat(dialog.message()).contains(context.getString(R.string.failure_server_full))
        assertThat(dialog.message()).contains("50 users max")
        assertThat(dialog.getButton(DialogInterface.BUTTON_POSITIVE).text.toString())
            .isEqualTo(context.getString(R.string.retry))
        assertThat(dialog.getButton(DialogInterface.BUTTON_NEUTRAL).text.toString())
            .isEqualTo(context.getString(R.string.edit_server))
        assertThat(dialog.getButton(DialogInterface.BUTTON_NEGATIVE).text.toString())
            .isEqualTo(context.getString(R.string.close))
    }

    @Test
    fun aKickShowsItsReason() {
        show(SessionState.Disconnected(DisconnectReason.Kicked("spam", "admin", banned = true)))

        val dialog = errorDialog()
        assertThat(dialog.title()).isEqualTo(context.getString(R.string.failure_banned_title))
        assertThat(dialog.message()).contains("spam")
    }

    @Test
    fun retryConnectsToTheSameServerAgain() {
        show(SessionState.Disconnected(DisconnectReason.Network("refused", IOException("ECONNREFUSED"))))
        assertThat(errorDialog().title()).isEqualTo(context.getString(R.string.failure_unreachable_title))

        errorDialog().click(DialogInterface.BUTTON_POSITIVE)

        verify { listener.reconnect(server) }
        assertThat(sessions.errorShown.value).isTrue()
    }

    @Test
    fun editServerOpensTheEditorForTheServer() {
        show(SessionState.Disconnected(DisconnectReason.Rejected(RejectType.WRONG_VERSION, "")))

        errorDialog().click(DialogInterface.BUTTON_NEUTRAL)

        val editor = controller.get().supportFragmentManager.fragments.filterIsInstance<ServerEditFragment>()
        assertThat(editor).hasSize(1)
        assertThat(controller.get().dialogs.isShowing).isFalse()
        verify(exactly = 0) { listener.reconnect(any()) }
    }

    @Test
    fun aTakenNameEnteredAfterRotationRetriesWithIt() {
        show(SessionState.Disconnected(DisconnectReason.Rejected(RejectType.USERNAME_IN_USE, "")))
        errorDialog().findViewById<EditText>(R.id.connection_input)!!.setText("me2")

        recreate().dialogs.update()
        val dialog = errorDialog()
        assertThat(dialog.title()).isEqualTo(context.getString(R.string.failure_name_taken_title))
        dialog.click(DialogInterface.BUTTON_POSITIVE)

        verify { listener.reconnect(server.copy(username = "me2")) }
        verify(exactly = 0) { listener.reconnectWithPassword(any()) }
    }

    @Test
    fun aWrongPasswordEnteredAfterRotationReconnectsWithIt() {
        show(SessionState.Disconnected(DisconnectReason.Rejected(RejectType.WRONG_SERVER_PASSWORD, "")))
        errorDialog().findViewById<EditText>(R.id.connection_input)!!.setText("sec")

        recreate().dialogs.update()
        val dialog = errorDialog()
        assertThat(dialog.findViewById<EditText>(R.id.connection_input)!!.text.toString()).isEqualTo("sec")
        dialog.click(DialogInterface.BUTTON_POSITIVE)

        verify { listener.reconnectWithPassword(server.copy(password = "sec")) }
    }

    @Test
    fun aClosedErrorIsNotShownAgain() {
        show(SessionState.Disconnected(DisconnectReason.Failed("bad certificate", null)))
        assertThat(controller.get().dialogs.isShowing).isTrue()

        errorDialog().click(DialogInterface.BUTTON_NEGATIVE)
        controller.get().dialogs.update()

        assertThat(controller.get().dialogs.isShowing).isFalse()
        verify(exactly = 0) { listener.reconnect(any()) }
    }

    @Test
    fun connectingAgainDismissesTheShownError() {
        show(SessionState.Disconnected(DisconnectReason.Failed("bad certificate", null)))
        assertThat(controller.get().dialogs.isShowing).isTrue()

        show(SessionState.Connecting)

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
