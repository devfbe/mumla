package se.lublin.mumla.channel

import android.view.MotionEvent
import android.view.View
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
import se.lublin.mumla.testing.ServiceHostActivity
import se.lublin.mumla.testing.stubConnected

/**
 * The fragment releases only what its own talk button is holding; transmission switched on
 * elsewhere (e.g. by a headset media key) is left alone.
 */
@RunWith(RobolectricTestRunner::class)
class ChannelFragmentTalkStateTest {

    private lateinit var session: IHumlaSession
    private lateinit var service: IMumlaService
    private lateinit var controller: ActivityController<ServiceHostActivity>
    private lateinit var fragment: ChannelFragment

    @Before
    fun setUp() {
        PreferenceManager
            .getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<android.content.Context>())
            .edit()
            // Voice activity (the default) hides the talk view, and `touch` dispatches straight at
            // the view regardless, so push-to-talk must be set explicitly.
            .putString(Settings.INPUT_METHOD.key, Settings.ARRAY_INPUT_METHOD_PTT)
            .commit()
        session = mockk(relaxed = true)
        service = mockk<IMumlaService>(relaxed = true).stubConnected(session)
        every { session.isTalking } returns true
        controller = Robolectric.buildActivity(ServiceHostActivity::class.java).setup()
        controller.get().bind(service)
        fragment = ChannelFragment()
        controller.get().supportFragmentManager.beginTransaction()
            .add(fragment, "channel").commitNow()
    }

    private val talkButton: View get() = fragment.requireView().findViewById(R.id.pushtotalk)

    /**
     * Hold by default, toggle when on. In toggle mode the down does nothing and the up is the
     * whole action.
     */
    private fun setPushToTalkToggle(toggle: Boolean) {
        PreferenceManager
            .getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<android.content.Context>())
            .edit().putBoolean(Settings.PTT_TOGGLE.key, toggle).commit()
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
     * No ACTION_UP arrives for a press the fragment paused on, so the hold must not carry into the
     * next pause.
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
     * A parent that takes the gesture over (e.g. the drawer being dragged open) sends ACTION_CANCEL
     * instead of ACTION_UP, which must release the press.
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
     * The other tests dispatch touches past hit testing and visibility, so this asserts the talk
     * view is actually visible. Checked on `pushtotalk_view`, the view `setTalkButtonHidden`
     * writes to; the button itself stays VISIBLE inside a GONE parent.
     */
    @Test
    fun theTalkButtonIsVisibleInPushToTalkMode() {
        val talkView: View = fragment.requireView().findViewById(R.id.pushtotalk_view)

        assertThat(talkView.visibility).isEqualTo(View.VISIBLE)
    }

    /**
     * Hiding the button takes it away even in push-to-talk mode; set after setup so the preference
     * listener's re-layout is covered too.
     */
    @Test
    fun hidingTheTalkButtonTakesItAwayInPushToTalkMode() {
        val talkView: View = fragment.requireView().findViewById(R.id.pushtotalk_view)

        PreferenceManager
            .getDefaultSharedPreferences(ApplicationProvider.getApplicationContext<android.content.Context>())
            .edit().putBoolean(Settings.PUSH_BUTTON_HIDE.key, true).commit()

        assertThat(talkView.visibility).isEqualTo(View.GONE)
    }

    /**
     * In toggle mode `onTalkKeyUp()` is the action itself, so a cancel must not be treated as an
     * up or it would switch the microphone on after the user aborted. The drawer drag zone and the
     * system back gesture both synthesize this cancel.
     */
    @Test
    fun aCanceledPressInToggleModeDoesNotPerformTheToggle() {
        setPushToTalkToggle(true)
        touch(MotionEvent.ACTION_DOWN)

        touch(MotionEvent.ACTION_CANCEL)

        verify(exactly = 0) { service.onTalkKeyUp() }
    }

    /** `onPause` does not release in toggle mode either. */
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
     * In toggle mode a held button holds nothing, so a pause must not switch off a state somebody
     * else set.
     */
    @Test
    fun pausingOnAHeldButtonInToggleModeSilencesNothing() {
        setPushToTalkToggle(true)
        touch(MotionEvent.ACTION_DOWN)

        controller.pause()

        verify(exactly = 0) { session.setTalkingState(false) }
    }

    /**
     * `session` throws when `isConnected` is false, so a pause after disconnect must not
     * reach for it.
     */
    @Test
    fun pausingOnAHeldButtonWhileDisconnectedReleasesNothing() {
        every { service.isConnected } returns false
        touch(MotionEvent.ACTION_DOWN)

        controller.pause()

        verify(exactly = 0) { session.setTalkingState(false) }
    }
}
