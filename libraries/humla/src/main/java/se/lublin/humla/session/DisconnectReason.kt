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

import se.lublin.humla.exception.HumlaException
import se.lublin.humla.protobuf.Mumble
import java.security.cert.X509Certificate

/** Why a connection ended without the user asking for it. */
sealed interface DisconnectReason {
    /** The server refused the connection; [message] is its own text, possibly empty. */
    data class Rejected(val type: RejectType, val message: String) : DisconnectReason

    /** [actor] (null if unknown) kicked, or with [banned] banned, the local user. */
    data class Kicked(val reason: String, val actor: String?, val banned: Boolean) : DisconnectReason

    /** The server's certificate chain is not trusted. */
    data class TlsUntrusted(val chain: List<X509Certificate>) : DisconnectReason

    /**
     * The server presented a certificate that differs from the one pinned for its host, and the
     * system does not trust it either. Possibly an attack; never accept it silently.
     */
    data class TlsCertificateChanged(val chain: List<X509Certificate>) : DisconnectReason

    /** The link failed: refused, reset or timed out. The only reason an automatic reconnect retries. */
    data class Network(val message: String, val cause: Throwable?) : DisconnectReason

    /** Anything else, such as an unreadable client certificate or a protocol error. */
    data class Failed(val message: String, val cause: Throwable?) : DisconnectReason
}

/** The server's reason for a [DisconnectReason.Rejected]. */
enum class RejectType {
    UNKNOWN,
    WRONG_VERSION,
    INVALID_USERNAME,
    WRONG_USER_PASSWORD,
    WRONG_SERVER_PASSWORD,
    USERNAME_IN_USE,
    SERVER_FULL,
    NO_CERTIFICATE,
    AUTHENTICATOR_FAIL,
    NO_NEW_CONNECTIONS,
}

/** The reason [e] stands for; [actorName] names the session of a kick's actor. */
internal fun disconnectReasonOf(e: HumlaException, actorName: (Int) -> String?): DisconnectReason {
    val reject = e.reject
    val removal = e.userRemove
    return when {
        reject != null -> DisconnectReason.Rejected(rejectTypeOf(reject.type), reject.reason.orEmpty())
        removal != null -> DisconnectReason.Kicked(
            removal.reason.orEmpty(),
            if (removal.hasActor()) actorName(removal.actor) else null,
            removal.ban,
        )
        e.reason == HumlaException.HumlaDisconnectReason.CONNECTION_ERROR ->
            DisconnectReason.Network(e.message.orEmpty(), e.cause)
        else -> DisconnectReason.Failed(e.message.orEmpty(), e.cause)
    }
}

private fun rejectTypeOf(type: Mumble.Reject.RejectType?): RejectType = when (type) {
    Mumble.Reject.RejectType.WrongVersion -> RejectType.WRONG_VERSION
    Mumble.Reject.RejectType.InvalidUsername -> RejectType.INVALID_USERNAME
    Mumble.Reject.RejectType.WrongUserPW -> RejectType.WRONG_USER_PASSWORD
    Mumble.Reject.RejectType.WrongServerPW -> RejectType.WRONG_SERVER_PASSWORD
    Mumble.Reject.RejectType.UsernameInUse -> RejectType.USERNAME_IN_USE
    Mumble.Reject.RejectType.ServerFull -> RejectType.SERVER_FULL
    Mumble.Reject.RejectType.NoCertificate -> RejectType.NO_CERTIFICATE
    Mumble.Reject.RejectType.AuthenticatorFail -> RejectType.AUTHENTICATOR_FAIL
    Mumble.Reject.RejectType.NoNewConnections -> RejectType.NO_NEW_CONNECTIONS
    Mumble.Reject.RejectType.None, null -> RejectType.UNKNOWN
}
