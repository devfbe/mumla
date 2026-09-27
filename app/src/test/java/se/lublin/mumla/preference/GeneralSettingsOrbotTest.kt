package se.lublin.mumla.preference

import android.app.Application
import android.content.pm.PackageInfo
import androidx.preference.Preference
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.testing.hostInThemedActivity

/** "Connect via Tor" is greyed out unless the Orbot package is installed. */
@RunWith(RobolectricTestRunner::class)
class GeneralSettingsOrbotTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun torPreference(): Preference {
        val fragment = hostInThemedActivity(GeneralSettingsFragment())
        return requireNotNull(fragment.preferenceScreen.findPreference("useTor")) {
            "no preference with key 'useTor' on the general settings screen"
        }
    }

    @Test
    fun theTorPreferenceIsGreyedOutWhenOrbotIsMissing() {
        assertThat(torPreference().isEnabled).isFalse()
    }

    @Test
    fun theTorPreferenceIsUsableOnceOrbotIsInstalled() {
        shadowOf(app.packageManager).installPackage(
            PackageInfo().apply { packageName = ORBOT_PACKAGE },
        )

        assertThat(torPreference().isEnabled).isTrue()
    }

    private companion object {
        const val ORBOT_PACKAGE = "org.torproject.android"
    }
}
