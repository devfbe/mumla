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

import androidx.annotation.StringRes
import se.lublin.humla.R
import se.lublin.humla.net.ConnectionWarning

@get:StringRes
internal val ConnectionWarning.messageRes: Int
    get() = when (this) {
        ConnectionWarning.UDP_UNAVAILABLE -> R.string.udp_warning_unavailable
        ConnectionWarning.UDP_SEND_FAILED -> R.string.udp_warning_send_failed
        ConnectionWarning.UDP_RECEIVE_FAILED -> R.string.udp_warning_receive_failed
        ConnectionWarning.UDP_PING_TIMEOUT -> R.string.udp_warning_ping_timeout
        ConnectionWarning.UDP_RESTORED -> R.string.udp_warning_restored
        ConnectionWarning.UDP_THREAD_FAILED -> R.string.udp_warning_thread_failed
        ConnectionWarning.NO_OPUS -> R.string.codec_warning_no_opus
    }
