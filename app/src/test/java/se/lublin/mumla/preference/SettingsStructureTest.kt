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
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import se.lublin.mumla.R

/** How the settings screens are put together, read off their XML. */
@RunWith(RobolectricTestRunner::class)
class SettingsStructureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** The element names on [screen], in order. */
    private fun tags(screen: Int): List<String> = buildList {
        val parser = context.resources.getXml(screen)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) add(parser.name)
        }
    }

    @Test
    fun `on and off settings are switches, not check boxes`() {
        assertThat(SCREENS.flatMap(::tags).filter { it.endsWith("CheckBoxPreference") }).isEmpty()
    }

    private companion object {
        val SCREENS = listOf(
            R.xml.settings_general, R.xml.settings_audio, R.xml.settings_appearance,
            R.xml.settings_authentication, R.xml.settings_about,
        )
    }
}
