package se.lublin.mumla.channel

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.R

/** Claims about `fragment_chat.xml` that the fragment's `findViewById` calls rely on. */
@RunWith(RobolectricTestRunner::class)
class ChatLayoutTest {

    private val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val view: View = LayoutInflater.from(activity).inflate(R.layout.fragment_chat, null)

    @Test
    fun theChatListIsARecyclerView() {
        assertThat(view.findViewById<View>(R.id.chat_list)).isInstanceOf(RecyclerView::class.java)
    }

    /** The spinner belongs to the two long operations only; it must not greet the user. */
    @Test
    fun theImageProgressIndicatorStartsHidden() {
        assertThat(view.findViewById<View>(R.id.chat_image_progress).visibility).isEqualTo(View.GONE)
    }

    /** Both compose-row buttons are icon-only, so they need a content description. */
    @Test
    fun bothComposeButtonsAreLabelledForAccessibility() {
        assertThat(view.findViewById<View>(R.id.chatImageSend).contentDescription.toString())
            .isEqualTo(activity.getString(R.string.image_confirm_send))
        assertThat(view.findViewById<View>(R.id.chatTextSend).contentDescription.toString())
            .isEqualTo(activity.getString(R.string.send_message))
    }

    /**
     * `android:enabled` is a `TextView` attribute, not a `View` one, so it has no effect on an
     * `ImageButton`. If a platform release starts honouring it, this fails.
     */
    @Test
    fun androidEnabledIsATextViewAttributeAndAnImageButtonIgnoresIt() {
        val attrs = Robolectric.buildAttributeSet().addAttribute(android.R.attr.enabled, "false").build()
        assertThat(ImageButton(activity, attrs).isEnabled).isTrue()
        assertThat(TextView(activity, attrs).isEnabled).isFalse()
    }
}
