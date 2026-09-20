package se.lublin.mumla.service

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.media.session.MediaSession
import android.os.Looper
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
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.Constants
import se.lublin.humla.IHumlaService
import se.lublin.humla.util.IHumlaObserver
import se.lublin.mumla.Settings

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
            // The same early exit HumlaMediaKeyTarget has. Without it this fake can do something
            // the production target cannot, and a test asserting it would be measuring the fake.
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
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
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
     * The half of the ACTION_DOWN/ACTION_UP question that can be executed here: whether the
     * library between the framework and us filters by key action. It does not -- this drives the
     * real androidx.media callback that MediaSessionCompat hands to the platform, not our own
     * override -- so one press, which the platform delivers as a DOWN and an UP, reaches us as
     * two events and must toggle exactly once. The other half (which of the two the platform
     * sends at all) is answered in MediaKeyHandler's KDoc and belongs to hardware QA.
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
     * HumlaCallbacks invokes observers on whatever thread fired the event, and MediaSessionCompat
     * has to be built and driven from a looper thread. So an observer callback that arrives
     * elsewhere is posted, not run where it landed -- pinned here by watching that nothing has
     * happened until the main looper is idled.
     */
    @Test
    fun observerCallbacksFromAnotherThreadAreMovedToTheMainLooper() {
        var observer: IHumlaObserver? = null
        mediaSession.attach(serviceCapturing { observer = it })

        val worker = Thread { observer!!.onConnected() }
        worker.start()
        worker.join()

        assertThat(mediaSession.isActive).isFalse()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(mediaSession.isActive).isTrue()
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
     * Unregistering the preference listener has no consequence this wrapper can be asked about.
     * After detach it believes it is disconnected, so `applyState` short-circuits on `connected`
     * before it even reads the setting, and a listener left behind behaves exactly like one that
     * is gone -- measured: an earlier version of this test watched the setting being read and
     * stayed green against a detach that unregistered nothing, because of that short-circuit.
     * What remains observable is the handover itself, so that is what is asserted: the listener
     * registered on attach is the one handed back on detach.
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
     * Giving up the session turns talking off. Nothing else does: ToggleInputMode.mInputOn lives
     * as long as the service, and a talking state switched on by a headset button with the screen
     * off has no other way back. Asserted while the wrapper still holds the fake, not after some
     * later teardown that would clear it anyway.
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
     * Only a session we were actually holding is ours to unwind. Otherwise every repeated
     * deactivate -- and onDisconnected followed by onDestroy is exactly that -- and every
     * unrelated change of the setting would reach into a talking state this class never set.
     */
    @Test
    fun deactivateWithoutASessionLeavesTalkingAlone() {
        target.setTalking(true)

        mediaSession.deactivate()

        assertThat(target.stopTalkingCalls).isEqualTo(0)
        assertThat(target.isTalking).isTrue()
    }

    /**
     * The preference listener does not filter by key, so this pins what that costs: re-evaluating
     * on a change that is none of our business must leave the held session and the talking state
     * exactly as they were.
     */
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
     * A [MediaSessionCompat] is a handle on a session registered with the system, and `release()`
     * is the only thing that hands it back. Were it dropped instead, every connect/disconnect
     * cycle and every switch to NONE would leave a live session behind, still advertising
     * STATE_PLAYING and still taking play/pause away from every other app -- the exact damage this
     * class exists to prevent. Nothing above the class can see it, because `isActive` and
     * `sessionToken` read off the very reference that was dropped, so the session is handed in
     * through the factory and the release is asserted on it.
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
     * The same question one object further out: the main looper holds our posted observer
     * callbacks, and `detach` is the only place that can hand them back. `onConnected` arrives on
     * the protocol thread and is posted; `MumlaService.onDestroy` runs `detach` on main. A post
     * still queued at that moment would build a session *after* the only code that could release
     * it has run -- a live STATE_PLAYING session with no reference left anywhere.
     */
    @Test
    fun aConnectStillQueuedAtDetachDoesNotOutliveIt() {
        var built = 0
        var observer: IHumlaObserver? = null
        val owner = MumlaMediaSession(context, target, Settings.getInstance(context)) { c, tag ->
            built++
            MediaSessionCompat(c, tag)
        }
        val service = serviceCapturing { observer = it }
        owner.attach(service)
        val worker = Thread { observer!!.onConnected() }
        worker.start()
        worker.join()

        owner.detach(service)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(built).isEqualTo(0)
        assertThat(owner.sessionToken).isNull()
    }

    /**
     * What `stopTalking` reaches on the main path, stated with its scope rather than as a promise.
     * `HumlaMediaKeyTarget.stopTalking` returns immediately while the service is disconnected, and
     * `mConnectionState` is already DISCONNECTED before `onDisconnected` fires (spec 4.1) -- so on
     * `onDisconnected -> deactivate -> releaseSession` the talking state is *not* cleared here.
     * The fake carries the same early exit, so no test in this file can claim a reach the
     * production target does not have. Closing this window is stream A's job, in
     * `HumlaService.onConnectionDisconnected`.
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
