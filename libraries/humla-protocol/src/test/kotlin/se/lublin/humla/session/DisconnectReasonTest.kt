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

package se.lublin.humla.session

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.exception.HumlaException
import se.lublin.humla.protobuf.Mumble
import java.io.IOException

/** How the connection's exceptions become reasons; plain JVM. */
class DisconnectReasonTest {
    private val names = mapOf(4 to "Mod")

    private fun reasonOf(e: HumlaException) = disconnectReasonOf(e) { names[it] }

    @Test
    fun aRejectKeepsItsTypeAndTheServersText() {
        val expected = mapOf(
            Mumble.Reject.RejectType.None to RejectType.UNKNOWN,
            Mumble.Reject.RejectType.WrongVersion to RejectType.WRONG_VERSION,
            Mumble.Reject.RejectType.InvalidUsername to RejectType.INVALID_USERNAME,
            Mumble.Reject.RejectType.WrongUserPW to RejectType.WRONG_USER_PASSWORD,
            Mumble.Reject.RejectType.WrongServerPW to RejectType.WRONG_SERVER_PASSWORD,
            Mumble.Reject.RejectType.UsernameInUse to RejectType.USERNAME_IN_USE,
            Mumble.Reject.RejectType.ServerFull to RejectType.SERVER_FULL,
            Mumble.Reject.RejectType.NoCertificate to RejectType.NO_CERTIFICATE,
            Mumble.Reject.RejectType.AuthenticatorFail to RejectType.AUTHENTICATOR_FAIL,
            Mumble.Reject.RejectType.NoNewConnections to RejectType.NO_NEW_CONNECTIONS,
        )
        assertThat(expected.keys).containsExactlyElementsIn(Mumble.Reject.RejectType.entries)

        for ((wire, type) in expected) {
            val reject = Mumble.Reject.newBuilder().setType(wire).setReason("because").build()
            assertThat(reasonOf(HumlaException(reject))).isEqualTo(DisconnectReason.Rejected(type, "because"))
        }
    }

    @Test
    fun aKickNamesItsActorAndSaysWhetherItWasABan() {
        val kick = Mumble.UserRemove.newBuilder().setSession(1).setActor(4).setReason("spam").setBan(true).build()

        assertThat(reasonOf(HumlaException(kick))).isEqualTo(DisconnectReason.Kicked("spam", "Mod", banned = true))
    }

    @Test
    fun aKickWithoutAnActorOrOneTheModelDoesNotKnowHasNoName() {
        val anonymous = Mumble.UserRemove.newBuilder().setSession(1).setReason("bye").build()
        val unknown = Mumble.UserRemove.newBuilder().setSession(1).setActor(9).setReason("bye").build()

        assertThat(reasonOf(HumlaException(anonymous))).isEqualTo(DisconnectReason.Kicked("bye", null, banned = false))
        assertThat(reasonOf(HumlaException(unknown))).isEqualTo(DisconnectReason.Kicked("bye", null, banned = false))
    }

    /** Only a network failure is a [DisconnectReason.Network], the one an automatic reconnect retries. */
    @Test
    fun connectionErrorsAreNetworkFailuresAndTheRestAreFailures() {
        val cause = IOException("reset")

        assertThat(reasonOf(HumlaException("lost", cause, HumlaException.HumlaDisconnectReason.CONNECTION_ERROR)))
            .isEqualTo(DisconnectReason.Network("lost", cause))
        assertThat(reasonOf(HumlaException("bad key", cause, HumlaException.HumlaDisconnectReason.OTHER_ERROR)))
            .isEqualTo(DisconnectReason.Failed("bad key", cause))
    }
}
