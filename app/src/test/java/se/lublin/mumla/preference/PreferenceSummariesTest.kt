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
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser
import se.lublin.mumla.R

/** The settings screens' fixed summaries fit on about one line of a phone screen. */
@RunWith(RobolectricTestRunner::class)
class PreferenceSummariesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun summaries(xml: Int): List<Pair<String, String>> = buildList {
        val parser = context.resources.getXml(xml)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            val id = parser.getAttributeResourceValue(ANDROID_NS, "summary", 0)
            if (id != 0) add(context.resources.getResourceEntryName(id) to context.getString(id))
        }
    }

    @Test
    fun everySummaryIsShort() {
        val screens = listOf(
            R.xml.settings_general, R.xml.settings_audio, R.xml.settings_appearance,
            R.xml.settings_authentication, R.xml.settings_about,
        )
        val tooLong = screens.flatMap(::summaries)
            .filter { (name, text) -> name != LEGAL_NOTICE && text.length > MAX_LENGTH }
        assertWithMessage("too long").that(tooLong).isEmpty()
    }

    @Test
    fun theBluetoothSummaryDoesNotSendTheUserToAMenuThatIsGone() {
        assertWithMessage("bluetoothScoSum").that(context.getString(R.string.bluetoothScoSum))
            .doesNotContain("channel menu")
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        const val MAX_LENGTH = 60

        /** The licence notice in About is read in full, not scanned. */
        const val LEGAL_NOTICE = "copyright"
    }
}
