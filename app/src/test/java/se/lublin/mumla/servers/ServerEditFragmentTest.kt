package se.lublin.mumla.servers

import android.content.DialogInterface
import android.os.Looper
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.model.Server
import se.lublin.mumla.R
import se.lublin.mumla.testing.ThemedActivity

@RunWith(RobolectricTestRunner::class)
class ServerEditFragmentTest {
    private val activity = Robolectric.buildActivity(ThemedActivity::class.java).setup().get()
    private val results = mutableListOf<ServerEditFragment.Result>()

    private fun show(server: Server?, action: ServerEditFragment.Action): AlertDialog {
        val manager = activity.supportFragmentManager
        manager.setFragmentResultListener(ServerEditFragment.REQUEST_KEY, activity) { _, bundle ->
            results += ServerEditFragment.Result.from(bundle)
        }
        val fragment = ServerEditFragment.newInstance(server, action, ignoreTitle = false)
        fragment.show(activity.supportFragmentManager, "edit")
        shadowOf(Looper.getMainLooper()).idle()
        return fragment.requireDialog() as AlertDialog
    }

    private fun AlertDialog.field(id: Int): EditText = findViewById(id)!!

    private fun AlertDialog.confirm() {
        getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `an edited server is delivered with its id and the action`() {
        val dialog = show(Server(7, "old", "old.example", 1234, "me", "pw"), ServerEditFragment.Action.EDIT)
        assertThat(dialog.field(R.id.server_edit_port).text.toString()).isEqualTo("1234")

        dialog.field(R.id.server_edit_host).setText(" new.example ")
        dialog.field(R.id.server_edit_port).setText("")
        dialog.confirm()

        val result = results.single()
        assertThat(result.action).isEqualTo(ServerEditFragment.Action.EDIT)
        assertThat(result.server.id).isEqualTo(7)
        assertThat(result.server.host).isEqualTo("new.example")
        assertThat(result.server.port).isEqualTo(0)
        assertThat(result.server.username).isEqualTo("me")
        assertThat(dialog.isShowing).isFalse()
    }

    @Test
    fun `an empty username falls back to the default one`() {
        val dialog = show(null, ServerEditFragment.Action.ADD)
        dialog.field(R.id.server_edit_host).setText("host.example")
        dialog.confirm()

        assertThat(results.single().server.id).isEqualTo(-1)
        assertThat(results.single().server.username).isEqualTo(dialog.field(R.id.server_edit_username).hint.toString())
    }

    @Test
    fun `an invalid entry keeps the dialog open and delivers nothing`() {
        val dialog = show(null, ServerEditFragment.Action.CONNECT)
        dialog.confirm()
        assertThat(dialog.field(R.id.server_edit_host).error).isNotNull()

        dialog.field(R.id.server_edit_host).setText("host.example")
        dialog.field(R.id.server_edit_port).setText("70000")
        dialog.confirm()
        assertThat(dialog.field(R.id.server_edit_port).error).isNotNull()

        assertThat(results).isEmpty()
        assertThat(dialog.isShowing).isTrue()
    }
}
