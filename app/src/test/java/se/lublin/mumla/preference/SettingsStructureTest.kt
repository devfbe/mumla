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

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import se.lublin.mumla.Pref
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.SETTINGS_SCREENS
import se.lublin.mumla.testing.currentScreen
import se.lublin.mumla.testing.openScreen

/** How the settings screens are put together: the index, where each setting lives, the controls. */
@RunWith(RobolectricTestRunner::class)
class SettingsStructureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Calls [visit] on every element of [screen], in order. */
    private fun elements(screen: Int, visit: (XmlResourceParser) -> Unit) {
        val parser = context.resources.getXml(screen)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) visit(parser)
        }
    }

    private fun keys(screen: Int): List<String> = buildList {
        elements(screen) { element ->
            val key = element.getAttributeValue(ANDROID_NS, "key") ?: element.getAttributeValue(APP_NS, "key")
            if (key != null) add(key)
        }
    }

    @Test
    fun `the index lists the seven screens in task order`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val index = activity.currentScreen().preferenceScreen

        val entries = (0 until index.preferenceCount).map { index.getPreference(it) }
        assertThat(entries.map { it.fragment }).containsExactly(
            AudioSettingsFragment::class.java.name,
            ControlsSettingsFragment::class.java.name,
            ChatSettingsFragment::class.java.name,
            ConnectionSettingsFragment::class.java.name,
            AuthenticationSettingsFragment::class.java.name,
            AppearanceSettingsFragment::class.java.name,
            AboutSettingsFragment::class.java.name,
        ).inOrder()
        assertThat(entries.map { it.title.toString() }).containsExactly(
            context.getString(R.string.speakingAndAudio),
            context.getString(R.string.controls),
            context.getString(R.string.chatAndNotifications),
            context.getString(R.string.connection),
            context.getString(R.string.accountAndCertificates),
            context.getString(R.string.appearance),
            context.getString(R.string.about),
        ).inOrder()
    }

    @Test
    fun `every setting with a control appears exactly once across the screens`() {
        val shown = SETTINGS_SCREENS.flatMap(::keys)
        val repeated = shown.groupBy { it }.filterValues { it.size > 1 }.keys
        assertWithMessage("keys on more than one row").that(repeated).isEmpty()

        val prefs = Settings::class.java.declaredFields
            .filter { Pref::class.java.isAssignableFrom(it.type) }
            .map { field -> field.isAccessible = true; (field.get(null) as Pref<*>).key }
        assertThat(prefs.filterNot { it in shown || it in WITHOUT_CONTROL }).isEmpty()
        // The exemptions cannot rot: none of them may have gained a control.
        assertThat(WITHOUT_CONTROL.keys.filter { it in shown }).isEmpty()
    }

    @Test
    fun `on and off settings are switches, not check boxes`() {
        val tags = SETTINGS_SCREENS.flatMap { screen -> buildList { elements(screen) { add(it.name) } } }
        assertThat(tags.filter { it.endsWith("CheckBoxPreference") }).isEmpty()
    }

    @Test
    fun `the advanced audio settings start collapsed`() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
        val audio = activity.openScreen(AudioSettingsFragment::class.java)

        val advanced = requireNotNull(audio.findPreference<PreferenceCategory>(KEY_ADVANCED))
        assertThat(advanced.initialExpandedChildrenCount).isEqualTo(0)
        val positions = audio.listView.adapter as PreferenceGroup.PreferencePositionCallback
        for (key in listOf(Settings.INPUT_QUALITY.key, Settings.VAD_HOLD_MS.key, Settings.ANDROID_AGC.key)) {
            assertWithMessage("$key is shown").that(positions.getPreferenceAdapterPosition(key)).isEqualTo(-1)
        }
        assertThat(positions.getPreferenceAdapterPosition(Settings.INPUT_METHOD.key)).isAtLeast(0)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val APP_NS = "http://schemas.android.com/apk/res-auto"
        const val KEY_ADVANCED = "advanced_audio"

        /** Stored state the app keeps for itself, with where it is set instead. */
        val WITHOUT_CONTROL = mapOf(
            "muted" to "the mute button",
            "deafened" to "the deafen button",
            "firstRun" to "set on first start",
            "certificateId" to "the certificate list, opened from Account & certificates",
            "newsShownVersions" to "the news dialog",
            "preprocessor_enabled" to "legacy; read as the noise suppression default",
            "battery_optimization_asked" to "the battery prompt",
            "microphone_permission_asked" to "the permission gate",
            "notification_permission_asked" to "the permission gate",
        )
    }
}
