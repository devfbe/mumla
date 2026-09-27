/*
 * Copyright (C) 2026 The Mumla Authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package se.lublin.mumla.preference

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import androidx.preference.Preference
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.ThemedActivity
import se.lublin.mumla.testing.host

/** The "Background connection" row shows and changes the battery optimisation exemption. */
@RunWith(RobolectricTestRunner::class)
class GeneralSettingsBatteryTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var controller: ActivityController<ThemedActivity>
    private lateinit var fragment: GeneralSettingsFragment

    private fun open() {
        controller = Robolectric.buildActivity(ThemedActivity::class.java).setup()
        fragment = controller.get().host(GeneralSettingsFragment())
    }

    private fun exempt(value: Boolean) {
        shadowOf(app.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(app.packageName, value)
    }

    private fun row(): Preference = requireNotNull(
        fragment.preferenceScreen.findPreference(Settings.PREF_BATTERY_OPTIMIZATION),
    ) { "no battery optimisation row on the general settings screen" }

    private fun started(): Intent? = shadowOf(controller.get()).nextStartedActivity

    @Test
    fun anOptimizedAppSaysSoAndOffersTheExemption() {
        exempt(false)
        open()

        assertThat(row().summary.toString()).isEqualTo(app.getString(R.string.battery_optimization_optimized))
        row().performClick()

        val intent = requireNotNull(started())
        assertThat(intent.action).isEqualTo(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertThat(intent.data.toString()).isEqualTo("package:${app.packageName}")
    }

    @Test
    fun anExemptAppIsUnrestrictedAndLeadsToTheSystemList() {
        exempt(true)
        open()

        assertThat(row().summary.toString()).isEqualTo(app.getString(R.string.battery_optimization_unrestricted))
        row().performClick()

        assertThat(requireNotNull(started()).action).isEqualTo(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    @Test
    fun theSummaryIsRefreshedOnReturn() {
        exempt(false)
        open()

        exempt(true)
        controller.pause().resume()

        assertThat(row().summary.toString()).isEqualTo(app.getString(R.string.battery_optimization_unrestricted))
    }

    @Test
    fun theSettingsListIsTheFallbackWithoutTheRequestDialog() {
        exempt(false)
        shadowOf(app).checkActivities(true)
        shadowOf(app.packageManager).apply {
            val settingsList = ComponentName("com.android.settings", "BatteryList")
            addActivityIfNotPresent(settingsList)
            val filter = IntentFilter(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            addIntentFilterForActivity(settingsList, filter)
        }
        open()

        row().performClick()

        assertThat(requireNotNull(started()).action).isEqualTo(ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }
}
