package se.lublin.mumla.service

import android.content.Context
import android.view.KeyEvent
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.audio.TransmitMode
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.FakeMediaKeyTarget
import se.lublin.mumla.testing.setMediaButtonAction

@RunWith(RobolectricTestRunner::class)
class MediaKeyHandlerTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val target = FakeMediaKeyTarget()
    private val handler = MediaKeyHandler(Settings.getInstance(context), target)

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
        target.transmitMode = TransmitMode.VOICE_ACTIVITY

        assertThat(press(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)).isTrue()

        assertThat(target.muteToggles).isEqualTo(1)
        assertThat(target.isTalking).isFalse()
    }

    @Test
    fun avrcpPlayAndPauseKeysBothToggle() {
        target.transmitMode = TransmitMode.CONTINUOUS

        press(KeyEvent.KEYCODE_MEDIA_PLAY)
        press(KeyEvent.KEYCODE_MEDIA_PAUSE)

        assertThat(target.muteToggles).isEqualTo(2)
    }

    /**
     * One press, one toggle, on the DOWN. The platform delivers a press as a DOWN/UP pair, so the
     * UP must be swallowed or every press would toggle twice.
     */
    @Test
    fun actionFiresOnceOnTheKeyDownOfAPress() {
        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK)))
            .isTrue()
        assertThat(target.isTalking).isTrue()

        // a held key repeats ACTION_DOWN; repeats must not toggle
        handler.onKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 3))
        assertThat(target.isTalking).isTrue()

        // the UP belonging to the same press is consumed, but does not toggle back
        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK)))
            .isTrue()
        assertThat(target.isTalking).isTrue()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    /**
     * A repeated or canceled DOWN is consumed but never acts. MediaSessionService already drops
     * canceled events, but this class is a pure function of the KeyEvent and the failure is an
     * unattended open microphone.
     */
    @Test
    fun aRepeatedOrCanceledKeyDownIsConsumedWithoutToggling() {
        val repeated = KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 1)
        val canceled = KeyEvent(
            0L, 0L, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0, 0, 0, 0,
            KeyEvent.FLAG_CANCELED,
        )
        for (event in listOf(repeated, canceled)) assertThat(handler.onKeyEvent(event)).isTrue()

        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    /**
     * A long press of the headset button belongs to the voice assistant, and the events that
     * reach us afterwards must not toggle anything.
     *
     * The sequence is the one AOSP's MediaSessionService delivers: it swallows the first DOWN and
     * the long-press DOWN (repeatCount 1, FLAG_LONG_PRESS), then dispatches the remaining repeats
     * and a final UP with repeatCount 0 and no FLAG_CANCELED.
     */
    @Test
    fun longPressTakenByTheVoiceAssistantDoesNotToggle() {
        val downTime = 1_000L
        // repeatCount 0 and 1 never reach the app; the service kept them for its own tracking.
        handler.onKeyEvent(
            KeyEvent(downTime, downTime + 600, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 2),
        )
        handler.onKeyEvent(
            KeyEvent(downTime, downTime + 900, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0),
        )

        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    @Test
    fun muteSettingAlwaysTogglesMuteEvenInPushToTalkMode() {
        setMediaButtonAction(context, "mute")

        press(KeyEvent.KEYCODE_HEADSETHOOK)

        assertThat(target.muteToggles).isEqualTo(1)
        assertThat(target.isTalking).isFalse()
    }

    private fun assertNeitherHalfOfAPressIsConsumed(keyCode: Int) {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertThat(handler.onKeyEvent(KeyEvent(action, keyCode))).isFalse()
        }
        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }

    /**
     * "Off is off" holds for every event of a handled key: the system stops dispatching a gesture
     * once any part of it is claimed, so consuming the DOWN breaks the media button for other apps.
     */
    @Test
    fun noneSettingConsumesNoKey() {
        setMediaButtonAction(context, "none")
        assertNeitherHalfOfAPressIsConsumed(KeyEvent.KEYCODE_HEADSETHOOK)
    }

    /** Same contract while disconnected: Mumla has no business claiming any part of the gesture. */
    @Test
    fun ignoredWhileDisconnected() {
        target.isConnected = false
        assertNeitherHalfOfAPressIsConsumed(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
    }

    @Test
    fun unrelatedMediaKeysAreNotConsumed() {
        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))).isFalse()
        assertThat(handler.onKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP))).isFalse()
        assertThat(target.isTalking).isFalse()
        assertThat(target.muteToggles).isEqualTo(0)
    }
}
