package se.lublin.mumla.preference

import android.app.Application
import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.R

/**
 * "Connect via Tor" is only usable with Orbot installed, and the screen greys it out when it is
 * not. The line predates this task and survived its mutation when the screen was converted to
 * Kotlin: nothing in the suite ever wrote the one input it reads, so `isEnabled = true` was
 * indistinguishable from the real call. Both sides of that input are written here.
 *
 * `OrbotHelper.isOrbotInstalled` asks the package manager for `org.torproject.android` and reads
 * a `NameNotFoundException` as "no" (verified against the disassembled netcipher 2.1.0), so an
 * installed package is the whole of the "yes" side.
 */
@RunWith(RobolectricTestRunner::class)
class GeneralSettingsOrbotTest {

    class HostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Mumla)
            super.onCreate(savedInstanceState)
        }
    }

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
    }

    private fun torPreference(): Preference {
        val fragment = GeneralSettingsFragment()
        val activity = Robolectric.buildActivity(HostActivity::class.java).setup().get()
        activity.supportFragmentManager.beginTransaction()
            .add(android.R.id.content, fragment)
            .commitNow()
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
