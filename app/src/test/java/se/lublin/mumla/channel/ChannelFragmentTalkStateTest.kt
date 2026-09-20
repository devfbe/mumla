package se.lublin.mumla.channel

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.service.IMumlaService
import se.lublin.mumla.util.HumlaServiceFragment
import se.lublin.mumla.util.HumlaServiceProvider

/**
 * Who may switch transmission off, and on whose behalf.
 *
 * `onPause` used to switch it off for everyone whenever the push-to-talk preference said "hold"
 * -- which is the default -- on the theory that a paused fragment may be holding the talk button
 * down. Since a headset media key can switch transmission on with the screen off, that theory
 * turned into a trap: pick the phone up, and the fragment that never held anything silences the
 * user. These tests fix who owns the state: the fragment releases what its own button is holding
 * and nothing else.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelFragmentTalkStateTest {

    class HostActivity : AppCompatActivity(), HumlaServiceProvider {
        // A `var service` here would generate getService(), clashing with the interface method.
        private var bound: IMumlaService? = null
        fun bind(service: IMumlaService) { bound = service }
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Mumla)
            super.onCreate(savedInstanceState)
        }
        override fun getService(): IMumlaService? = bound
        override fun addServiceFragment(fragment: HumlaServiceFragment) = Unit
        override fun removeServiceFragment(fragment: HumlaServiceFragment) = Unit
    }

    private lateinit var session: IHumlaSession
    private lateinit var service: IMumlaService
    private lateinit var controller: ActivityController<HostActivity>
    private lateinit var fragment: ChannelFragment

    @Before
    fun setUp() {
        PreferenceManager
            .getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<android.content.Context>())
            .edit().clear()
            // `configureInput` reads three settings, and clearing the preferences answers only two
            // of them the way this class needs. `isPushToTalkButtonShown` defaults to shown, but
            // `getInputMethod` defaults to ARRAY_INPUT_METHOD_VOICE, and voice activity hides the
            // talk view outright -- measured: every test below ran against `visibility == GONE`
            // and passed anyway, because `touch` dispatches straight at the view and never asks
            // whether a finger could have landed there. A push-to-talk button is the premise of
            // the whole class, so it is written here rather than per test.
            .putString(Settings.PREF_INPUT_METHOD, Settings.ARRAY_INPUT_METHOD_PTT)
            .commit()
        session = mockk(relaxed = true)
        service = mockk(relaxed = true) {
            every { isConnected } returns true
            every { HumlaSession() } returns session
        }
        every { session.isTalking } returns true
        controller = Robolectric.buildActivity(HostActivity::class.java).setup()
        controller.get().bind(service)
        fragment = ChannelFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .add(fragment, "channel").commitNow()
    }

    private val talkButton: View get() = fragment.requireView().findViewById(R.id.pushtotalk)

    /**
     * The talk button is a hold by default and a toggle when this is on. The two are not variants
     * of one behaviour: in toggle mode the *down* does nothing and the *up* is the whole action.
     */
    private fun setPushToTalkToggle(toggle: Boolean) {
        PreferenceManager
            .getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<android.content.Context>())
            .edit().putBoolean(Settings.PREF_PTT_TOGGLE, toggle).commit()
    }

    private fun touch(action: Int) {
        talkButton.dispatchTouchEvent(MotionEvent.obtain(0L, 0L, action, 0f, 0f, 0))
    }

    @Test
    fun pausingDoesNotSilenceATalkStateTheButtonIsNotHolding() {
        controller.pause()

        verify(exactly = 0) { session.setTalkingState(false) }
    }

    @Test
    fun pausingWhileTheButtonIsHeldStillReleasesIt() {
        touch(MotionEvent.ACTION_DOWN)

        controller.pause()

        verify(exactly = 1) { session.setTalkingState(false) }
    }

    @Test
    fun pausingAfterTheButtonWasReleasedSilencesNothing() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP)

        controller.pause()

        verify(exactly = 0) { session.setTalkingState(false) }
    }

    /**
     * The press is over once the fragment paused on it -- no ACTION_UP will ever arrive for it --
     * so the hold must not be remembered into the next pause, or the trap is back one lifecycle
     * later.
     */
    @Test
    fun aPressThatWasAlreadyReleasedOnPauseIsNotReleasedTwice() {
        touch(MotionEvent.ACTION_DOWN)
        controller.pause()
        verify(exactly = 1) { session.setTalkingState(false) }

        controller.resume()
        controller.pause()

        verify(exactly = 1) { session.setTalkingState(false) }
    }

    /**
     * A parent that takes the gesture over -- the navigation drawer being dragged open is the one
     * that prompted the second of these handlers -- delivers ACTION_CANCEL to the child instead of
     * ACTION_UP. Without this the press is never released and transmission sticks on, which is
     * exactly what MumlaActivity's drawer listener was compensating for by resetting everyone.
     */
    @Test
    fun aCanceledPressReleasesTheButtonLikeAnUp() {
        touch(MotionEvent.ACTION_DOWN)

        touch(MotionEvent.ACTION_CANCEL)

        verify(exactly = 1) { service.onTalkKeyUp() }
        controller.pause()
        verify(exactly = 0) { session.setTalkingState(false) }
    }

    @Test
    fun theButtonStillDrivesPushToTalk() {
        touch(MotionEvent.ACTION_DOWN)
        verify(exactly = 1) { service.onTalkKeyDown() }

        touch(MotionEvent.ACTION_UP)
        verify(exactly = 1) { service.onTalkKeyUp() }
    }

    @Test
    fun hostingTheFragmentReallyBuildsTheButton() {
        assertThat(talkButton).isNotNull()
    }

    /**
     * Every other test here reaches the button through `dispatchTouchEvent`, which walks past hit
     * testing and visibility alike -- so the whole class would stay green against a button the
     * user can never touch. It is not a hypothetical: `configureInput` hides the talk view unless
     * the input method is push-to-talk, and `Settings.getInputMethod` defaults to voice activity.
     * This is the one test that asserts a real finger could arrive at all, and it is asserted on
     * `pushtotalk_view`, because that is the view `setTalkButtonHidden` writes to; the button's
     * own visibility never changes and would report VISIBLE inside a GONE parent.
     */
    @Test
    fun theTalkButtonIsVisibleInPushToTalkMode() {
        val talkView: View = fragment.requireView().findViewById(R.id.pushtotalk_view)

        assertThat(talkView.visibility).isEqualTo(View.VISIBLE)
    }

    /**
     * The third setting `configureInput` reads. The test above pins only its default answer --
     * "not hidden" -- which is the same answer an `isPushToTalkButtonShown()` that had been
     * deleted would give, so the other value is pinned here: the user hiding the button takes it
     * away even in push-to-talk mode. Written after setup on purpose, because that also pins the
     * preference listener that turns the setting into a re-layout while the fragment is up.
     */
    @Test
    fun hidingTheTalkButtonTakesItAwayInPushToTalkMode() {
        val talkView: View = fragment.requireView().findViewById(R.id.pushtotalk_view)

        PreferenceManager
            .getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<android.content.Context>())
            .edit().putBoolean(Settings.PREF_PUSH_BUTTON_HIDE_KEY, true).commit()

        assertThat(talkView.visibility).isEqualTo(View.GONE)
    }

    /**
     * A cancel says the gesture was taken away, not that the user finished it, and in toggle mode
     * `MumlaService.onTalkKeyUp()` is not a release but the action itself -- the down did nothing,
     * because `onTalkKeyDown` is gated on `!isPushToTalkToggle()`. Treating the cancel like an up
     * therefore switches the microphone *on* after the user aborted. It is reachable: the button is
     * `match_parent` at the bottom of a `DrawerLayout`, so its left edge is both the drawer's drag
     * zone and where the system back gesture starts, and both synthesize exactly this cancel.
     */
    @Test
    fun aCanceledPressInToggleModeDoesNotPerformTheToggle() {
        setPushToTalkToggle(true)
        touch(MotionEvent.ACTION_DOWN)

        touch(MotionEvent.ACTION_CANCEL)

        verify(exactly = 0) { service.onTalkKeyUp() }
    }

    /** And nothing takes it back later: `onPause` does not release in toggle mode either. */
    @Test
    fun aCanceledPressInToggleModeIsNotTurnedOnByTheFollowingPause() {
        setPushToTalkToggle(true)
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_CANCEL)

        controller.pause()

        verify(exactly = 0) { service.onTalkKeyUp() }
        verify(exactly = 0) { session.setTalkingState(any()) }
    }

    /**
     * In toggle mode a held button is holding nothing -- the down is a no-op there -- so a pause on
     * it has nothing of ours to release, and switching transmission off would silence a state
     * somebody else set.
     */
    @Test
    fun pausingOnAHeldButtonInToggleModeSilencesNothing() {
        setPushToTalkToggle(true)
        touch(MotionEvent.ACTION_DOWN)

        controller.pause()

        verify(exactly = 0) { session.setTalkingState(false) }
    }

    /**
     * Releasing goes through `HumlaSession()`, which throws under exactly the condition that makes
     * `isConnected()` false. A pause that arrives once the connection is gone must not reach for it.
     */
    @Test
    fun pausingOnAHeldButtonWhileDisconnectedReleasesNothing() {
        every { service.isConnected } returns false
        touch(MotionEvent.ACTION_DOWN)

        controller.pause()

        verify(exactly = 0) { session.setTalkingState(false) }
    }
}
