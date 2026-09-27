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

import androidx.annotation.StringRes
import se.lublin.humla.session.DisconnectReason
import se.lublin.humla.session.RejectType
import se.lublin.mumla.R
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import javax.net.ssl.SSLException

/** What the user can change in the error dialog before retrying. */
enum class FailureInput { NONE, USERNAME, PASSWORD }

/** A kind of failed connection, as the error dialog presents it. */
enum class ConnectionFailure(
    @param:StringRes val title: Int,
    @param:StringRes val message: Int,
    val input: FailureInput = FailureInput.NONE,
) {
    NAME_TAKEN(R.string.failure_name_taken_title, R.string.failure_name_taken, FailureInput.USERNAME),
    INVALID_NAME(R.string.failure_invalid_name_title, R.string.failure_invalid_name, FailureInput.USERNAME),
    WRONG_PASSWORD(R.string.failure_wrong_password_title, R.string.failure_wrong_password, FailureInput.PASSWORD),
    UNREACHABLE(R.string.failure_unreachable_title, R.string.failure_unreachable),
    TIMEOUT(R.string.failure_timeout_title, R.string.failure_timeout),
    CERTIFICATE_REQUIRED(R.string.failure_certificate_required_title, R.string.failure_certificate_required),
    CERTIFICATE_REJECTED(R.string.failure_certificate_rejected_title, R.string.failure_certificate_rejected),
    SERVER_FULL(R.string.failure_server_full_title, R.string.failure_server_full),
    VERSION_MISMATCH(R.string.failure_version_title, R.string.failure_version),
    TLS(R.string.failure_tls_title, R.string.failure_tls),
    KICKED(R.string.failure_kicked_title, R.string.failure_kicked),
    BANNED(R.string.failure_banned_title, R.string.failure_banned),
    GENERIC(R.string.failure_generic_title, R.string.failure_generic),
}

/** A [failure] and the server's or the system's own words about it, if any. */
data class FailureUi(val failure: ConnectionFailure, val detail: String?)

/** How the error dialog presents a connection that ended for [reason]. */
fun connectionFailureUi(reason: DisconnectReason): FailureUi = when (reason) {
    is DisconnectReason.Rejected -> FailureUi(rejectFailure(reason.type), reason.message.ifEmpty { null })
    is DisconnectReason.Kicked -> FailureUi(
        if (reason.banned) ConnectionFailure.BANNED else ConnectionFailure.KICKED,
        reason.reason.ifEmpty { null },
    )
    is DisconnectReason.TlsUntrusted, is DisconnectReason.TlsCertificateChanged ->
        FailureUi(ConnectionFailure.TLS, null)
    is DisconnectReason.Network -> FailureUi(
        when (reason.cause) {
            is SocketTimeoutException -> ConnectionFailure.TIMEOUT
            is SSLException -> ConnectionFailure.TLS
            else -> ConnectionFailure.UNREACHABLE
        },
        detail(reason.message, reason.cause),
    )
    is DisconnectReason.Failed -> FailureUi(
        // The client certificate is the only security material read before the link is up.
        if (reason.cause is GeneralSecurityException) {
            ConnectionFailure.CERTIFICATE_REJECTED
        } else {
            ConnectionFailure.GENERIC
        },
        detail(reason.message, reason.cause),
    )
}

private fun rejectFailure(type: RejectType): ConnectionFailure = when (type) {
    RejectType.USERNAME_IN_USE -> ConnectionFailure.NAME_TAKEN
    RejectType.INVALID_USERNAME -> ConnectionFailure.INVALID_NAME
    RejectType.WRONG_USER_PASSWORD, RejectType.WRONG_SERVER_PASSWORD -> ConnectionFailure.WRONG_PASSWORD
    RejectType.SERVER_FULL -> ConnectionFailure.SERVER_FULL
    RejectType.NO_CERTIFICATE -> ConnectionFailure.CERTIFICATE_REQUIRED
    RejectType.WRONG_VERSION -> ConnectionFailure.VERSION_MISMATCH
    RejectType.UNKNOWN, RejectType.AUTHENTICATOR_FAIL, RejectType.NO_NEW_CONNECTIONS -> ConnectionFailure.GENERIC
}

/** [message] and the [cause]'s own message, joined; null if neither says anything. */
private fun detail(message: String, cause: Throwable?): String? = listOf(message, cause?.message.orEmpty())
    .filter { it.isNotEmpty() }
    .distinct()
    .joinToString(": ")
    .ifEmpty { null }
