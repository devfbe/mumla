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
     * @return true if the event was consumed. Every other event of a handled key -- the matching
     * UP, repeats, canceled events -- is consumed without acting; the action fires on an
     * uncanceled ACTION_DOWN with repeatCount 0, of which the platform delivers exactly one per
     * press.
     *
     * Acting on the DOWN rather than the UP is measured, not assumed. AOSP API 36's
     * MediaSessionService tracks HEADSETHOOK and MEDIA_PLAY_PAUSE itself (they are its "voice
     * keys"): it swallows the real first DOWN, and on the release it synthesizes a DOWN from the
     * UP and dispatches DOWN then UP to the session, both with repeatCount 0. A press therefore
     * arrives here as a pair, and acting on either one alone fires once -- but only the DOWN
     * fires once *per press*. When the press instead becomes a long press, the service starts
     * the voice assistant and stops tracking, after which the remaining DOWN repeats
     * (repeatCount >= 2) and the final UP (repeatCount 0, *not* canceled -- canceled events are
     * dropped before dispatch) still reach us. Acting on the UP would open the microphone every
     * time the user summons the assistant; acting on the DOWN is silent there, as it must be.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in HANDLED_KEYS) return false
        if (!target.isConnected) return false
        val action = settings.getMediaButtonAction()
        if (action == MediaButtonAction.NONE) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
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
        /**
         * Each handled key toggles on its own; there is deliberately no debounce, neither across
         * keys nor within one key.
         *
         * One press can be delivered twice. androidx/media issue #3083 reports that since Media3
         * 1.9.2 a single press arrives as two [KeyEvent.KEYCODE_HEADSETHOOK] events, reproducible
         * with `adb shell input keyevent 79`, on among others a Pixel 9a (API 36) and a Samsung
         * SM-T220. That is repeated delivery of the *same* keycode; for the cross-key case (one
         * press producing both HEADSETHOOK and MEDIA_PLAY_PAUSE) no evidence was found. Whether it
         * reaches us is unverified: that report is about Media3's dispatch and we receive keys
         * through MediaSessionCompat, and it is the half of the question that needs hardware. The
         * other half -- which key action the platform delivers -- is measured and settled; see
         * [onKeyEvent].
         *
         * It is not debounced here regardless. A time-window filter cannot distinguish a duplicated
         * event from a deliberate quick double press, and the two want opposite outcomes: a real
         * double press is meant to end in the *other* state, so swallowing the second event leaves
         * the microphone open -- precisely the failure this feature must never cause. Dropping a
         * duplicate belongs at the delivery seam that can identify it as one, which is also the
         * only place it can be observed.
         */
        val HANDLED_KEYS: Set<Int> = setOf(
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
    }
}
