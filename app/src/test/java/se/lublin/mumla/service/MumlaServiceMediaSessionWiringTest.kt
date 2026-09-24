package se.lublin.mumla.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.testutil.testCallbacks
import se.lublin.mumla.testing.idleMainLooper

/**
 * The media session wiring in onCreate/onDestroy, checked through the service's observer registry
 * rather than the field: after onDestroy the session must be detached, not merely inactive.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceMediaSessionWiringTest {

    @Test
    fun aConnectedServiceHoldsAMediaSessionAndGivesItUpWhenDestroyed() {
        val controller = Robolectric.buildService(MumlaService::class.java).create()
        val service = controller.get()
        val mediaSession = service.mMediaSession!!
        assertThat(mediaSession.isActive).isFalse()

        service.testCallbacks.onConnected()
        idleMainLooper()
        assertThat(mediaSession.isActive).isTrue()

        controller.destroy()
        idleMainLooper()
        assertThat(mediaSession.isActive).isFalse()

        // and it is really detached, not merely inactive: a reconnect must not revive it
        service.testCallbacks.onConnected()
        idleMainLooper()
        assertThat(mediaSession.isActive).isFalse()
    }
}
