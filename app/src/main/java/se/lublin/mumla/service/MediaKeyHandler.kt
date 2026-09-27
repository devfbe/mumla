package se.lublin.mumla.service

import android.view.KeyEvent
import se.lublin.humla.audio.TransmitMode
import se.lublin.mumla.MediaButtonAction
import se.lublin.mumla.Settings

/** Maps headset / AVRCP media key events to push-to-talk or mute toggles. */
class MediaKeyHandler(
    private val settings: Settings,
    private val target: MediaKeyTarget,
) {
    /**
     * @return true if the event was consumed. The action fires on an uncanceled ACTION_DOWN with
     * repeatCount 0; every other event of a handled key is consumed without acting.
     *
     * Why DOWN, not UP: MediaSessionService swallows the real DOWN of HEADSETHOOK/PLAY_PAUSE and on
     * release dispatches a synthesized DOWN+UP pair, so a short press arrives as one DOWN. A long
     * press starts the voice assistant, after which only repeats and the final (uncanceled) UP reach
     * us; acting on the UP would open the microphone every time the assistant is summoned.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in HANDLED_KEYS) return false
        if (!target.isConnected) return false
        val action = settings.mediaButtonAction
        if (action == MediaButtonAction.NONE) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount != 0) return true
        if (event.flags and KeyEvent.FLAG_CANCELED != 0) return true

        when (action) {
            MediaButtonAction.AUTO ->
                if (target.transmitMode == TransmitMode.PUSH_TO_TALK) {
                    target.setTalking(!target.isTalking)
                } else {
                    target.toggleSelfMute()
                }
            MediaButtonAction.MUTE -> target.toggleSelfMute()
            MediaButtonAction.NONE -> Unit // returned above
        }
        return true
    }

    companion object {
        /**
         * Each key toggles on its own, with no debounce: a time window cannot tell a duplicated
         * event (androidx/media#3083) from a deliberate double press, and swallowing the second
         * press of a real double press would leave the microphone open.
         */
        val HANDLED_KEYS: Set<Int> = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
