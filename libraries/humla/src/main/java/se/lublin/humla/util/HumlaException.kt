/*
 * Copyright (C) 2014 Andrew Comminos
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
package se.lublin.humla.util

import se.lublin.humla.protobuf.Mumble

/** Why a connection ended, with the server's reject or kick message when it sent one. */
class HumlaException private constructor(
    message: String?,
    cause: Throwable?,
    val reason: HumlaDisconnectReason,
    /** The server's reject, when that ended the connection. */
    @Transient val reject: Mumble.Reject? = null,
    /** The kick or ban, when that ended the connection. */
    @Transient val userRemove: Mumble.UserRemove? = null,
) : Exception(message, cause) {

    constructor(message: String, cause: Throwable?, reason: HumlaDisconnectReason) :
        this(message, cause, reason, null, null)

    constructor(message: String, reason: HumlaDisconnectReason) : this(message, null, reason, null, null)

    constructor(cause: Throwable, reason: HumlaDisconnectReason) : this(cause.toString(), cause, reason, null, null)

    constructor(reject: Mumble.Reject) :
        this("Rejected: " + reject.reason, null, HumlaDisconnectReason.REJECT, reject = reject)

    constructor(userRemove: Mumble.UserRemove) : this(
        (if (userRemove.ban) "Banned: " else "Kicked: ") + userRemove.reason,
        null,
        HumlaDisconnectReason.USER_REMOVE,
        userRemove = userRemove,
    )

    enum class HumlaDisconnectReason {
        REJECT,
        USER_REMOVE,
        CONNECTION_ERROR,
        OTHER_ERROR,
    }
}
