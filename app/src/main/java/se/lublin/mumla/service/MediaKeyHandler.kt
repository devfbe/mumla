package se.lublin.mumla.service

import android.view.KeyEvent
import se.lublin.humla.Constants
import se.lublin.mumla.MediaButtonAction
import se.lublin.mumla.Settings

/**
 * Maps headset / AVRCP media key events to push-to-talk or mute toggles (spec P1).
 * Pure logic; a later task (Task 4) feeds it the events of the active MediaSession.
 */
class MediaKeyHandler(
    private val settings: Settings,
    private val target: MediaKeyTarget,
) {
    /**
     * @return true if the event was consumed. DOWN and repeated DOWN events of a handled key are
     * consumed without acting; the action fires on an UP event with repeatCount 0 that the
     * system did not cancel (a canceled UP follows a long press the system consumed itself).
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in HANDLED_KEYS) return false
        if (!target.isConnected) return false
        val action = settings.getMediaButtonAction()
        if (action == MediaButtonAction.NONE) return false
        if (event.action != KeyEvent.ACTION_UP) return true
        if (event.repeatCount != 0) return true
        if (event.flags and KeyEvent.FLAG_CANCELED != 0) return true

        when (action) {
            MediaButtonAction.AUTO ->
                if (target.transmitMode == Constants.TRANSMIT_PUSH_TO_TALK) {
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
        val HANDLED_KEYS: Set<Int> = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
