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
        assertThat(context.packageName).isEqualTo("se.lublin.mumla")
        assertThat(context.getString(R.string.app_name)).isEqualTo("Mumla")
    }
}
