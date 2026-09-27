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

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.RejectType
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLException

class ConnectionFailureTest {

    private fun rejected(type: RejectType, message: String = "") =
        connectionFailureUi(DisconnectReason.Rejected(type, message))

    @Test
    fun everyRejectTypeHasItsFailure() {
        val expected = mapOf(
            RejectType.UNKNOWN to ConnectionFailure.GENERIC,
            RejectType.WRONG_VERSION to ConnectionFailure.VERSION_MISMATCH,
            RejectType.INVALID_USERNAME to ConnectionFailure.INVALID_NAME,
            RejectType.WRONG_USER_PASSWORD to ConnectionFailure.WRONG_PASSWORD,
            RejectType.WRONG_SERVER_PASSWORD to ConnectionFailure.WRONG_PASSWORD,
            RejectType.USERNAME_IN_USE to ConnectionFailure.NAME_TAKEN,
            RejectType.SERVER_FULL to ConnectionFailure.SERVER_FULL,
            RejectType.NO_CERTIFICATE to ConnectionFailure.CERTIFICATE_REQUIRED,
            RejectType.AUTHENTICATOR_FAIL to ConnectionFailure.GENERIC,
            RejectType.NO_NEW_CONNECTIONS to ConnectionFailure.GENERIC,
        )
        assertThat(expected.keys).containsExactlyElementsIn(RejectType.entries)

        expected.forEach { (type, failure) -> assertThat(rejected(type).failure).isEqualTo(failure) }
    }

    @Test
    fun theNameFailuresAskForANameAndAWrongPasswordForAPassword() {
        val inputs = ConnectionFailure.entries.associateWith { it.input }

        assertThat(inputs.filterValues { it == FailureInput.USERNAME }.keys)
            .containsExactly(ConnectionFailure.NAME_TAKEN, ConnectionFailure.INVALID_NAME)
        assertThat(inputs.filterValues { it == FailureInput.PASSWORD }.keys)
            .containsExactly(ConnectionFailure.WRONG_PASSWORD)
    }

    @Test
    fun everyFailureHasItsOwnTitleAndMessage() {
        val failures = ConnectionFailure.entries

        assertThat(failures.map { it.title }.toSet()).hasSize(failures.size)
        assertThat(failures.map { it.message }.toSet()).hasSize(failures.size)
    }

    @Test
    fun theServersOwnTextIsTheDetail() {
        assertThat(rejected(RejectType.SERVER_FULL, "50 users max").detail).isEqualTo("50 users max")
        assertThat(rejected(RejectType.SERVER_FULL).detail).isNull()
    }

    @Test
    fun aKickOrBanCarriesItsReason() {
        val kicked = connectionFailureUi(DisconnectReason.Kicked("spam", "admin", banned = false))
        val banned = connectionFailureUi(DisconnectReason.Kicked("", null, banned = true))

        assertThat(kicked).isEqualTo(FailureUi(ConnectionFailure.KICKED, "spam"))
        assertThat(banned).isEqualTo(FailureUi(ConnectionFailure.BANNED, null))
    }

    @Test
    fun certificateTrustProblemsAreTlsFailures() {
        assertThat(connectionFailureUi(DisconnectReason.TlsUntrusted(emptyList<X509Certificate>())).failure)
            .isEqualTo(ConnectionFailure.TLS)
        assertThat(connectionFailureUi(DisconnectReason.TlsCertificateChanged(emptyList<X509Certificate>())).failure)
            .isEqualTo(ConnectionFailure.TLS)
    }

    @Test
    fun aNetworkFailureIsATimeoutATlsFailureOrUnreachable() {
        fun network(cause: Throwable?) = connectionFailureUi(DisconnectReason.Network("Could not connect", cause))

        assertThat(network(SocketTimeoutException("timed out")))
            .isEqualTo(FailureUi(ConnectionFailure.TIMEOUT, "Could not connect: timed out"))
        assertThat(network(SSLException("bad record")).failure).isEqualTo(ConnectionFailure.TLS)
        assertThat(network(IOException("ECONNREFUSED")).failure).isEqualTo(ConnectionFailure.UNREACHABLE)
        assertThat(network(null)).isEqualTo(FailureUi(ConnectionFailure.UNREACHABLE, "Could not connect"))
    }

    @Test
    fun anUnusableClientCertificateIsRejectedAndAnythingElseGeneric() {
        val certificate =
            connectionFailureUi(DisconnectReason.Failed("Could not read certificate", CertificateException()))
        val other = connectionFailureUi(DisconnectReason.Failed("", null))

        assertThat(certificate.failure).isEqualTo(ConnectionFailure.CERTIFICATE_REJECTED)
        assertThat(other).isEqualTo(FailureUi(ConnectionFailure.GENERIC, null))
    }
}
