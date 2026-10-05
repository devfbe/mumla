package se.lublin.mumla.chat

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ChatImageIntentsTest {
    private val saved = ImageGallerySaver.Saved(Uri.parse("content://media/external/images/media/7"), "image/png")

    @Test
    fun viewOpensTheSavedImageWithAReadGrant() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()

            activity.viewSaved(saved)

            val view = shadowOf(activity).nextStartedActivity
            assertThat(view.action).isEqualTo(Intent.ACTION_VIEW)
            assertThat(view.data).isEqualTo(saved.uri)
            assertThat(view.type).isEqualTo("image/png")
            assertThat(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        }
    }

    /** No app views images (a locked-down work profile): the image is saved, so "View" does nothing. */
    @Test
    fun withNoViewerInstalledViewIsANoOpAndNotACrash() {
        Robolectric.buildActivity(Activity::class.java).setup().use { controller ->
            val activity = controller.get()
            // Resolves started intents against the (empty) package manager, as a device does.
            shadowOf(activity.application).checkActivities(true)
            // The setup really fails the start, so the next line runs the catch.
            assertThrows(ActivityNotFoundException::class.java) { activity.startActivity(viewIntent(saved)) }

            activity.viewSaved(saved)
        }
    }
}
