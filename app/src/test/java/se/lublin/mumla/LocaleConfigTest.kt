package se.lublin.mumla

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.xmlpull.v1.XmlPullParser

/** The system's per-app language list and the in-app language picker offer the same languages. */
@RunWith(RobolectricTestRunner::class)
class LocaleConfigTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun localeConfig(): List<String> {
        val parser = context.resources.getXml(R.xml.local_config)
        val locales = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                locales += parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")
            }
        }
        return locales
    }

    @Test
    fun wellTranslatedLanguagesAreOffered() {
        assertThat(localeConfig()).containsAtLeast("da", "kab", "zh-TW", "zh-CN", "en-US")
    }

    @Test
    fun thePickerOffersExactlyTheSystemList() {
        val picker = context.resources.getStringArray(R.array.languageValues).toList()

        assertThat(picker).containsExactlyElementsIn(localeConfig().map { if (it == "en-US") "en" else it })
    }
}
