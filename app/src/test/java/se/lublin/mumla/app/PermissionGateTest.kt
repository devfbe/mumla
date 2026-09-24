/*
 * Copyright (C) 2026 The Mumla Authors
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
package se.lublin.mumla.app

import android.Manifest.permission.POST_NOTIFICATIONS
import android.Manifest.permission.RECORD_AUDIO
import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PermissionGateTest {

    private class FakeHost(override val sdkInt: Int = Build.VERSION_CODES.TIRAMISU) : PermissionGate.Host {
        val granted = mutableSetOf<String>()
        val rationale = mutableSetOf<String>()
        val actions = mutableListOf<String>()
        var pendingExplanation: (() -> Unit)? = null
        override var microphoneAsked = false
        override var notificationsAsked = false

        override fun isGranted(permission: String) = permission in granted
        override fun shouldShowRationale(permission: String) = permission in rationale
        override fun request(permission: String) {
            actions += "request ${permission.substringAfterLast('.')}"
        }

        override fun explainMicrophone(onContinue: () -> Unit) {
            actions += "explain"
            pendingExplanation = onContinue
        }

        override fun offerMicrophoneSettings() {
            actions += "settings"
        }

        override fun onMicrophoneDenied() {
            actions += "microphone denied"
        }

        override fun onNotificationsDenied() {
            actions += "notifications denied"
        }
    }

    private val host = FakeHost()
    private val gate = PermissionGate(host)
    private var connects = 0

    private fun connect() = gate.ensure { connects++ }

    @Test
    fun withEverythingGrantedItConnectsAtOnce() {
        host.granted += listOf(RECORD_AUDIO, POST_NOTIFICATIONS)

        connect()

        assertThat(connects).isEqualTo(1)
        assertThat(host.actions).isEmpty()
    }

    @Test
    fun theMicrophoneIsRequestedFirstThenNotificationsThenItConnects() {
        connect()
        assertThat(host.actions).containsExactly("request RECORD_AUDIO")

        host.granted += RECORD_AUDIO
        gate.onResult(RECORD_AUDIO, granted = true)
        assertThat(host.actions).containsExactly("request RECORD_AUDIO", "request POST_NOTIFICATIONS").inOrder()
        assertThat(connects).isEqualTo(0)

        gate.onResult(POST_NOTIFICATIONS, granted = true)
        assertThat(connects).isEqualTo(1)
    }

    @Test
    fun notificationsAreAskedForOnlyOnceEvenIfDenied() {
        host.granted += RECORD_AUDIO
        connect()
        gate.onResult(POST_NOTIFICATIONS, granted = false)
        assertThat(host.notificationsAsked).isTrue()
        assertThat(connects).isEqualTo(1)

        connect()

        assertThat(host.actions).containsExactly("request POST_NOTIFICATIONS")
        assertThat(connects).isEqualTo(2)
    }

    @Test
    fun deniedNotificationsAreExplainedWhenTheSystemSuggestsIt() {
        host.granted += RECORD_AUDIO
        host.rationale += POST_NOTIFICATIONS
        connect()

        gate.onResult(POST_NOTIFICATIONS, granted = false)

        assertThat(host.actions).containsExactly("request POST_NOTIFICATIONS", "notifications denied").inOrder()
        assertThat(connects).isEqualTo(1)
    }

    @Test
    fun beforeAndroid13NotificationsNeedNoPermission() {
        val oldHost = FakeHost(sdkInt = Build.VERSION_CODES.S_V2).apply { granted += RECORD_AUDIO }

        PermissionGate(oldHost).ensure { connects++ }

        assertThat(oldHost.actions).isEmpty()
        assertThat(connects).isEqualTo(1)
    }

    @Test
    fun aFirstDenialOfTheMicrophoneIsReportedAndDoesNotConnect() {
        connect()
        host.rationale += RECORD_AUDIO

        gate.onResult(RECORD_AUDIO, granted = false)

        assertThat(host.actions).containsExactly("request RECORD_AUDIO", "microphone denied").inOrder()
        assertThat(host.microphoneAsked).isTrue()
        assertThat(connects).isEqualTo(0)
    }

    @Test
    fun aDismissedFirstRequestIsReportedAsADenialNotAsPermanent() {
        connect()

        gate.onResult(RECORD_AUDIO, granted = false)

        assertThat(host.actions).containsExactly("request RECORD_AUDIO", "microphone denied").inOrder()
    }

    @Test
    fun afterADenialTheReasonIsExplainedBeforeAskingAgain() {
        host.microphoneAsked = true
        host.rationale += RECORD_AUDIO

        connect()
        assertThat(host.actions).containsExactly("explain")

        host.pendingExplanation!!.invoke()
        assertThat(host.actions).containsExactly("explain", "request RECORD_AUDIO").inOrder()
    }

    @Test
    fun denyingAgainAfterTheExplanationLeadsToTheSettings() {
        host.microphoneAsked = true
        host.rationale += RECORD_AUDIO
        connect()
        host.pendingExplanation!!.invoke()
        host.rationale -= RECORD_AUDIO

        gate.onResult(RECORD_AUDIO, granted = false)

        assertThat(host.actions).containsExactly("explain", "request RECORD_AUDIO", "settings").inOrder()
        assertThat(connects).isEqualTo(0)
    }

    @Test
    fun aPermanentlyDeniedMicrophoneLeadsStraightToTheSettings() {
        host.microphoneAsked = true

        connect()

        assertThat(host.actions).containsExactly("settings")
        assertThat(connects).isEqualTo(0)
    }

    @Test
    fun grantingAfterTheExplanationConnects() {
        host.microphoneAsked = true
        host.rationale += RECORD_AUDIO
        host.granted += POST_NOTIFICATIONS
        connect()
        host.pendingExplanation!!.invoke()

        host.granted += RECORD_AUDIO
        gate.onResult(RECORD_AUDIO, granted = true)

        assertThat(connects).isEqualTo(1)
    }

    @Test
    fun aDeniedAttemptIsForgottenSoALaterGrantDoesNotConnectIt() {
        host.granted += POST_NOTIFICATIONS
        connect()
        host.rationale += RECORD_AUDIO
        gate.onResult(RECORD_AUDIO, granted = false)

        host.granted += RECORD_AUDIO
        gate.onResult(RECORD_AUDIO, granted = true)

        assertThat(connects).isEqualTo(0)
    }

    @Test
    fun onlyTheLatestAttemptConnects() {
        host.granted += POST_NOTIFICATIONS
        var first = 0
        gate.ensure { first++ }
        connect()

        host.granted += RECORD_AUDIO
        gate.onResult(RECORD_AUDIO, granted = true)

        assertThat(first).isEqualTo(0)
        assertThat(connects).isEqualTo(1)
    }

    @Test
    fun aResultWithNoAttemptPendingDoesNothing() {
        host.granted += listOf(RECORD_AUDIO, POST_NOTIFICATIONS)

        gate.onResult(RECORD_AUDIO, granted = true)

        assertThat(connects).isEqualTo(0)
        assertThat(host.actions).isEmpty()
    }
}
