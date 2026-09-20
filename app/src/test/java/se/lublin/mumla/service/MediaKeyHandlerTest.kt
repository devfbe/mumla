package se.lublin.mumla.service

import android.content.Context
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.Constants
import se.lublin.mumla.Settings

@RunWith(RobolectricTestRunner::class)
class MediaKeyHandlerTest {
    private class FakeTarget(
        override var isConnected: Boolean = true,
        override var transmitMode: Int = Constants.TRANSMIT_PUSH_TO_TALK,
    ) : MediaKeyTarget {
        // A mutable `var isTalking` here would generate a JVM `setTalking(boolean)` accessor that
        // clashes with the interface's own `fun setTalking`; back it with a private field instead.
        private var talking: Boolean = false
        override val isTalking: Boolean
            get() = talking
        var muteToggles = 0

        override fun setTalking(talking: Boolean) {
            this.talking = talking
        }

        override fun toggleSelfMute() {
            muteToggles++
        }
    }

    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var target: FakeTarget
    private lateinit var handler: MediaKeyHandler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        settings = Settings.getInstance(context)
        target = FakeTarget()
        handler = MediaKeyHandler(settings, target)
    }

    private fun setAction(prefValue: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(Settings.PREF_MEDIA_BUTTON_ACTION, prefValue).commit()
    }

    private fun press(keyCode: Int): Boolean {
        val down = handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        val up = handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return down && up
    }

    @Test
    fun headsetHookTogglesTalkingInPushToTalkMode() {
        assertThat(press(KeyEvent.KEYCODE_HEADSETHOOK)).isTrue()
        assertThat(target.isTalking).isTrue()

        press(KeyEvent.KEYCODE_HEADSETHOOK)
        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    @Test
    fun playPauseTogglesMuteInVoiceActivityMode() {
        target.transmitMode = Constants.TRANSMIT_VOICE_ACTIVITY

        assertThat(press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)).isTrue()

        assertThat(target.muteToggles).isEqualTo(1)
        assertThat(target.isTalking).isFalse()
    }

    @Test
    fun avrcpPlayAndPauseKeysBothToggle() {
        target.transmitMode = Constants.TRANSMIT_CONTINUOUS

        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)

        assertThat(target.muteToggles).isEqualTo(2)
    }

    @Test
    fun actionFiresOnceOnKeyUpOnly() {
        handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
        assertThat(target.isTalking).isFalse()

        // a held key repeats ACTION_DOWN; repeats must not toggle
        handler.onKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 3))
        assertThat(target.isTalking).isFalse()

        handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
        assertThat(target.isTalking).isTrue()
    }

    @Test
    fun repeatedKeyUpIsConsumedWithoutToggling() {
        val repeatedUp = KeyEvent(0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 1)

        assertThat(handler.onKeyEvent(repeatedUp)).isTrue()

        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    @Test
    fun canceledKeyUpAfterLongPressIsConsumedWithoutToggling() {
        // The system consumed the long press (voice assistant); the UP arrives with FLAG_CANCELED.
        val canceledUp = KeyEvent(
            0L, 0L, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0, 0, 0, 0, KeyEvent.FLAG_CANCELED,
        )

        assertThat(handler.onKeyEvent(canceledUp)).isTrue()

        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    @Test
    fun muteSettingAlwaysTogglesMuteEvenInPushToTalkMode() {
        setAction("mute")

        press(KeyEvent.KEYCODE_HEADSETHOOK)

        assertThat(target.muteToggles).isEqualTo(1)
        assertThat(target.isTalking).isFalse()
    }

    @Test
    fun noneSettingDoesNotConsumeTheKey() {
        setAction("none")

        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))).isFalse()
        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    /**
     * "Off is off" must hold for every event of a handled key, not only the UP that would act.
     * The system stops dispatching a gesture once any part of it is claimed, so consuming the DOWN
     * silently breaks the media button of whatever app the user actually meant to control.
     */
    @Test
    fun noneSettingDoesNotConsumeTheKeyDownEither() {
        setAction("none")

        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))).isFalse()
        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    @Test
    fun ignoredWhileDisconnected() {
        target.isConnected = false

        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    /** Same contract while disconnected: Mumla has no business claiming any part of the gesture. */
    @Test
    fun ignoredWhileDisconnectedForKeyDownToo() {
        target.isConnected = false

        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))).isFalse()
        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    @Test
    fun unrelatedMediaKeysAreNotConsumed() {
        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))).isFalse()
        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))).isFalse()
        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }
}
