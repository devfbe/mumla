package se.lublin.mumla

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Proves that Robolectric runs in the app module and that
 * `testOptions.unitTests.includeAndroidResources = true` is in effect: without merged
 * resources, resolving a string resource fails instead of returning its value.
 */
@RunWith(RobolectricTestRunner::class)
class AppResourcesSmokeTest {

    @Test
    fun `the app module's merged resources are available to unit tests`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // The applicationId varies per flavor (foss/goog: se.lublin.mumla, beta/donation:
        // se.lublin.mumla.beta/.donation), so this compares against the variant's own
        // BuildConfig rather than a literal, to hold for all of them.
        assertThat(context.packageName).isEqualTo(BuildConfig.APPLICATION_ID)
        // app_name is also overridden per flavor (beta: "Mumla Beta", donation: "Mumla*"), so
        // this keeps a real claim -- resolution returned actual branded text, not an empty
        // string or a resource-not-found failure -- without pinning a value that only two of
        // the four flavors share.
        assertThat(context.getString(R.string.app_name)).startsWith("Mumla")
    }
}
