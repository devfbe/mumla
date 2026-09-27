package se.lublin.mumla.channel.comment

import android.webkit.WebView
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import com.google.android.material.tabs.TabLayout
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.R
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.idleMainLooper
import se.lublin.mumla.testing.stubConnected
import se.lublin.mumla.testing.stubEvents

/** The comment dialog shows the rendered comment and its source on two tabs. */
@RunWith(RobolectricTestRunner::class)
class CommentTabsTest {
    private val session: IHumlaSession = mockk<IHumlaSession>(relaxed = true).stubConnected()
    private val activity = Robolectric.buildActivity(ServiceHostActivity::class.java).setup().get()
        .also { it.bind(session) }

    private fun show(editing: Boolean): AlertDialog {
        val fragment = UserCommentFragment.newInstance(7, "<b>hi</b>", editing)
        fragment.show(activity.supportFragmentManager, "comment")
        idleMainLooper()
        return fragment.requireDialog() as AlertDialog
    }

    private val AlertDialog.tabs get() = findViewById<TabLayout>(R.id.comment_tabs)!!
    private val AlertDialog.source get() = findViewById<EditText>(R.id.comment_edit)!!
    private val AlertDialog.rendered get() = findViewById<WebView>(R.id.comment_view)!!

    @Test
    fun `viewing opens on the rendered comment`() {
        val dialog = show(editing = false)

        assertThat(dialog.tabs.selectedTabPosition).isEqualTo(0)
        assertThat(dialog.rendered.isShown).isTrue()
        assertThat(dialog.source.isShown).isFalse()
        assertThat(dialog.tabs.getTabAt(1)!!.text).isEqualTo(activity.getString(R.string.comment_view_source))
    }

    @Test
    fun `editing opens on the source, and the rendered tab shows the edited source`() {
        val dialog = show(editing = true)

        assertThat(dialog.tabs.selectedTabPosition).isEqualTo(1)
        assertThat(dialog.source.isShown).isTrue()
        assertThat(dialog.source.text.toString()).isEqualTo("<b>hi</b>")

        dialog.source.setText("<i>bye</i>")
        dialog.tabs.selectTab(dialog.tabs.getTabAt(0))

        assertThat(dialog.rendered.isShown).isTrue()
        assertThat(shadowOf(dialog.rendered).lastLoadData.data).isEqualTo("<i>bye</i>")
    }
}
