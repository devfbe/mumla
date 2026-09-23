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
 * The phone app's audio chooser as one router with one wish (contract 9b, point 15): the user's
 * explicit [AudioRouter.choice] replaces `ScoRouter.wanted`, and everything else is the automatic
 * default - a connected Bluetooth headset when [AudioRouter.bluetoothAutomatic] allows it, and
 * otherwise the route the platform takes when nobody routes, which the router leaves unclaimed.
 *
 * No permission appears anywhere in this class, and that is a decision rather than an omission -
 * see the class doc of [AudioRouter]. There is no input by which a caller could refuse the route on
 * that ground, so there is no corner of it to drive.
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

    // ---------------------------------------------------------------- the automatic default

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

    /** LE Audio headsets are Bluetooth headsets to the user, whatever the platform calls them. */
    @Test
    fun aLeAudioHeadsetIsABluetoothHeadsetToo() {
        phone()
        devices.available[8] = TYPE_BLE_HEADSET

        engaged()

        assertThat(devices.selectedId).isEqualTo(8)
    }

    /**
     * Without a headset the automatic default is the one the platform takes by itself - the
     * speaker for a media-stream track, a plugged-in headset before it - so the router routes
     * nothing, and a speaker or wired headset is never a refusal.
     */
    @Test
    fun withoutAHeadsetNothingIsRoutedAndNothingIsRefused() {
        phone()
        devices.available[4] = TYPE_WIRED_HEADSET

        engaged()

        assertThat(devices.selectCalls).isEmpty()
        assertThat(devices.clearCalls).isEqualTo(0)
        assertThat(refusals).isEqualTo(0)
        assertThat(routes).isEmpty()
    }

    @Test
    fun aHeadsetIsNotTakenWhenTheUserSwitchedThatOff() {
        phone()
        devices.available[7] = TYPE_BLUETOOTH_SCO

        engaged(bluetooth = false)

        assertThat(devices.selectCalls).isEmpty()
    }

    @Test
    fun switchingBluetoothOffGivesBackTheRouteItTook() {
        phone()
        devices.available[7] = TYPE_BLUETOOTH_SCO
        engaged()

        router.bluetoothAutomatic = false
        router.apply()

        assertThat(devices.clearCalls).isEqualTo(1)
        assertThat(devices.selectedId).isNull()
        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO, null).inOrder()
    }

    /**
     * Only a route this router took is its to give back. Clearing whatever the platform chose would
     * take the user off their own speaker or wired headset for a reason they never gave.
     */
    @Test
    fun aRouteSomebodyElseTookIsLeftAlone() {
        phone()
        devices.systemSelects(2)

        engaged(bluetooth = false)
        router.apply()

        assertThat(devices.clearCalls).isEqualTo(0)
        assertThat(devices.selectedId).isEqualTo(2)
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
     * is reported and not fought: a phone call takes the communication device, and a router that
     * re-selected on every route event would argue with the dialler.
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
     * The production ordering: `AudioManager`'s callback is posted to the main looper and the router
     * is main-thread-only, so while `apply()` runs the platform event cannot. `select` returns with
     * the route already changed and the event still queued, and the only thing that tells the
     * listener in time is the router's own closing check. (Found by mutation on `ScoRouter`.)
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
     * And the other ordering: the seam raises the event inline, from inside `select`. Both reports
     * describe one transition, so the listener hears it once - and the event arriving in the middle
     * of an apply must not start a second one that selects again.
     */
    @Test
    fun anEventRaisedInsideApplyIsOneTransitionAndOneSelection() {
        devices.notifiesOnChange = true
        devices.available[7] = TYPE_BLUETOOTH_SCO

        engaged()

        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO)
        assertThat(devices.selectCalls).containsExactly(7)
    }

    // ---------------------------------------------------------------- the session

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
        assertThat(routes).containsExactly(TYPE_BUILTIN_EARPIECE, null).inOrder()
        assertThat(router.choice).isEqualTo(1)

        router.engage()

        assertThat(devices.selectCalls).containsExactly(1, 1).inOrder()
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
        assertThat(devices.clearCalls).isEqualTo(1)
    }

    // ---------------------------------------------------------------- the user's choice

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
        assertThat(routes).containsExactly(TYPE_BUILTIN_EARPIECE)
    }

    /**
     * Choosing what the default would have given anyway is going back to the default: the route is
     * handed back to the platform, and the next headset may take over again.
     */
    @Test
    fun choosingTheDefaultDeviceIsTheDefault() {
        phone()
        engaged()
        router.choose(1)

        router.choose(2)

        assertThat(router.choice).isNull()
        assertThat(devices.clearCalls).isEqualTo(1)
        assertThat(devices.selectedId).isNull()
    }

    @Test
    fun choosingAnUnknownDeviceChangesNothing() {
        phone()
        engaged()

        router.choose(42)

        assertThat(router.choice).isNull()
        assertThat(devices.selectCalls).isEmpty()
    }

    // ---------------------------------------------------------------- plugging in and out

    /** The phone app's rule: a headset that is switched on during the call takes over. */
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

    /**
     * A plugged-in headset is where the platform plays anyway, so taking over means handing the
     * route back rather than selecting it.
     */
    @Test
    fun aWiredHeadsetThatIsPluggedInTakesOverFromAChoice() {
        phone()
        engaged()
        router.choose(1)
        assertThat(devices.selectedId).isEqualTo(1)

        devices.deviceArrives(4, TYPE_USB_HEADSET)

        assertThat(router.choice).isNull()
        assertThat(devices.selectedId).isNull()
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
        assertThat(routes.last()).isNull()
        assertThat(router.activeDevice()?.id).isEqualTo(2)
    }

    // ---------------------------------------------------------------- what the chooser shows

    @Test
    fun theDefaultShownIsTheSpeakerOrInHandsetModeTheEarpiece() {
        phone()
        engaged()
        assertThat(router.activeDevice()?.id).isEqualTo(2)

        router.handset = true

        assertThat(router.activeDevice()?.id).isEqualTo(1)
    }

    @Test
    fun aPluggedInHeadsetIsTheDefaultShown() {
        phone()
        devices.available[4] = TYPE_WIRED_HEADSET
        engaged()

        assertThat(router.activeDevice()?.id).isEqualTo(4)
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

    // ---------------------------------------------------------------- teardown

    @Test
    fun releaseStopsListening() {
        devices.available[7] = TYPE_BLUETOOTH_SCO
        engaged()

        router.release()
        devices.systemSelects(null)

        assertThat(routes).containsExactly(TYPE_BLUETOOTH_SCO)
    }

    /**
     * Release is the teardown of a registration the constructor made, so it has to reach the seam
     * and not merely a flag of its own: asserted on the fake's own registration count, because a
     * router that stopped reporting without unregistering looks identical from [routes].
     */
    @Test
    fun releaseUnregistersAtTheSeamItRegisteredAt() {
        assertThat(devices.listenerRegistrations).isEqualTo(1)

        router.release()

        assertThat(devices.listenerRegistrations).isEqualTo(2)
        assertThat(devices.listener).isNull()
    }
}
