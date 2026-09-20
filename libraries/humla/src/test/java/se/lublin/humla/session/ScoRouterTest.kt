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

import android.media.AudioDeviceInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.testutil.FakeCommunicationDevices

/**
 * Spec A4's two states, kept apart: what the user asked for ([ScoRouter.wanted]) and what the
 * platform routes to ([ScoRouter.isActive]).
 *
 * No permission appears anywhere in this class, and that is a decision rather than an omission -
 * see the class doc of [ScoRouter]. There is no input by which a caller could refuse the route on
 * that ground, so there is no corner of it to drive.
 */
class ScoRouterTest {
    private val devices = FakeCommunicationDevices()
    private val activeChanges = mutableListOf<Boolean>()
    private var unavailable = 0
    private val router = ScoRouter(devices, object : ScoRouter.Listener {
        override fun onScoActiveChanged(active: Boolean) { activeChanges += active }
        override fun onScoUnavailable() { unavailable++ }
    })

    @Test
    fun applyWithWantedSelectsTheFirstScoDevice() {
        devices.available[3] = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        devices.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        devices.available[9] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        router.wanted = true
        router.apply()

        assertThat(devices.selectedId).isEqualTo(7)
        assertThat(router.isActive).isTrue()
        assertThat(activeChanges).containsExactly(true)
        assertThat(unavailable).isEqualTo(0)
    }

    /**
     * The speaker is not a headset. Without this the type filter could be deleted and
     * applyWithWantedSelectsTheFirstScoDevice would still pass on the *first* device in the map.
     */
    @Test
    fun aDeviceOfAnotherTypeIsNeverSelectedEvenWhenItIsTheOnlyOne() {
        devices.available[3] = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        devices.available[4] = AudioDeviceInfo.TYPE_WIRED_HEADSET

        router.wanted = true
        router.apply()

        assertThat(devices.selectCalls).isEmpty()
        assertThat(unavailable).isEqualTo(1)
    }

    @Test
    fun applyWithoutAnyScoDeviceReportsUnavailableAndKeepsWanted() {
        devices.available[3] = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER

        router.wanted = true
        router.apply()

        assertThat(unavailable).isEqualTo(1)
        assertThat(router.wanted).isTrue()
        assertThat(router.isActive).isFalse()
        assertThat(activeChanges).isEmpty()
    }

    @Test
    fun rejectedSelectionIsReportedAsUnavailable() {
        devices.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        devices.selectResult = false

        router.wanted = true
        router.apply()

        assertThat(devices.selectCalls).containsExactly(7)
        assertThat(unavailable).isEqualTo(1)
        assertThat(router.isActive).isFalse()
        assertThat(activeChanges).isEmpty()
    }

    @Test
    fun applyWithWantedFalseClearsAnActiveScoRoute() {
        devices.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        router.wanted = true
        router.apply()

        router.wanted = false
        router.apply()

        assertThat(devices.clearCalls).isEqualTo(1)
        assertThat(devices.selectedId).isNull()
        assertThat(activeChanges).containsExactly(true, false).inOrder()
    }

    @Test
    fun applyWithWantedFalseAndNothingActiveDoesNotTouchTheRoute() {
        router.wanted = false
        router.apply()

        assertThat(devices.clearCalls).isEqualTo(0)
        assertThat(activeChanges).isEmpty()
    }

    /**
     * The fourth corner of (wanted, isActive): not wanted, and the platform is on something that is
     * not a headset. Clearing there would take the user off whatever they are on for no reason.
     */
    @Test
    fun applyWithWantedFalseLeavesANonScoRouteAlone() {
        devices.available[3] = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        devices.systemSelects(3)

        router.wanted = false
        router.apply()

        assertThat(devices.clearCalls).isEqualTo(0)
        assertThat(devices.selectedId).isEqualTo(3)
    }

    @Test
    fun applyIsIdempotentWhileScoIsActive() {
        devices.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        router.wanted = true
        router.apply()
        router.apply()

        assertThat(devices.selectCalls).containsExactly(7)
        assertThat(activeChanges).containsExactly(true)
    }

    /**
     * The other half of idempotence, and the one the four-corner rule asks for: wanted, and the
     * system took the headset away underneath us. The next apply has to route again rather than
     * read the earlier success back.
     */
    @Test
    fun applyRoutesAgainAfterTheSystemTookTheHeadsetAway() {
        devices.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        router.wanted = true
        router.apply()

        devices.systemSelects(null)
        router.apply()

        assertThat(devices.selectCalls).containsExactly(7, 7).inOrder()
        assertThat(activeChanges).containsExactly(true, false, true).inOrder()
    }

    @Test
    fun systemRouteChangesAreReportedOncePerTransition() {
        devices.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        devices.systemSelects(7)
        devices.systemSelects(7)
        devices.systemSelects(null)

        assertThat(activeChanges).containsExactly(true, false).inOrder()
    }

    /**
     * The user's wish survives a headset that walks away: the platform state changes, [wanted] does
     * not, and the next apply routes again. This is the half of spec A4 that the old
     * `startBluetoothSco` world lost on every disconnect.
     */
    @Test
    fun aSystemRouteChangeNeverTouchesWhatTheUserAskedFor() {
        devices.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        router.wanted = true
        router.apply()

        devices.systemSelects(null)

        assertThat(router.wanted).isTrue()
        assertThat(router.isActive).isFalse()
    }

    @Test
    fun releaseStopsListening() {
        devices.available[7] = AudioDeviceInfo.TYPE_BLUETOOTH_SCO

        router.release()
        devices.systemSelects(7)

        assertThat(activeChanges).isEmpty()
    }

    /**
     * Release is the teardown of a registration the constructor made, so it has to reach the seam
     * and not merely a flag of its own: asserted on the fake's own registration count, because a
     * router that stopped reporting without unregistering looks identical from [activeChanges].
     */
    @Test
    fun releaseUnregistersAtTheSeamItRegisteredAt() {
        assertThat(devices.listenerRegistrations).isEqualTo(1)

        router.release()

        assertThat(devices.listenerRegistrations).isEqualTo(2)
        assertThat(devices.listener).isNull()
    }
}
