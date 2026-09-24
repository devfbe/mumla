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
 * The pass-through to `AudioManager`'s communication-device API. Device selection rules are tested
 * in [AudioRouterTest] against a fake, because Robolectric's `AudioDeviceInfoBuilder` has no
 * `setId()`, so two SCO devices built here cannot be told apart.
 *
 * This pins the seam: device listing, `select` refusing an unavailable id, listener registration
 * and removal, and the SecurityException wrapper around the calls.
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

    private fun idsOfType(type: Int): List<Int> =
        devices.available().filter { it.type == type }.map { it.id }

    @Test
    fun selectsAndClearsAScoDeviceThroughAudioManager() {
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(sco()))

        val ids = idsOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertThat(ids).hasSize(1)
        assertThat(devices.current()).isNull()

        assertThat(devices.select(ids[0])).isTrue()
        assertThat(devices.current()?.type).isEqualTo(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        assertThat(devices.current()?.id).isEqualTo(ids[0])

        devices.clear()
        assertThat(devices.current()).isNull()
    }

    /**
     * Every device the platform offers, not one type: the chooser lists them all, in the platform's
     * order, each with the type it is labelled by and the product name a Bluetooth headset is shown
     * by. The name is never null - an unnamed device is an empty string the UI replaces.
     */
    @Test
    fun everyAvailableDeviceIsListedWithItsTypeAndName() {
        val speaker =
            AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        val earpiece =
            AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
        val headset = sco()
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(speaker, earpiece, headset))

        val listed = devices.available()

        assertThat(listed.map { it.type }).containsExactly(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        ).inOrder()
        // Robolectric names every device after itself, so this only checks the name is passed through.
        assertThat(listed.map { it.name }).containsExactly(
            speaker.productName.toString(),
            earpiece.productName.toString(),
            headset.productName.toString(),
        ).inOrder()
        assertThat(listed.map { it.name }).doesNotContain("")
    }

    @Test
    fun aDeviceOfAnotherTypeIsNotReportedAsSco() {
        val speaker =
            AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(speaker))

        assertThat(idsOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)).isEmpty()
        assertThat(idsOfType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)).hasSize(1)
    }

    /**
     * A headset being switched on or off is not a route change: the platform raises nothing on the
     * communication-device listener until someone routes, so "a Bluetooth headset appeared, take
     * it" needs the device callback too. Both reach the one listener, on the main looper.
     */
    @Test
    fun aDeviceArrivingOrLeavingIsReportedToTheListener() {
        shadowOf(audioManager).setAvailableCommunicationDevices(emptyList())
        val invocations = AtomicInteger()
        devices.setOnChangedListener { invocations.incrementAndGet() }
        shadowOf(Looper.getMainLooper()).idle()
        val afterRegistration = invocations.get()

        val headset = sco()
        shadowOf(audioManager).addAvailableCommunicationDevice(headset, true)
        assertThat(invocations.get()).isEqualTo(afterRegistration) // posted, not run inline
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(invocations.get()).isEqualTo(afterRegistration + 1)

        shadowOf(audioManager).removeAvailableCommunicationDevice(headset, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(invocations.get()).isEqualTo(afterRegistration + 2)

        devices.setOnChangedListener(null)
        shadowOf(audioManager).addAvailableCommunicationDevice(headset, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(invocations.get()).isEqualTo(afterRegistration + 2)
    }

    /** The mode is the platform's switch for routing voice by the communication device at all. */
    @Test
    fun theCommunicationModeIsTakenAndGivenBackThroughAudioManager() {
        devices.setCommunicationMode(true)
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_IN_COMMUNICATION)

        devices.setCommunicationMode(false)
        assertThat(audioManager.mode).isEqualTo(AudioManager.MODE_NORMAL)
    }

    @Test
    fun selectingAnUnknownIdFails() {
        shadowOf(audioManager).setAvailableCommunicationDevices(emptyList())

        assertThat(devices.select(42)).isFalse()
        assertThat(devices.current()).isNull()
    }

    /**
     * Refusal with a device present, which tells "the device with this id" apart from "the first
     * device". `ShadowAudioManager.setCommunicationDevice` does not check availability itself.
     */
    @Test
    fun selectingAnIdThatIsNotTheAvailableOnesFails() {
        val device = sco()
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(device))
        val present = idsOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).single()

        assertThat(devices.select(present + 1)).isFalse()
        assertThat(devices.current()).isNull()
    }

    /**
     * The platform's own refusal. `ShadowAudioManager.setCommunicationDevice` returns true unless
     * the route is locked, so `lockCommunicationDevice` stands in for an OEM refusing the route.
     *
     * The lock is a **static** field of the shadow, so it is reset in a `finally`.
     */
    @Test
    fun aPlatformThatRefusesTheSelectionIsReportedAsARefusal() {
        val device = sco()
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(device))
        val id = idsOfType(AudioDeviceInfo.TYPE_BLUETOOTH_SCO).single()

        shadowOf(audioManager).lockCommunicationDevice(true)
        try {
            assertThat(devices.select(id)).isFalse()
            assertThat(devices.current()).isNull()
        } finally {
            shadowOf(audioManager).lockCommunicationDevice(false)
        }

        assertThat(devices.select(id)).isTrue() // and the lock really was what refused it
    }

    /**
     * `ShadowAudioManager.setCommunicationDevice` does **not** call the registered listeners, so the
     * event is raised with `callOnCommunicationDeviceChangedListeners`, which dispatches through
     * the Executor we registered with - hence the main looper has to be idled.
     */
    @Test
    fun aRegisteredListenerIsInvokedOnTheMainLooperAndNotAfterItIsRemoved() {
        val device = sco()
        shadowOf(audioManager).setAvailableCommunicationDevices(listOf(device))
        val invocations = AtomicInteger()
        devices.setOnChangedListener { invocations.incrementAndGet() }
        // The device callback reports the devices already present on registration; flush that.
        shadowOf(Looper.getMainLooper()).idle()
        val registered = invocations.get()

        shadowOf(audioManager).callOnCommunicationDeviceChangedListeners(device)
        assertThat(invocations.get()).isEqualTo(registered) // posted, not run inline
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(invocations.get()).isEqualTo(registered + 1)

        devices.setOnChangedListener(null)
        shadowOf(audioManager).callOnCommunicationDeviceChangedListeners(null)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(invocations.get()).isEqualTo(registered + 1)
    }

    /**
     * Route without checking `BLUETOOTH_CONNECT`, but wrap every call so an OEM that enforces it
     * anyway cannot crash the process. Each call falls back to the "no headset" value, and the
     * denial is reported **once per instance** (one service life).
     */
    @Test
    @Config(shadows = [DenyingAudioManagerShadow::class])
    fun aDeniedCallIsCaughtReportedOnceAndAnsweredWithNoHeadset() {
        assertThat(devices.available()).isEmpty()
        assertThat(devices.select(1)).isFalse()
        assertThat(devices.current()).isNull()
        devices.clear()
        devices.setCommunicationMode(true)

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

        @Implementation
        override fun setMode(mode: Int): Unit = throw SecurityException("denied")
    }
}
