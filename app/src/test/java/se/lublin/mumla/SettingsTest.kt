package se.lublin.mumla

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.TransmitMode

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val settings = Settings.getInstance(context)

    @Test
    fun `each input method maps to its transmit mode`() {
        val cases = mapOf(
            "voiceActivity" to TransmitMode.VOICE_ACTIVITY,
            "ptt" to TransmitMode.PUSH_TO_TALK,
            "continuous" to TransmitMode.CONTINUOUS,
        )
        for ((stored, mode) in cases) {
            prefs.edit().putString("audioInputMethod", stored).commit()
            assertWithMessage(stored).that(settings.transmitMode).isEqualTo(mode)
        }
    }

    @Test
    fun `an unknown stored input method falls back to voice activity`() {
        prefs.edit().putString("audioInputMethod", "handset").commit()
        assertThat(settings.inputMethod).isEqualTo("voiceActivity")
        assertThat(settings.transmitMode).isEqualTo(TransmitMode.VOICE_ACTIVITY)
    }

    @Test
    fun `setInputMethod rejects unknown values without writing them`() {
        assertThrows(RuntimeException::class.java) { settings.inputMethod = "handset" }
        assertThat(prefs.contains("audioInputMethod")).isFalse()
    }

    @Test
    fun `setInputMethod stores a valid value`() {
        settings.inputMethod = "ptt"
        assertThat(prefs.getString("audioInputMethod", null)).isEqualTo("ptt")
    }

    @Test
    fun `detection threshold is the stored percentage divided by 100`() {
        prefs.edit().putInt("vadThreshold", 35).commit()
        assertThat(settings.detectionThreshold).isWithin(1e-6f).of(0.35f)
    }

    @Test
    fun `detection threshold defaults to one half`() {
        assertThat(settings.detectionThreshold).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `amplitude boost is the stored percentage divided by 100`() {
        prefs.edit().putInt("inputVolume", 250).commit()
        assertThat(settings.amplitudeBoostMultiplier).isWithin(1e-6f).of(2.5f)
    }

    @Test
    fun `input sample rate and frames per packet are parsed from their string preferences`() {
        prefs.edit().putString("input_quality", "16000").putString("audio_per_packet", "6").commit()
        assertThat(settings.inputSampleRate).isEqualTo(16000)
        assertThat(settings.framesPerPacket).isEqualTo(6)
    }

    @Test
    fun `hot corner gravity maps each corner and is zero when disabled`() {
        val cases = mapOf(
            "none" to 0,
            "topLeft" to (Gravity.LEFT or Gravity.TOP),
            "topRight" to (Gravity.RIGHT or Gravity.TOP),
            "bottomLeft" to (Gravity.LEFT or Gravity.BOTTOM),
            "bottomRight" to (Gravity.RIGHT or Gravity.BOTTOM),
        )
        for ((stored, gravity) in cases) {
            prefs.edit().putString("hotCorner", stored).commit()
            assertThat(settings.hotCornerGravity).isEqualTo(gravity)
            assertThat(settings.isHotCornerEnabled).isEqualTo(stored != "none")
        }
    }

    @Test
    fun `certificate id below zero means no certificate is used`() {
        assertThat(settings.defaultCertificateId).isEqualTo(-1L)
        assertThat(settings.isUsingCertificate).isFalse()
        settings.defaultCertificateId = 3L
        assertThat(settings.defaultCertificateId).isEqualTo(3L)
        assertThat(settings.isUsingCertificate).isTrue()
        settings.disableCertificate()
        assertThat(settings.isUsingCertificate).isFalse()
    }

    @Test
    fun `deafened implies muted`() {
        settings.setMutedAndDeafened(false, true)
        assertThat(settings.isMuted).isTrue()
        assertThat(settings.isDeafened).isTrue()
        settings.setMutedAndDeafened(false, false)
        assertThat(settings.isMuted).isFalse()
    }

    @Test
    fun `news shown versions accumulate and skip empty entries`() {
        settings.addNewsShownVersions(mutableListOf("3.6.0", ""))
        settings.addNewsShownVersions(mutableListOf("3.7.0"))
        assertThat(settings.newsShownVersions).containsExactly("3.6.0", "3.7.0")
        settings.resetNewsShownVersion()
        assertThat(settings.newsShownVersions).isEmpty()
    }

    @Test
    fun `adding a news version does not mutate the set already handed out`() {
        settings.addNewsShownVersions(mutableListOf("3.6.0"))
        val handedOut = settings.newsShownVersions

        settings.addNewsShownVersions(mutableListOf("3.7.0"))

        // SharedPreferences.getStringSet documents that the returned set must not be modified;
        // addNewsShownVersions copies it before adding, and this pins that it keeps doing so.
        assertThat(handedOut).containsExactly("3.6.0")
    }

    @Test
    fun `push to talk button is shown unless hidden`() {
        assertThat(settings.isPushToTalkButtonShown).isTrue()
        prefs.edit().putBoolean("hidePtt", true).commit()
        assertThat(settings.isPushToTalkButtonShown).isFalse()
    }

    @Test
    fun `the control bar is at the bottom unless moved to the top`() {
        assertThat(settings.isControlBarAtBottom).isTrue()

        settings.isControlBarAtBottom = false

        assertThat(prefs.getString("controlBarPosition", null)).isEqualTo("top")
        assertThat(Settings.getInstance(context).isControlBarAtBottom).isFalse()
        prefs.edit().putString("controlBarPosition", "bottom").commit()
        assertThat(settings.isControlBarAtBottom).isTrue()
    }

    @Test
    fun `external images load by default but never while Tor is on`() {
        assertThat(settings.shouldLoadExternalImages).isTrue()
        prefs.edit().putBoolean("useTor", true).commit()
        assertThat(settings.shouldLoadExternalImages).isFalse()
        prefs.edit().putBoolean("useTor", false).putBoolean("load_images", false).commit()
        assertThat(settings.shouldLoadExternalImages).isFalse()
    }
}
