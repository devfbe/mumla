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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.TextView
import androidx.core.content.edit
import androidx.preference.ListPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.slider.Slider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.SETTINGS_SCREENS
import se.lublin.mumla.testing.openScreen
import se.lublin.mumla.testing.rowOf

/**
 * The inline sliders store the same ints the slider dialogs stored before them, under the same
 * keys and in the same units, so values saved by an older version read back unchanged.
 */
@RunWith(RobolectricTestRunner::class)
class SliderPreferenceTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val preferences = PreferenceManager.getDefaultSharedPreferences(app)
    private val settings get() = Settings.getInstance(app)
    private val activity by lazy { Robolectric.buildActivity(SettingsActivity::class.java).setup().get() }

    private fun PreferenceFragmentCompat.slider(key: String) = requireNotNull(findPreference<SliderPreference>(key))

    @Test
    fun `values the slider dialogs stored are shown and read back unchanged`() {
        preferences.edit {
            putInt(Settings.PTT_BUTTON_HEIGHT.key, 370)
            putInt(Settings.INPUT_QUALITY.key, 64000)
            putInt(Settings.VAD_HOLD_MS.key, 410)
            putInt(Settings.VAD_SENSITIVITY.key, 31)
        }

        val audio = activity.openScreen(AudioSettingsFragment::class.java)
        assertThat(audio.slider(Settings.INPUT_QUALITY.key).value).isEqualTo(64000)
        assertThat(audio.slider(Settings.INPUT_QUALITY.key).formatted(64000))
            .isEqualTo(app.getString(R.string.unitKilobitsPerSecond, 64))
        assertThat(audio.slider(Settings.VAD_HOLD_MS.key).value).isEqualTo(410)
        val sensitivity = audio.rowOf(Settings.VAD_SENSITIVITY.key).findViewById<TextView>(R.id.slider_value)
        assertThat(sensitivity.text.toString()).isEqualTo(app.getString(R.string.unitPercent, 31))

        assertThat(settings.pttButtonHeight).isEqualTo(370)
        assertThat(settings.inputQuality).isEqualTo(64000)
        assertThat(settings.vadConfig.holdTimeMs).isEqualTo(410L)
        assertThat(settings.vadConfig.snrFraction).isWithin(0.001f).of(0.31f)
    }

    @Test
    fun `a stored value off the steps or out of range is shown near it and kept until moved`() {
        preferences.edit { putInt(Settings.PTT_BUTTON_HEIGHT.key, 5000) }
        val controls = activity.openScreen(ControlsSettingsFragment::class.java)
        val slider = controls.rowOf(Settings.PTT_BUTTON_HEIGHT.key).findViewById<Slider>(R.id.slider)
        slider.draw(Canvas(Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888)))

        assertThat(slider.value).isEqualTo(1000f)
        assertThat(preferences.getInt(Settings.PTT_BUTTON_HEIGHT.key, 0)).isEqualTo(5000)
    }

    @Test
    fun `a stored value between two steps does not break the slider`() {
        preferences.edit { putInt(Settings.PTT_BUTTON_HEIGHT.key, 154) }
        val controls = activity.openScreen(ControlsSettingsFragment::class.java)
        val slider = controls.rowOf(Settings.PTT_BUTTON_HEIGHT.key).findViewById<Slider>(R.id.slider)
        slider.draw(Canvas(Bitmap.createBitmap(CANVAS_SIZE, CANVAS_SIZE, Bitmap.Config.ARGB_8888)))

        assertThat(slider.value).isEqualTo(150f)
        assertThat(settings.pttButtonHeight).isEqualTo(154)
    }

    @Test
    fun `every slider takes its unit from a string resource`() {
        for (screen in SETTINGS_SCREENS) {
            val parser = app.resources.getXml(screen)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                assertWithMessage("a literal suffix in ${parser.name}")
                    .that(parser.getAttributeValue(ANDROID_NS, "text")).isNull()
                if (parser.name.endsWith(SliderPreference::class.java.simpleName)) {
                    val key = parser.getAttributeValue(ANDROID_NS, "key")
                    assertWithMessage("the unit of $key")
                        .that(parser.getAttributeResourceValue(APP_NS, "valueFormat", 0)).isNotEqualTo(0)
                }
            }
        }
    }

    @Test
    fun `the packet lengths and sample rates are named with units from resources`() {
        val audio = activity.openScreen(AudioSettingsFragment::class.java)

        val packets = requireNotNull(audio.findPreference<ListPreference>(Settings.FRAMES_PER_PACKET.key))
        assertThat(packets.entries.map { it.toString() }).containsExactly(
            app.getString(R.string.unitMilliseconds, 10),
            app.getString(R.string.unitMilliseconds, 20),
            app.getString(R.string.unitMilliseconds, 40),
            app.getString(R.string.unitMilliseconds, 60),
        ).inOrder()
        val rates = requireNotNull(audio.findPreference<ListPreference>(Settings.INPUT_RATE.key))
        assertThat(rates.entries.first().toString()).startsWith(app.getString(R.string.unitHertz, 48000))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val APP_NS = "http://schemas.android.com/apk/res-auto"
        const val CANVAS_SIZE = 200
    }
}
