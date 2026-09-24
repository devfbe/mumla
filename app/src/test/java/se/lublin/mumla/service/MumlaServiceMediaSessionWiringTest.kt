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
 * The media session wiring in onCreate/onDestroy, checked through the service's observer registry
 * rather than the field: after onDestroy the session must be detached, not merely inactive.
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
