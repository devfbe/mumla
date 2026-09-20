/*
 * Copyright (C) 2026 The Mumla authors
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

package se.lublin.humla.session

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pass-through to `AudioManager`'s communication-device API, and nothing else. The rule of
 * spec A4 - *the first* TYPE_BLUETOOTH_SCO device - is verified in [ScoRouterTest] against a fake
 * that models distinct ids, because Robolectric's `AudioDeviceInfoBuilder` exposes `newBuilder()`,
 * `setType(int)`, `setProfiles(...)` and `build()` and no `setId()`, so two SCO devices built here
 * cannot be told apart.
 *
 * What this class pins is the seam: one id per available device of a type, `select` on an id that
 * is not in `availableCommunicationDevices` refusing, a registered listener really being invoked
 * and really stopping, and the SecurityException wrapper that spec 4.1 requires around the calls.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidCommunicationDevicesTest {
    private val audioManager =
        RuntimeEnvironment.getApplication().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val denials = mutableListOf<SecurityException>()
    private val devices =
        AndroidCommunicationDevices(audioManager, Handler(Looper.getMainLooper())) { denials += it }

    private fun sco(): AudioDeviceInfo =
        AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).build()

    @Test
    fun selectsAndClearsAScoDeviceThroughAudioManager() {
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(sco()))

        val ids = devices.availableIdsOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertThat(ids).hasSize(1)
        assertThat(devices.currentType()).isNull()

        assertThat(devices.select(ids[0])).isTrue()
        assertThat(devices.currentType()).isEqualTo(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)

        devices.clear()
        assertThat(devices.currentType()).isNull()
    }

    @Test
    fun aDeviceOfAnotherTypeIsNotReportedAsSco() {
        val speaker =
            AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(speaker))

        assertThat(devices.availableIdsOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)).isEmpty()
        assertThat(devices.availableIdsOfType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).hasSize(1)
    }

    @Test
    fun selectingAnUnknownIdFails() {
        shadowOf(audioManager).setAvailableCommunicationDevices(emptyList())

        assertThat(devices.select(42)).isFalse()
        assertThat(devices.currentType()).isNull()
    }

    /**
     * The same refusal with a device present, which is the corner that tells "the device with this
     * id" apart from "the first device". Found by mutation: with only the empty-list case above,
     * dropping the `it.id == id` test from the lookup left all 294 tests green, because an empty
     * list answers null either way. `ShadowAudioManager.setCommunicationDevice` does not check
     * availability, so under that mutation this call routes the user to a device nobody asked for
     * and returns true.
     */
    @Test
    fun selectingAnIdThatIsNotTheAvailableOnesFails() {
        val device = sco()
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(device))
        val present = devices.availableIdsOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).single()

        assertThat(devices.select(present + 1)).isFalse()
        assertThat(devices.currentType()).isNull()
    }

    /**
     * The platform's own refusal, which is a different answer from "there is no such device" and
     * was the last constant left in the double: `ShadowAudioManager.setCommunicationDevice` returns
     * true for anything unless the route is locked, so without `lockCommunicationDevice` this seam
     * could only ever be asked a question it always answers yes to. Under the lock the shadow
     * refuses and stores nothing, which is what an OEM that will not hand over the route does.
     *
     * The lock is a **static** field of the shadow, so it is put back in a `finally`; leaving it
     * set would refuse every selection in every test that runs afterwards in this JVM.
     */
    @Test
    fun aPlatformThatRefusesTheSelectionIsReportedAsARefusal() {
        val device = sco()
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(device))
        val id = devices.availableIdsOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).single()

        shadowOf(audioManager).lockCommunicationDevice(true)
        try {
            assertThat(devices.select(id)).isFalse()
            assertThat(devices.currentType()).isNull()
        } finally {
            shadowOf(audioManager).lockCommunicationDevice(false)
        }

        assertThat(devices.select(id)).isTrue() // and the lock really was what refused it
    }

    /**
     * The platform event, driven where the platform raises it. `ShadowAudioManager.setCommunication
     * Device` stores the device and does **not** call the registered listeners - disassembled to
     * check, it is a field write and a return - so driving the notification through `select()`
     * would assert nothing about the registration. `callOnCommunicationDeviceChangedListeners` is
     * the shadow's own raise, and it dispatches through the Executor we hand to
     * `addOnCommunicationDeviceChangedListener`, which is why the main looper has to be idled.
     */
    @Test
    fun aRegisteredListenerIsInvokedOnTheMainLooperAndNotAfterItIsRemoved() {
        val device = sco()
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(device))
        val invocations = AtomicInteger()
        devices.setOnChangedListener { invocations.incrementAndGet() }

        shadowOf(audioManager).callOnCommunicationDeviceChangedListeners(device)
        assertThat(invocations.get()).isEqualTo(0) // posted, not run inline
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(invocations.get()).isEqualTo(1)

        devices.setOnChangedListener(null)
        shadowOf(audioManager).callOnCommunicationDeviceChangedListeners(null)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(invocations.get()).isEqualTo(1)
    }

    /**
     * Spec 4.1's ruling on `BLUETOOTH_CONNECT`: route on the wish alone, and wrap the call so an
     * OEM that enforces a permission the SDK's own annotation database does not record cannot take
     * the process down. Every call into `AudioManager` is wrapped, the value each one falls back to
     * is the one that reads as "no headset", and the denial is reported **once per instance** -
     * one instance being one service life - so the chat log carries the reason and not a stream.
     */
    @Test
    @Config(shadows = [DenyingAudioManagerShadow::class])
    fun aDeniedCallIsCaughtReportedOnceAndAnsweredWithNoHeadset() {
        assertThat(devices.availableIdsOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)).isEmpty()
        assertThat(devices.select(1)).isFalse()
        assertThat(devices.currentType()).isNull()
        devices.clear()

        assertThat(denials).hasSize(1)
        assertThat(denials[0]).hasMessageThat().contains("denied")
    }

    /** An `AudioManager` on which every communication-device call is refused. */
    @Implements(AudioManager::class)
    class DenyingAudioManagerShadow : ShadowAudioManager() {
        @Implementation
        override fun getAvailableCommunicationDevices(): List<AudioDeviceInfo> =
            throw SecurityException("denied")

        @Implementation
        override fun setCommunicationDevice(device: AudioDeviceInfo): Boolean =
            throw SecurityException("denied")

        @Implementation
        override fun getCommunicationDevice(): AudioDeviceInfo = throw SecurityException("denied")

        @Implementation
        override fun clearCommunicationDevice(): Unit = throw SecurityException("denied")
    }
}
