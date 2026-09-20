package se.lublin.mumla.channel

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
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
}
