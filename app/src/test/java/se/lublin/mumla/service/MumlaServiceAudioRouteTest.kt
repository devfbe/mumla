/*
 * Copyright (C) 2026 The Mumla contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package se.lublin.mumla.service

import android.app.Application
import android.media.AudioDeviceInfo
import android.os.PowerManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import se.lublin.humla.audio.routing.AudioRouter
import se.lublin.humla.audio.routing.PreferredAudioDevice
import se.lublin.humla.session.SessionConfig
import se.lublin.humla.testutil.FakeCommunicationDevices
import se.lublin.humla.testutil.testRouter
import se.lublin.mumla.Settings
import se.lublin.mumla.testing.createMumlaService

/**
 * Routing voice to the earpiece holds the proximity lock that turns the screen off at the ear;
 * every other device releases it. Driven through the real router, route report and service hook;
 * only the platform's `AudioManager` is replaced.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceAudioRouteTest {
    private lateinit var app: Application
    private lateinit var devices: FakeCommunicationDevices
    private lateinit var service: MumlaService
    private lateinit var controller: ServiceController<MumlaService>

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putBoolean(Settings.PREF_BLUETOOTH_SCO, false).commit()
    }

    private fun create() {
        devices = FakeCommunicationDevices().apply {
            available[EARPIECE] = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            available[SPEAKER] = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        controller = createMumlaService { communicationDevices = devices }
        service = controller.get()
    }

    private fun router(): AudioRouter = service.testRouter

    private fun proximityLock(): PowerManager.WakeLock? = service.mProximityLock

    private fun proximityLockHeld(): Boolean {
        val lock = proximityLock()
        return lock != null && lock.isHeld && shadowOf(lock).tag == "Mumla:Proximity"
    }

    private fun reportRoute(type: Int?) = service.applyAudioRoute(type)

    @Test
    fun aRepeatedEarpieceReportKeepsOneLockAndLeaksNone() {
        create()
        reportRoute(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val first = proximityLock()!!

        reportRoute(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        reportRoute(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

        assertThat(first.isHeld).isFalse()
        assertThat(proximityLockHeld()).isFalse()
    }

    @Test
    fun destroyingTheServiceReleasesTheLock() {
        create()
        reportRoute(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val lock = proximityLock()!!

        controller.destroy()

        assertThat(lock.isHeld).isFalse()
    }

    @Test
    fun choosingTheEarpieceTurnsTheProximitySensorOnAndAnythingElseOff() {
        create()
        router().engage()
        assertThat(proximityLockHeld()).isFalse() // the speaker

        service.selectAudioDevice(EARPIECE)
        assertThat(proximityLockHeld()).isTrue()

        service.selectAudioDevice(SPEAKER)
        assertThat(proximityLockHeld()).isFalse()
    }

    @Test
    fun aSavedEarpieceTurnsItOnWhenTheSessionStarts() {
        Settings.getInstance(app).preferredAudioDevice = PreferredAudioDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        create()
        // What ServerConnectTask sends at connect; the preference listener only sees changes.
        router().preferred =
            SessionSettings.withAudioSettings(SessionConfig(), Settings.getInstance(app)).preferredAudioDevice

        router().engage()

        assertThat(proximityLockHeld()).isTrue()
    }

    /** The session ending gives the route back, and with it the lock. */
    @Test
    fun theEndOfTheSessionTurnsItOff() {
        create()
        router().engage()
        service.selectAudioDevice(EARPIECE)

        router().disengage()

        assertThat(proximityLockHeld()).isFalse()
    }

    private companion object {
        const val EARPIECE = 1
        const val SPEAKER = 2
    }
}
