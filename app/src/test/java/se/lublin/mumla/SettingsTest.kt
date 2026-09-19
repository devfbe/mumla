package se.lublin.mumla

import android.content.Context
import android.content.SharedPreferences
import android.view.Gravity
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsTest {
    private lateinit var prefs: SharedPreferences
    private lateinit var settings: Settings

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().commit()
        settings = Settings.getInstance(context)
    }

    @Test
    fun `voice activity maps to humla transmit mode 0`() {
        prefs.edit().putString("audioInputMethod", "voiceActivity").commit()
        assertThat(settings.getHumlaInputMethod()).isEqualTo(0)
    }

    @Test
    fun `push to talk maps to humla transmit mode 1`() {
        prefs.edit().putString("audioInputMethod", "ptt").commit()
        assertThat(settings.getHumlaInputMethod()).isEqualTo(1)
    }

    @Test
    fun `continuous maps to humla transmit mode 2`() {
        prefs.edit().putString("audioInputMethod", "continuous").commit()
        assertThat(settings.getHumlaInputMethod()).isEqualTo(2)
    }

    @Test
    fun `an unknown stored input method falls back to voice activity`() {
        prefs.edit().putString("audioInputMethod", "handset").commit()
        assertThat(settings.getInputMethod()).isEqualTo("voiceActivity")
        assertThat(settings.getHumlaInputMethod()).isEqualTo(0)
    }

    @Test
    fun `setInputMethod rejects unknown values without writing them`() {
        assertThrows(RuntimeException::class.java) { settings.setInputMethod("handset") }
        assertThat(prefs.contains("audioInputMethod")).isFalse()
    }

    @Test
    fun `setInputMethod stores a valid value`() {
        settings.setInputMethod("ptt")
        assertThat(prefs.getString("audioInputMethod", null)).isEqualTo("ptt")
    }

    @Test
    fun `detection threshold is the stored percentage divided by 100`() {
        prefs.edit().putInt("vadThreshold", 35).commit()
        assertThat(settings.getDetectionThreshold()).isWithin(1e-6f).of(0.35f)
    }

    @Test
    fun `detection threshold defaults to one half`() {
        assertThat(settings.getDetectionThreshold()).isWithin(1e-6f).of(0.5f)
    }

    @Test
    fun `amplitude boost is the stored percentage divided by 100`() {
        prefs.edit().putInt("inputVolume", 250).commit()
        assertThat(settings.getAmplitudeBoostMultiplier()).isWithin(1e-6f).of(2.5f)
    }

    @Test
    fun `input sample rate and frames per packet are parsed from their string preferences`() {
        prefs.edit().putString("input_quality", "16000").putString("audio_per_packet", "6").commit()
        assertThat(settings.getInputSampleRate()).isEqualTo(16000)
        assertThat(settings.getFramesPerPacket()).isEqualTo(6)
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
            assertThat(settings.getHotCornerGravity()).isEqualTo(gravity)
            assertThat(settings.isHotCornerEnabled()).isEqualTo(stored != "none")
        }
    }

    @Test
    fun `certificate id below zero means no certificate is used`() {
        assertThat(settings.getDefaultCertificate()).isEqualTo(-1L)
        assertThat(settings.isUsingCertificate()).isFalse()
        settings.setDefaultCertificateId(3L)
        assertThat(settings.getDefaultCertificate()).isEqualTo(3L)
        assertThat(settings.isUsingCertificate()).isTrue()
        settings.disableCertificate()
        assertThat(settings.isUsingCertificate()).isFalse()
    }

    @Test
    fun `deafened implies muted`() {
        settings.setMutedAndDeafened(false, true)
        assertThat(settings.isMuted()).isTrue()
        assertThat(settings.isDeafened()).isTrue()
        settings.setMutedAndDeafened(false, false)
        assertThat(settings.isMuted()).isFalse()
    }

    @Test
    fun `news shown versions accumulate and skip empty entries`() {
        settings.addNewsShownVersions(mutableListOf("3.6.0", ""))
        settings.addNewsShownVersions(mutableListOf("3.7.0"))
        assertThat(settings.getNewsShownVersions()).containsExactly("3.6.0", "3.7.0")
        settings.resetNewsShownVersion()
        assertThat(settings.getNewsShownVersions()).isEmpty()
    }

    @Test
    fun `push to talk button is shown unless hidden`() {
        assertThat(settings.isPushToTalkButtonShown()).isTrue()
        prefs.edit().putBoolean("hidePtt", true).commit()
        assertThat(settings.isPushToTalkButtonShown()).isFalse()
    }
}
