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

package se.lublin.humla.net

import se.lublin.humla.R

/**
 * Connection-level warnings the service shows in the chat log; each maps to a string resource.
 *
 * The connection raises the value, the consumer resolves the text: a warning raised on the protocol
 * thread must not depend on a Context, and a translated string cannot be built where there is none.
 */
enum class ConnectionWarning(val messageRes: Int) {
    UDP_UNAVAILABLE(R.string.udp_warning_unavailable),
    UDP_SEND_FAILED(R.string.udp_warning_send_failed),
    UDP_RECEIVE_FAILED(R.string.udp_warning_receive_failed),
    UDP_PING_TIMEOUT(R.string.udp_warning_ping_timeout),
    UDP_RESTORED(R.string.udp_warning_restored),
    UDP_THREAD_FAILED(R.string.udp_warning_thread_failed),
}
