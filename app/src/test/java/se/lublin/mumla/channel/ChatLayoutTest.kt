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

/**
 * Claims about `fragment_chat.xml` that hold without a fragment: what the fragment's
 * `findViewById` calls are allowed to assume, and one claim about the platform that the layout
 * used to get wrong.
 */
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

    /** Both compose-row buttons are icon-only, so a screen reader has nothing else to read out. */
    @Test
    fun bothComposeButtonsAreLabelledForAccessibility() {
        assertThat(view.findViewById<View>(R.id.chatImageSend).contentDescription.toString())
            .isEqualTo(activity.getString(R.string.image_confirm_send))
        assertThat(view.findViewById<View>(R.id.chatTextSend).contentDescription.toString())
            .isEqualTo(activity.getString(R.string.send_message))
    }

    /**
     * The premise of a line this layout no longer contains. Both `ImageButton`s carried
     * `android:enabled="false"` from 2022 on, and it never did anything: `android:enabled` is a
     * **`TextView`** attribute, not a `View` one — `com.android.internal.R.styleable` declares 122
     * `View_*` entries and `TextView_enabled`, and no `View_enabled`, so `ImageView`'s constructor
     * chain never reads it. Measured before the attributes were removed: both buttons inflated
     * with `isEnabled == true` while the XML said `false`, so nothing about the shipped app
     * changed when they went.
     *
     * Deleting a dead line leaves nothing that can go red, which is why the premise is written out
     * here instead: if a platform release ever starts honouring the attribute on a plain `View`,
     * this turns red rather than silently giving the removed lines a job again.
     */
    @Test
    fun androidEnabledIsATextViewAttributeAndAnImageButtonIgnoresIt() {
        val attrs = Robolectric.buildAttributeSet().addAttribute(android.R.attr.enabled, "false").build()
        assertThat(ImageButton(activity, attrs).isEnabled).isTrue()
        assertThat(TextView(activity, attrs).isEnabled).isFalse()
    }
}
