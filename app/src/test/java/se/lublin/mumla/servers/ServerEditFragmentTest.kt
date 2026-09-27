package se.lublin.mumla.servers

import android.content.DialogInterface
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.textfield.TextInputLayout
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.servers.ServerEditFragment.Action
import se.lublin.mumla.servers.ServerEditFragment.Mode
import se.lublin.mumla.testing.ThemedActivity

@RunWith(RobolectricTestRunner::class)
class ServerEditFragmentTest {
    private val controller = Robolectric.buildActivity(ThemedActivity::class.java).setup()
    private val activity get() = controller.get()
    private val results = mutableListOf<ServerEditFragment.Result>()

    private fun listen() {
        val fragments = activity.supportFragmentManager
        fragments.setFragmentResultListener(ServerEditFragment.REQUEST_KEY, activity) { _, bundle ->
            results += ServerEditFragment.Result.from(bundle)
        }
    }

    private fun show(server: Server?, mode: Mode): AlertDialog {
        listen()
        ServerEditFragment.newInstance(server, mode).show(activity.supportFragmentManager, TAG)
        idleMainLooper()
        return shownDialog()
    }

    private fun shownDialog(): AlertDialog =
        (activity.supportFragmentManager.findFragmentByTag(TAG) as ServerEditFragment).requireDialog() as AlertDialog

    private fun AlertDialog.field(id: Int): EditText = findViewById(id)!!

    private fun AlertDialog.error(layoutId: Int): CharSequence? = findViewById<TextInputLayout>(layoutId)!!.error

    private fun AlertDialog.title(): String =
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)!!.text.toString()

    private fun AlertDialog.buttonText(button: Int): String? =
        getButton(button).takeIf { it.visibility == View.VISIBLE }?.text?.toString()

    private fun AlertDialog.click(button: Int) {
        getButton(button).performClick()
        idleMainLooper()
    }

    private fun string(id: Int) = activity.getString(id)

    @Test
    fun `each mode names its purpose in the title and offers its actions`() {
        fun check(mode: Mode, title: Int, primary: Int, secondary: Int?) {
            val dialog = show(null, mode)
            assertThat(dialog.title()).isEqualTo(string(title))
            assertThat(dialog.buttonText(DialogInterface.BUTTON_POSITIVE)).isEqualTo(string(primary))
            assertThat(dialog.buttonText(DialogInterface.BUTTON_NEUTRAL)).isEqualTo(secondary?.let(::string))
            assertThat(dialog.buttonText(DialogInterface.BUTTON_NEGATIVE)).isEqualTo(string(android.R.string.cancel))
            dialog.dismiss()
            idleMainLooper()
        }
        check(Mode.ADD, R.string.server_add, R.string.save, R.string.server_connect_only)
        check(Mode.EDIT, R.string.edit_server, R.string.save, null)
        check(Mode.CONNECT, R.string.connect, R.string.connect, null)
        check(Mode.LINK, R.string.connect, R.string.server_save_and_connect, R.string.server_connect_only)
    }

    @Test
    fun `only a plain connect hides the label`() {
        assertThat(show(null, Mode.CONNECT).findViewById<View>(R.id.server_edit_name_layout)!!.visibility)
            .isEqualTo(View.GONE)
        assertThat(show(null, Mode.LINK).findViewById<View>(R.id.server_edit_name_layout)!!.visibility)
            .isEqualTo(View.VISIBLE)
    }

    @Test
    fun `an edited server is saved with its id`() {
        val dialog = show(Server(7, "old", "old.example", 1234, "me", "pw"), Mode.EDIT)
        assertThat(dialog.field(R.id.server_edit_port).text.toString()).isEqualTo("1234")

        dialog.field(R.id.server_edit_host).setText(" new.example ")
        dialog.field(R.id.server_edit_port).setText("")
        dialog.click(DialogInterface.BUTTON_POSITIVE)

        val result = results.single()
        assertThat(result.action).isEqualTo(Action.EDIT)
        assertThat(result.server.id).isEqualTo(7)
        assertThat(result.server.host).isEqualTo("new.example")
        assertThat(result.server.port).isEqualTo(0)
        assertThat(result.server.username).isEqualTo("me")
        assertThat(dialog.isShowing).isFalse()
    }

    @Test
    fun `a new server is saved, or connected to only`() {
        show(null, Mode.ADD).apply {
            field(R.id.server_edit_host).setText("host.example")
            click(DialogInterface.BUTTON_POSITIVE)
        }
        show(null, Mode.ADD).apply {
            field(R.id.server_edit_host).setText("other.example")
            click(DialogInterface.BUTTON_NEUTRAL)
        }

        assertThat(results.map { it.action to it.server.host })
            .containsExactly(Action.ADD to "host.example", Action.CONNECT to "other.example").inOrder()
        assertThat(results.map { it.server.id }).containsExactly(Server.NOT_SAVED, Server.NOT_SAVED)
    }

    @Test
    fun `a linked server is saved and connected to, or connected to only`() {
        val linked = Server(Server.NOT_SAVED, "", "link.example", 64738, "", "")
        show(linked, Mode.LINK).click(DialogInterface.BUTTON_POSITIVE)
        show(linked, Mode.LINK).click(DialogInterface.BUTTON_NEUTRAL)

        assertThat(results.map { it.action }).containsExactly(Action.ADD_AND_CONNECT, Action.CONNECT).inOrder()
        assertThat(results.map { it.server.host }).containsExactly("link.example", "link.example")
    }

    @Test
    fun `an empty username falls back to the default one`() {
        val dialog = show(null, Mode.ADD)
        dialog.field(R.id.server_edit_host).setText("host.example")
        dialog.click(DialogInterface.BUTTON_POSITIVE)

        assertThat(results.single().server.username).isEqualTo(Settings.getInstance(activity).defaultUsername)
    }

    @Test
    fun `an empty address shows its error on the field`() {
        val dialog = show(null, Mode.CONNECT)
        dialog.field(R.id.server_edit_host).setText("  ")
        dialog.click(DialogInterface.BUTTON_POSITIVE)

        assertThat(dialog.error(R.id.server_edit_host_layout)).isEqualTo(string(R.string.invalid_host))
        assertThat(dialog.error(R.id.server_edit_port_layout)).isNull()
        assertThat(results).isEmpty()
        assertThat(dialog.isShowing).isTrue()

        dialog.field(R.id.server_edit_host).setText("host.example")
        assertThat(dialog.error(R.id.server_edit_host_layout)).isNull()
    }

    @Test
    fun `a port out of range or not a number shows its error on the field`() {
        val dialog = show(null, Mode.ADD)
        dialog.field(R.id.server_edit_host).setText("host.example")
        for (port in listOf("70000", "0", "12a")) {
            dialog.field(R.id.server_edit_port).setText(port)
            dialog.click(DialogInterface.BUTTON_NEUTRAL)
            assertThat(dialog.error(R.id.server_edit_port_layout)).isEqualTo(string(R.string.invalid_port_range))
        }

        assertThat(dialog.error(R.id.server_edit_host_layout)).isNull()
        assertThat(results).isEmpty()
        assertThat(dialog.isShowing).isTrue()
    }

    @Test
    fun `the password field can be revealed`() {
        val dialog = show(null, Mode.ADD)
        assertThat(dialog.findViewById<TextInputLayout>(R.id.server_edit_password_layout)!!.endIconMode)
            .isEqualTo(TextInputLayout.END_ICON_PASSWORD_TOGGLE)
    }

    @Test
    fun `the entered text survives a rotation`() {
        show(null, Mode.ADD).field(R.id.server_edit_host).setText("typed.example")

        controller.recreate()
        idleMainLooper()
        listen()
        val dialog = shownDialog()
        assertThat(dialog.field(R.id.server_edit_host).text.toString()).isEqualTo("typed.example")
        assertThat(dialog.title()).isEqualTo(string(R.string.server_add))

        dialog.click(DialogInterface.BUTTON_POSITIVE)
        assertThat(results.single().server.host).isEqualTo("typed.example")
    }

    private companion object {
        const val TAG = "edit"
    }
}
