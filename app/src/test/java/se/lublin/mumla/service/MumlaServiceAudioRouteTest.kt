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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.humla.session.AudioRouter
import se.lublin.mumla.Settings

/**
 * The earpiece is the handset mode now: routing voice to it - chosen in the audio chooser, or the
 * default output without a headset - holds the proximity lock that turns the screen off at the
 * ear, and every other device releases it. No switch of its own.
 *
 * Driven through the real chain: the router over a recording device seam, the service's route
 * report, and `MumlaService`'s hook - only the platform's `AudioManager` is replaced.
 */
@RunWith(RobolectricTestRunner::class)
class MumlaServiceAudioRouteTest {
    private lateinit var app: Application
    private lateinit var devices: MumlaServiceBluetoothTest.RecordingDevices
    private lateinit var service: MumlaService

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
            .putBoolean(Settings.PREF_BLUETOOTH_SCO, false).commit()
    }

    private fun create() {
        devices = MumlaServiceBluetoothTest.RecordingDevices().apply {
            available.clear()
            available[EARPIECE] = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            available[SPEAKER] = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        val controller = Robolectric.buildService(MumlaService::class.java)
        controller.get().communicationDevices = devices
        service = controller.create().get()
    }

    private fun router(): AudioRouter =
        Class.forName("se.lublin.humla.HumlaService").getDeclaredField("mRouter")
            .apply { isAccessible = true }.get(service) as AudioRouter

    private fun proximityLockHeld(): Boolean {
        val lock = MumlaService::class.java.getDeclaredField("mProximityLock")
            .apply { isAccessible = true }.get(service) as PowerManager.WakeLock?
        return lock != null && lock.isHeld && shadowOf(lock).tag == "Mumla:Proximity"
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
    fun theEarpieceAsDefaultOutputTurnsItOnWhenTheSessionStarts() {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
            .putString(Settings.PREF_DEFAULT_OUTPUT, Settings.DEFAULT_OUTPUT_EARPIECE).commit()
        create()
        // What ServerConnectTask sends at connect; the preference listener only sees changes.
        router().earpieceByDefault = Settings.getInstance(app).isEarpieceDefaultOutput()

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
