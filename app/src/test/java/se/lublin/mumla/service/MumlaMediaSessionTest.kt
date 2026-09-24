package se.lublin.mumla.service

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.media.session.MediaSession
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.Constants
import se.lublin.humla.IHumlaService
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.idleMainLooper

@RunWith(RobolectricTestRunner::class)
class MumlaMediaSessionTest {
    private class FakeTarget : MediaKeyTarget {
        override var isConnected = true
        override var transmitMode = Constants.TRANSMIT_PUSH_TO_TALK
        // Backed by a private field on purpose: `override var isTalking` would generate a JVM
        // setTalking(Z)V that clashes with the interface's own setTalking.
        private var talking = false
        var stopTalkingCalls = 0
        override val isTalking get() = talking
        override fun setTalking(talking: Boolean) { this.talking = talking }
        override fun stopTalking() {
            stopTalkingCalls++
            // Same early exit as HumlaMediaKeyTarget, so the fake can't do more than production.
            if (!isConnected) return
            talking = false
        }
        override fun toggleSelfMute() = Unit
    }

    private lateinit var context: Context
    private lateinit var target: FakeTarget
    private lateinit var mediaSession: MumlaMediaSession

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        target = FakeTarget()
        mediaSession = MumlaMediaSession(context, target, Settings.getInstance(context))
    }

    private fun mediaButtonIntent(action: Int, keyCode: Int): Intent =
        Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, keyCode))

    private fun setAction(prefValue: String) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString(Settings.PREF_MEDIA_BUTTON_ACTION, prefValue).commit()
    }

    /** A service double that hands the registered observer back to the test. */
    private fun serviceCapturing(observer: (IHumlaObserver) -> Unit): IHumlaService =
        mockk {
            every { registerObserver(any()) } answers { observer(firstArg()) }
            every { unregisterObserver(any()) } returns Unit
        }

    @Test
    fun inactiveUntilActivated() {
        assertThat(mediaSession.isActive).isFalse()

        mediaSession.activate()

        assertThat(mediaSession.isActive).isTrue()
    }

    @Test
    fun activeSessionReportsPlayingSoMediaKeysAreRoutedToIt() {
        assertThat(mediaSession.playbackState).isNull()

        mediaSession.activate()

        val state = mediaSession.playbackState!!
        assertThat(state.state).isEqualTo(PlaybackStateCompat.STATE_PLAYING)
        // PLAY and PAUSE are advertised next to PLAY_PAUSE because AVRCP headsets that track
        // their own play state send those two keycodes instead of the combined one.
        assertThat(state.actions and PlaybackStateCompat.ACTION_PLAY_PAUSE).isNotEqualTo(0L)
        assertThat(state.actions and PlaybackStateCompat.ACTION_PLAY).isNotEqualTo(0L)
        assertThat(state.actions and PlaybackStateCompat.ACTION_PAUSE).isNotEqualTo(0L)
    }

    @Test
    fun noneSettingKeepsSessionInactiveOnConnect() {
        setAction("none")
        var observer: IHumlaObserver? = null
        mediaSession.attach(serviceCapturing { observer = it })

        observer!!.onConnected()

        assertThat(mediaSession.isActive).isFalse()
        assertThat(mediaSession.sessionToken).isNull()
    }

    @Test
    fun switchingToNoneWhileConnectedReleasesSessionAndBackRestoresIt() {
        var observer: IHumlaObserver? = null
        mediaSession.attach(serviceCapturing { observer = it })
        observer!!.onConnected()
        assertThat(mediaSession.isActive).isTrue()

        setAction("none")
        assertThat(mediaSession.isActive).isFalse()

        setAction("mute")
        assertThat(mediaSession.isActive).isTrue()
    }

    @Test
    fun deactivateReleasesTheSession() {
        mediaSession.activate()

        mediaSession.deactivate()

        assertThat(mediaSession.isActive).isFalse()
        assertThat(mediaSession.sessionToken).isNull()
    }

    @Test
    fun activateTwiceKeepsOneSession() {
        mediaSession.activate()
        val first = mediaSession.sessionToken

        mediaSession.activate()

        assertThat(mediaSession.sessionToken).isEqualTo(first)
    }

    @Test
    fun callbackForwardsMediaButtonToHandler() {
        mediaSession.activate()

        val handledDown = mediaSession.callback.onMediaButtonEvent(
            mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
        val handledUp = mediaSession.callback.onMediaButtonEvent(
            mediaButtonIntent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))

        assertThat(handledDown).isTrue()
        assertThat(handledUp).isTrue()
        assertThat(target.isTalking).isTrue()
    }

    @Test
    fun callbackIgnoresIntentWithoutKeyEvent() {
        assertThat(mediaSession.callback.onMediaButtonEvent(Intent(Intent.ACTION_MEDIA_BUTTON))).isFalse()
        assertThat(target.isTalking).isFalse()
    }

    /**
     * The androidx.media callback does not filter by key action, so one press (DOWN + UP) arrives
     * as two events and must toggle exactly once.
     */
    @Test
    fun theLibraryPassesBothKeyActionsThroughToTheHandler() {
        mediaSession.activate()
        val frameworkCallback = MediaSessionCompat.Callback::class.java
            .getDeclaredField("mCallbackFwk")
            .apply { isAccessible = true }
            .get(mediaSession.callback) as MediaSession.Callback

        val handledDown = frameworkCallback.onMediaButtonEvent(
            mediaButtonIntent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
        assertThat(target.isTalking).isTrue()
        val handledUp = frameworkCallback.onMediaButtonEvent(
            mediaButtonIntent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))

        assertThat(handledDown).isTrue()
        assertThat(handledUp).isTrue()
        assertThat(target.isTalking).isTrue()
    }

    @Test
    fun attachActivatesOnConnectedAndDeactivatesOnDisconnected() {
        var observer: IHumlaObserver? = null

        mediaSession.attach(serviceCapturing { observer = it })
        assertThat(observer).isNotNull()
        assertThat(mediaSession.isActive).isFalse()

        observer!!.onConnected()
        assertThat(mediaSession.isActive).isTrue()

        observer!!.onDisconnected(null)
        assertThat(mediaSession.isActive).isFalse()
    }

    /**
     * MediaSessionCompat must be driven from a looper thread, so a callback landing elsewhere is
     * posted. `onDisconnected` is the one that really arrives off main (from HumlaConnection's
     * socket thread).
     */
    @Test
    fun aDisconnectFromTheSocketThreadIsMovedToTheMainLooper() {
        var observer: IHumlaObserver? = null
        mediaSession.attach(serviceCapturing { observer = it })
        observer!!.onConnected()
        assertThat(mediaSession.isActive).isTrue()

        val socketThread = Thread { observer!!.onDisconnected(null) }
        socketThread.start()
        socketThread.join()

        assertThat(mediaSession.isActive).isTrue()
        idleMainLooper()
        assertThat(mediaSession.isActive).isFalse()
    }

    @Test
    fun detachUnregistersAndDeactivates() {
        var observer: IHumlaObserver? = null
        val service = serviceCapturing { observer = it }
        mediaSession.attach(service)
        observer!!.onConnected()

        mediaSession.detach(service)

        verify(exactly = 1) { service.unregisterObserver(observer!!) }
        assertThat(mediaSession.isActive).isFalse()
    }

    /**
     * After detach `applyState` short-circuits on `connected`, so only the handover is observable:
     * the listener registered on attach is the one handed back on detach.
     */
    @Test
    fun detachHandsBackTheVeryListenerAttachRegistered() {
        val prefs = spyk(PreferenceManager.getDefaultSharedPreferences(context))
        val prefContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        val detached = MumlaMediaSession(prefContext, target, Settings.getInstance(context))
        val service = serviceCapturing { }
        val registered = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        val unregistered = slot<SharedPreferences.OnSharedPreferenceChangeListener>()

        detached.attach(service)
        detached.detach(service)

        verify(exactly = 1) { prefs.registerOnSharedPreferenceChangeListener(capture(registered)) }
        verify(exactly = 1) {
            prefs.unregisterOnSharedPreferenceChangeListener(capture(unregistered))
        }
        assertThat(unregistered.captured).isSameInstanceAs(registered.captured)
    }

    /**
     * Giving up the session turns talking off; otherwise a talking state switched on by a headset
     * button with the screen off has no way back.
     */
    @Test
    fun deactivateStopsTalking() {
        mediaSession.activate()
        target.setTalking(true)

        mediaSession.deactivate()

        assertThat(target.stopTalkingCalls).isEqualTo(1)
        assertThat(target.isTalking).isFalse()
    }

    @Test
    fun switchingToNoneStopsTalking() {
        var observer: IHumlaObserver? = null
        mediaSession.attach(serviceCapturing { observer = it })
        observer!!.onConnected()
        target.setTalking(true)

        setAction("none")

        assertThat(target.stopTalkingCalls).isEqualTo(1)
        assertThat(target.isTalking).isFalse()
    }

    /**
     * Only a session we were holding is ours to unwind; a repeated deactivate (onDisconnected then
     * onDestroy) or an unrelated setting change must not touch the talking state.
     */
    @Test
    fun deactivateWithoutASessionLeavesTalkingAlone() {
        target.setTalking(true)

        mediaSession.deactivate()

        assertThat(target.stopTalkingCalls).isEqualTo(0)
        assertThat(target.isTalking).isTrue()
    }

    /** The preference listener does not filter by key; an unrelated change must change nothing. */
    @Test
    fun anUnrelatedPreferenceChangeLeavesTheSessionAndTalkingAlone() {
        var observer: IHumlaObserver? = null
        mediaSession.attach(serviceCapturing { observer = it })
        observer!!.onConnected()
        val token = mediaSession.sessionToken
        target.setTalking(true)

        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putBoolean(Settings.PREF_BLUETOOTH_SCO, true).commit()

        assertThat(mediaSession.isActive).isTrue()
        assertThat(mediaSession.sessionToken).isEqualTo(token)
        assertThat(target.stopTalkingCalls).isEqualTo(0)
        assertThat(target.isTalking).isTrue()
    }

    /**
     * `release()` is the only way to hand a session back to the system; a dropped one would keep
     * advertising STATE_PLAYING. The session is handed in through the factory to assert that.
     */
    @Test
    fun givingUpTheSessionHandsItBackToTheSystem() {
        val handedOut = mockk<MediaSessionCompat>(relaxed = true)
        val owner = MumlaMediaSession(context, target, Settings.getInstance(context)) { _, _ ->
            handedOut
        }
        owner.activate()

        owner.deactivate()

        verify(exactly = 1) { handedOut.release() }
    }

    /**
     * `HumlaMediaKeyTarget.stopTalking` returns early while disconnected, and the connection state
     * is already DISCONNECTED when `onDisconnected` fires, so the talking state is *not* cleared
     * on that path. The fake carries the same early exit.
     */
    @Test
    fun onDisconnectedTheTalkingStateIsLeftToStreamA() {
        var observer: IHumlaObserver? = null
        mediaSession.attach(serviceCapturing { observer = it })
        observer!!.onConnected()
        target.setTalking(true)

        target.isConnected = false // as HumlaService already has it when onDisconnected fires
        observer!!.onDisconnected(null)

        assertThat(mediaSession.isActive).isFalse()
        assertThat(target.stopTalkingCalls).isEqualTo(1)
        assertThat(target.isTalking).isTrue()
    }

    @Test
    fun switchingAwayFromNoneDoesNotStopTalking() {
        setAction("none")
        var observer: IHumlaObserver? = null
        mediaSession.attach(serviceCapturing { observer = it })
        observer!!.onConnected()
        target.setTalking(true)

        setAction("mute")

        assertThat(mediaSession.isActive).isTrue()
        assertThat(target.stopTalkingCalls).isEqualTo(0)
        assertThat(target.isTalking).isTrue()
    }
}
