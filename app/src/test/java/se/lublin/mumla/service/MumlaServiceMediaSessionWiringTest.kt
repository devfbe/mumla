package se.lublin.mumla.service

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.util.HumlaCallbacks

/**
 * The media session is only reachable through the service, so the wiring in onCreate/onDestroy is
 * the whole feature as far as a user is concerned. Driven through the service's own observer
 * registry rather than by looking at the field: that a session object exists says nothing, and
 * after onDestroy an assertion about the session would hold whether it was detached or not.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceMediaSessionWiringTest {

    private fun mediaSessionOf(service: MumlaService): MumlaMediaSession =
        MumlaService::class.java.getDeclaredField("mMediaSession")
            .apply { isAccessible = true }
            .get(service) as MumlaMediaSession

    private fun callbacksOf(service: MumlaService): HumlaCallbacks =
        Class.forName("se.lublin.humla.HumlaService").getDeclaredField("mCallbacks")
            .apply { isAccessible = true }
            .get(service) as HumlaCallbacks

    @Test
    fun aConnectedServiceHoldsAMediaSessionAndGivesItUpWhenDestroyed() {
        val controller = Robolectric.buildService(MumlaService::class.java).create()
        val service = controller.get()
        val mediaSession = mediaSessionOf(service)
        assertThat(mediaSession.isActive).isFalse()

        callbacksOf(service).onConnected()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(mediaSession.isActive).isTrue()

        controller.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(mediaSession.isActive).isFalse()

        // and it is really detached, not merely inactive: a reconnect must not revive it
        callbacksOf(service).onConnected()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(mediaSession.isActive).isFalse()
    }
}
