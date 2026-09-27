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
package se.lublin.mumla

import android.content.Context
import android.content.res.XmlResourceParser
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import se.lublin.mumla.testing.SETTINGS_SCREENS

/**
 * The settings screens and [Settings] agree: every key a screen gives a default is a [Pref] with
 * that default, so the screen shows what the app reads while the user has not chosen.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsDefaultsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val prefs: Map<String, Pref<*>> = Settings::class.java.declaredFields
        .filter { Pref::class.java.isAssignableFrom(it.type) }
        .map { field -> field.isAccessible = true; field.get(null) as Pref<*> }
        .associateBy { it.key }

    /** The `android:defaultValue` of every keyed preference on [screen]. */
    private fun defaults(screen: Int): Map<String, String> {
        val found = mutableMapOf<String, String>()
        val parser = context.resources.getXml(screen)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) parser.storedDefault()?.let { found += it }
        }
        return found
    }

    /** The key and default of the element at [this], if it is a stored preference with a default. */
    private fun XmlResourceParser.storedDefault(): Pair<String, String>? {
        val key = getAttributeValue(ANDROID_NS, "key")
        val resId = getAttributeResourceValue(ANDROID_NS, "defaultValue", 0)
        val value = if (resId != 0) context.getString(resId) else getAttributeValue(ANDROID_NS, "defaultValue")
        val persistent = getAttributeBooleanValue(ANDROID_NS, "persistent", true)
        if (key == null || value == null || !persistent) return null
        return key to value
    }

    @Test
    fun everyScreenDefaultIsThePrefsDefault() {
        val mismatches = SETTINGS_SCREENS.flatMap { screen ->
            defaults(screen).mapNotNull { (key, value) ->
                if (key in UNSET_BY_DESIGN) return@mapNotNull null
                val pref = prefs[key] ?: return@mapNotNull "$key: no Pref"
                "$key: screen $value, Pref ${pref.default}".takeIf { pref.default?.toString() != value }
            }
        }
        assertThat(mismatches).isEmpty()
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        /** Unset, the method follows the legacy `preprocessor_enabled` switch, which is on by default. */
        val UNSET_BY_DESIGN = setOf(Settings.NOISE_SUPPRESSION_METHOD.key)
    }
}
