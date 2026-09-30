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

package se.lublin.humla.audio.routing

import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.testutil.FakeCommunicationDevices

/**
 * The audio chooser as one router: the user's explicit [AudioRouter.choice], otherwise the
 * automatic default - the saved [AudioRouter.preferred] headset, a connected Bluetooth headset when
 * [AudioRouter.bluetoothAutomatic] allows it, a plugged-in headset, then the saved built-in device or
 * the speaker. While engaged the router holds the communication mode and routes every one explicitly.
 */
class AudioRouterTest {
    private val devices = FakeCommunicationDevices()
    private val routes = mutableListOf<Int?>()
    private var refusals = 0
    private val router = AudioRouter(devices, object : AudioRouter.Listener {
        override fun onRouteChanged(type: Int?) { routes += type }
        override fun onRouteRefused() { refusals++ }
    })

    private fun phone() {
        devices.available[1] = TYPE_BUILTIN_EARPIECE
        devices.available[2] = TYPE_BUILTIN_SPEAKER
    }

    private fun engaged(bluetooth: Boolean = true) {
        router.bluetoothAutomatic = bluetooth
        router.engage()
    }

    @Test
    fun theFirstBluetoothHeadsetIsTakenAutomatically() {
        phone()
        devices.available[7] = TYPE_BLUETOOTH_SCO
        devices.available[9] = TYPE_BLUETOOTH_SCO

        engaged()

        assertThat(devices.selectedId).isEqualTo(7)
        assertThat(router.isBluetoothActive).isTrue()
        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO)
        assertThat(refusals).isEqualTo(0)
    }

    /**
     * The route taken when the session starts on a phone (earpiece 1, speaker 2) with [available]
     * devices besides, by type, and their Bluetooth [addresses].
     */
    private fun defaultRoute(
        available: Map<Int, Int> = emptyMap(),
        addresses: Map<Int, String> = emptyMap(),
        preferred: PreferredAudioDevice? = null,
        bluetooth: Boolean = true,
        earpiece: Boolean = true,
    ): Int? {
        val devices = FakeCommunicationDevices()
        if (earpiece) devices.available[1] = TYPE_BUILTIN_EARPIECE
        devices.available[2] = TYPE_BUILTIN_SPEAKER
        devices.available += available
        devices.addresses += addresses
        val router = AudioRouter(devices, object : AudioRouter.Listener {
            override fun onRouteChanged(type: Int?) = Unit
            override fun onRouteRefused() = Unit
        })
        router.preferred = preferred
        router.bluetoothAutomatic = bluetooth
        router.engage()
        assertThat(router.activeDevice()?.id).isEqualTo(devices.selectedId)
        return devices.selectedId
    }

    /**
     * In communication mode the platform's own default is the earpiece, so every default is routed
     * explicitly: a saved headset that is there, even over another headset (a Bluetooth one found by
     * its address, as its id changes on every connection); else a Bluetooth headset unless the user
     * switched that off, LE Audio included; else a plugged-in headset; else the speaker. A saved
     * built-in device that is there is taken over any headset present when the session starts (a
     * tablet has no earpiece, so saving one must not leave it silent).
     */
    @Test
    fun theDefaultRouteFollowsThePreferenceOrder() {
        val earpiece = PreferredAudioDevice(TYPE_BUILTIN_EARPIECE)
        val savedHeadset = PreferredAudioDevice(TYPE_BLUETOOTH_SCO, "AA")
        val wiredAndBluetooth = mapOf(4 to TYPE_WIRED_HEADSET, 7 to TYPE_BLUETOOTH_SCO)

        assertThat(defaultRoute()).isEqualTo(2)
        assertThat(defaultRoute(mapOf(8 to TYPE_BLE_HEADSET))).isEqualTo(8)
        assertThat(defaultRoute(mapOf(7 to TYPE_BLUETOOTH_SCO), bluetooth = false)).isEqualTo(2)
        assertThat(defaultRoute(mapOf(4 to TYPE_WIRED_HEADSET))).isEqualTo(4)
        assertThat(defaultRoute(preferred = earpiece)).isEqualTo(1)
        assertThat(defaultRoute(mapOf(4 to TYPE_WIRED_HEADSET), preferred = earpiece)).isEqualTo(1)
        assertThat(defaultRoute(preferred = earpiece, earpiece = false)).isEqualTo(2)
        assertThat(defaultRoute(wiredAndBluetooth, mapOf(7 to "AA"), savedHeadset, bluetooth = false)).isEqualTo(7)
        assertThat(defaultRoute(wiredAndBluetooth, mapOf(7 to "BB"), savedHeadset, bluetooth = false)).isEqualTo(4)
        val twoBluetooth = mapOf(7 to TYPE_BLUETOOTH_SCO, 9 to TYPE_BLUETOOTH_SCO)
        assertThat(defaultRoute(twoBluetooth, mapOf(7 to "BB", 9 to "AA"), savedHeadset)).isEqualTo(9)
    }

    /**
     * A device picked before connecting is only saved; at the start of the session it must count as
     * the same pick made while connected, so it wins over a headset that is already there, as the
     * chooser showed it.
     */
    @Test
    fun aSavedBuiltInDeviceWinsOverAHeadsetPresentWhenTheSessionStarts() {
        val earpiece = PreferredAudioDevice(TYPE_BUILTIN_EARPIECE)
        val speaker = PreferredAudioDevice(TYPE_BUILTIN_SPEAKER)

        assertThat(defaultRoute(mapOf(7 to TYPE_BLUETOOTH_SCO), preferred = earpiece)).isEqualTo(1)
        assertThat(defaultRoute(mapOf(8 to TYPE_BLE_HEADSET), preferred = earpiece)).isEqualTo(1)
        assertThat(defaultRoute(mapOf(4 to TYPE_USB_HEADSET), preferred = earpiece)).isEqualTo(1)
        assertThat(defaultRoute(mapOf(4 to TYPE_WIRED_HEADSET, 7 to TYPE_BLUETOOTH_SCO), preferred = speaker))
            .isEqualTo(2)
    }

    /** ...while a headset switched on during the session still takes over, as from any pick. */
    @Test
    fun aHeadsetThatArrivesTakesOverFromASavedBuiltInDevice() {
        phone()
        devices.available[4] = TYPE_WIRED_HEADSET
        router.preferred = PreferredAudioDevice(TYPE_BUILTIN_EARPIECE)
        engaged()
        assertThat(devices.selectedId).isEqualTo(1)

        devices.deviceArrives(7, TYPE_BLUETOOTH_SCO)

        assertThat(devices.selectedId).isEqualTo(7)
        assertThat(routes).containsExactly(TYPE_BUILTIN_EARPIECE, TYPE_BLUETOOTH_SCO).inOrder()
    }

    /**
     * The saved device beats the headset only as long as it is the saved device: changing the
     * preference (in Settings, say) during the session moves the route by the new one.
     */
    @Test
    fun changingThePreferenceDropsWhatTheOldOneWonAtTheStart() {
        phone()
        devices.available[7] = TYPE_BLUETOOTH_SCO
        router.preferred = PreferredAudioDevice(TYPE_BUILTIN_EARPIECE)
        engaged()
        assertThat(devices.selectedId).isEqualTo(1)

        router.preferred = null
        router.apply()

        assertThat(router.choice).isNull()
        assertThat(devices.selectedId).isEqualTo(7)
    }

    /** A pick the user made in the session is theirs, and a new preference leaves it standing. */
    @Test
    fun changingThePreferenceLeavesAnExplicitChoiceStanding() {
        phone()
        devices.available[7] = TYPE_BLUETOOTH_SCO
        router.preferred = PreferredAudioDevice(TYPE_BUILTIN_EARPIECE)
        engaged()
        router.choose(2)

        router.preferred = null
        router.apply()

        assertThat(router.choice).isEqualTo(2)
        assertThat(devices.selectedId).isEqualTo(2)
    }

    @Test
    fun withoutABluetoothHeadsetAPluggedInOneIsRouted() {
        phone()
        devices.available[4] = TYPE_WIRED_HEADSET

        engaged()

        assertThat(devices.selectedId).isEqualTo(4)
        assertThat(refusals).isEqualTo(0)
        assertThat(routes).containsExactly(TYPE_WIRED_HEADSET)
    }

    @Test
    fun theSavedHeadsetTakesOverWhenItConnects() {
        phone()
        router.preferred = PreferredAudioDevice(TYPE_BLUETOOTH_SCO, "AA")
        engaged(bluetooth = false)
        router.choose(1)

        devices.deviceArrives(7, TYPE_BLUETOOTH_SCO, address = "AA")

        assertThat(devices.selectedId).isEqualTo(7)
    }

    /** The session's pick outlasts nothing: after it is forgotten the saved device is back. */
    @Test
    fun theSavedDeviceReturnsWhenTheChoiceIsForgotten() {
        phone()
        router.preferred = PreferredAudioDevice(TYPE_BUILTIN_EARPIECE)
        engaged()
        router.choose(2)
        assertThat(devices.selectedId).isEqualTo(2)

        router.forgetChoice()

        assertThat(devices.selectedId).isEqualTo(1)
    }

    /** Choosing the saved device is choosing the default, so the next headset may take over. */
    @Test
    fun choosingTheSavedDeviceIsTheDefault() {
        phone()
        router.preferred = PreferredAudioDevice(TYPE_BUILTIN_EARPIECE)
        engaged()

        router.choose(1)

        assertThat(router.choice).isNull()
        assertThat(devices.selectedId).isEqualTo(1)
    }

    /** Nothing about a saved device reaches the platform without a session. */
    @Test
    fun aSavedDeviceIsNotRoutedBeforeTheRouterIsEngaged() {
        phone()
        router.preferred = PreferredAudioDevice(TYPE_BUILTIN_EARPIECE)

        router.apply()

        assertThat(devices.selectCalls).isEmpty()
        assertThat(devices.modeCalls).isEmpty()
    }

    @Test
    fun switchingBluetoothOffMovesToThePhone() {
        phone()
        devices.available[7] = TYPE_BLUETOOTH_SCO
        engaged()

        router.bluetoothAutomatic = false
        router.apply()

        assertThat(devices.selectedId).isEqualTo(2)
        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO, TYPE_BUILTIN_SPEAKER).inOrder()
    }

    /** Only a route this router took is its to give back. */
    @Test
    fun withNothingToRouteNothingIsGivenBack() {
        devices.systemSelects(null)
        engaged()

        router.disengage()

        assertThat(devices.selectCalls).isEmpty()
        assertThat(devices.clearCalls).isEqualTo(0)
    }

    /**
     * The communication mode is the session's: taken when the router engages, before the first
     * route, and handed back when it disengages.
     */
    @Test
    fun theRouterHoldsTheCommunicationModeForTheSession() {
        phone()
        assertThat(devices.inCommunicationMode).isFalse()

        engaged()
        assertThat(devices.inCommunicationMode).isTrue()
        assertThat(devices.modeCalls.first()).isTrue()

        router.disengage()
        assertThat(devices.inCommunicationMode).isFalse()
    }

    @Test
    fun aRouterThatWasNeverEngagedLeavesTheModeAlone() {
        router.apply()
        router.disengage()

        assertThat(devices.modeCalls).isEmpty()
    }

    @Test
    fun aRefusedRouteIsReported() {
        devices.available[7] = TYPE_BLUETOOTH_SCO
        devices.selectResult = false

        engaged()

        assertThat(devices.selectCalls).containsExactly(7)
        assertThat(refusals).isEqualTo(1)
        assertThat(router.isBluetoothActive).isFalse()
        assertThat(routes).isEmpty()
    }

    @Test
    fun applyIsIdempotentWhileTheRouteHolds() {
        devices.available[7] = TYPE_BLUETOOTH_SCO
        engaged()
        router.apply()

        assertThat(devices.selectCalls).containsExactly(7)
        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO)
    }

    /**
     * The system took the headset away underneath us while it is still there. The next apply has
     * to route again rather than read the earlier success back.
     */
    @Test
    fun applyRoutesAgainAfterTheSystemTookTheHeadsetAway() {
        devices.available[7] = TYPE_BLUETOOTH_SCO
        engaged()

        devices.systemSelects(null)
        router.apply()

        assertThat(devices.selectCalls).containsExactly(7, 7).inOrder()
        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO, null, TYPE_BLUETOOTH_SCO).inOrder()
    }

    /**
     * ...but only an apply does. A route change the platform raises with the same devices present
     * is reported and not fought: a phone call takes the communication device.
     */
    @Test
    fun aRouteChangeAloneIsReportedAndNotFought() {
        devices.available[7] = TYPE_BLUETOOTH_SCO
        engaged()

        devices.systemSelects(null)

        assertThat(devices.selectCalls).containsExactly(7)
        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO, null).inOrder()
    }

    /**
     * `AudioManager`'s callback is posted to the main looper and the router is main-thread-only,
     * so `select` returns with the route changed and the event still queued; the router's own
     * closing check has to tell the listener.
     */
    @Test
    fun applyReportsTheRouteItselfWhenTheSeamHasNotRaisedItsEventYet() {
        devices.available[7] = TYPE_BLUETOOTH_SCO

        engaged()
        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO)

        router.bluetoothAutomatic = false
        router.apply()
        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO, null).inOrder()
    }

    /**
     * The other ordering: the seam raises the event inline, from inside `select`. The listener hears
     * the transition once, and the event must not start a second apply.
     */
    @Test
    fun anEventRaisedInsideApplyIsOneTransitionAndOneSelection() {
        devices.notifiesOnChange = true
        devices.available[7] = TYPE_BLUETOOTH_SCO

        engaged()

        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO)
        assertThat(devices.selectCalls).containsExactly(7)
    }

    /** Routing voice with no voice to route holds an SCO link open for nothing. */
    @Test
    fun nothingIsRoutedBeforeTheRouterIsEngaged() {
        devices.available[7] = TYPE_BLUETOOTH_SCO
        router.bluetoothAutomatic = true

        router.apply()
        devices.deviceArrives(9, TYPE_BLUETOOTH_SCO)

        assertThat(devices.selectCalls).isEmpty()
        assertThat(router.activeDevice()).isNull()
    }

    @Test
    fun disengagingGivesTheRouteBackAndKeepsTheChoice() {
        phone()
        engaged()
        router.choose(1)
        assertThat(devices.selectedId).isEqualTo(1)

        router.disengage()

        assertThat(devices.clearCalls).isEqualTo(1)
        assertThat(routes).containsExactly(TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_EARPIECE, null).inOrder()
        assertThat(router.choice).isEqualTo(1)

        router.engage()

        assertThat(devices.selectCalls).containsExactly(2, 1, 1).inOrder()
    }

    /**
     * What was there when the session started is not "new": a headset switched on while the
     * connection was down does not overrule a choice the user made before it dropped.
     */
    @Test
    fun aDevicePresentWhenTheSessionStartsIsNotNew() {
        phone()
        engaged()
        router.choose(1)
        router.disengage()

        devices.deviceArrives(7, TYPE_BLUETOOTH_SCO)
        router.engage()

        assertThat(devices.selectedId).isEqualTo(1)
    }

    @Test
    fun forgettingTheChoiceReturnsToTheDefault() {
        phone()
        engaged()
        router.choose(1)

        router.forgetChoice()

        assertThat(router.choice).isNull()
        assertThat(devices.selectedId).isEqualTo(2)
    }

    @Test
    fun theUserCanChooseTheSpeakerOverAHeadset() {
        phone()
        devices.available[7] = TYPE_BLUETOOTH_SCO
        engaged()

        router.choose(2)

        assertThat(devices.selectedId).isEqualTo(2)
        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO, TYPE_BUILTIN_SPEAKER).inOrder()
        assertThat(router.activeDevice()?.id).isEqualTo(2)
    }

    @Test
    fun theUserCanChooseTheEarpieceOverTheSpeaker() {
        phone()
        engaged()

        router.choose(1)

        assertThat(devices.selectedId).isEqualTo(1)
        assertThat(routes).containsExactly(TYPE_BUILTIN_SPEAKER, TYPE_BUILTIN_EARPIECE).inOrder()
    }

    /**
     * Choosing what the default would have given anyway is going back to the default, so the next
     * headset may take over again.
     */
    @Test
    fun choosingTheDefaultDeviceIsTheDefault() {
        phone()
        engaged()
        router.choose(1)

        router.choose(2)

        assertThat(router.choice).isNull()
        assertThat(devices.selectedId).isEqualTo(2)
    }

    @Test
    fun choosingAnUnknownDeviceChangesNothing() {
        phone()
        engaged()

        router.choose(42)

        assertThat(router.choice).isNull()
        assertThat(devices.selectCalls).containsExactly(2)
    }

    /** A headset that is switched on during the call takes over. */
    @Test
    fun aBluetoothHeadsetThatArrivesTakesOverFromAChoice() {
        phone()
        engaged()
        router.choose(1)

        devices.deviceArrives(7, TYPE_BLUETOOTH_SCO, "Jabra")

        assertThat(devices.selectedId).isEqualTo(7)
        assertThat(router.activeDevice()?.name).isEqualTo("Jabra")
    }

    @Test
    fun aBluetoothHeadsetThatArrivesIsLeftAloneWhenBluetoothIsSwitchedOff() {
        phone()
        engaged(bluetooth = false)
        router.choose(1)

        devices.deviceArrives(7, TYPE_BLUETOOTH_SCO)

        assertThat(devices.selectedId).isEqualTo(1)
    }

    @Test
    fun theNewestBluetoothHeadsetWins() {
        phone()
        devices.available[7] = TYPE_BLUETOOTH_SCO
        engaged()

        devices.deviceArrives(9, TYPE_BLE_HEADSET)

        assertThat(devices.selectedId).isEqualTo(9)
    }

    /** A plugged-in headset takes over as well, and becomes the default rather than a choice. */
    @Test
    fun aWiredHeadsetThatIsPluggedInTakesOverFromAChoice() {
        phone()
        engaged()
        router.choose(1)
        assertThat(devices.selectedId).isEqualTo(1)

        devices.deviceArrives(4, TYPE_USB_HEADSET)

        assertThat(router.choice).isNull()
        assertThat(devices.selectedId).isEqualTo(4)
        assertThat(router.activeDevice()?.id).isEqualTo(4)
    }

    @Test
    fun whenTheChosenDeviceLeavesTheDefaultReturns() {
        phone()
        devices.available[4] = TYPE_WIRED_HEADSET
        engaged()
        router.choose(2)

        devices.deviceLeaves(2)

        assertThat(router.choice).isNull()
        assertThat(router.activeDevice()?.id).isEqualTo(4)
    }

    @Test
    fun whenTheBluetoothHeadsetLeavesTheNextOneIsTaken() {
        phone()
        devices.available[7] = TYPE_BLUETOOTH_SCO
        devices.available[9] = TYPE_BLUETOOTH_SCO
        engaged()

        devices.deviceLeaves(7)

        assertThat(devices.selectedId).isEqualTo(9)
    }

    @Test
    fun whenTheOnlyHeadsetLeavesTheRouteGoesBackToThePlatform() {
        phone()
        devices.available[7] = TYPE_BLUETOOTH_SCO
        engaged()

        devices.deviceLeaves(7)

        assertThat(router.isBluetoothActive).isFalse()
        assertThat(routes.last()).isEqualTo(TYPE_BUILTIN_SPEAKER)
        assertThat(router.activeDevice()?.id).isEqualTo(2)
    }

    @Test
    fun changingTheSavedDeviceMovesTheDefaultAtTheNextApply() {
        phone()
        engaged()
        assertThat(router.activeDevice()?.id).isEqualTo(2)

        router.preferred = PreferredAudioDevice(TYPE_BUILTIN_EARPIECE)
        router.apply()

        assertThat(router.activeDevice()?.id).isEqualTo(1)
    }

    @Test
    fun theChooserListsWhatThePlatformOffersInItsOrder() {
        phone()
        devices.available[7] = TYPE_BLUETOOTH_SCO
        devices.names[7] = "Jabra"
        engaged()

        assertThat(router.availableDevices()).containsExactly(
            CommunicationDevice(1, TYPE_BUILTIN_EARPIECE, ""),
            CommunicationDevice(2, TYPE_BUILTIN_SPEAKER, ""),
            CommunicationDevice(7, TYPE_BLUETOOTH_SCO, "Jabra"),
        ).inOrder()
    }

    @Test
    fun theChooserIsEmptyWithoutASession() {
        phone()

        assertThat(router.availableDevices()).isEmpty()
    }

    @Test
    fun releaseStopsListening() {
        devices.available[7] = TYPE_BLUETOOTH_SCO
        engaged()

        router.release()
        devices.systemSelects(null)

        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO)
    }

    /**
     * Release must unregister from the seam, asserted on the fake's registration count: a router
     * that stopped reporting without unregistering looks identical from [routes].
     */
    @Test
    fun releaseUnregistersAtTheSeamItRegisteredAt() {
        assertThat(devices.listenerRegistrations).isEqualTo(1)

        router.release()

        assertThat(devices.listenerRegistrations).isEqualTo(2)
        assertThat(devices.listener).isNull()
    }
}
