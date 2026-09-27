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

package se.lublin.humla.model

/**
 * A server the user can connect to, as entered or stored. [port] 0 means "look up the
 * `_mumble._tcp` SRV record"; `ServerResolver` turns it into the endpoint to connect to.
 */
data class Server(
    /** The database id, or [NOT_SAVED]. */
    val id: Long,
    /** The name the user gave it; may be empty. */
    val label: String?,
    val host: String,
    val port: Int,
    val username: String?,
    val password: String?,
) {
    /** The user-defined name, or the host when none is set. */
    val name: String get() = label?.takeIf { it.isNotEmpty() } ?: host

    /** True if the server is stored in the database. */
    val isSaved: Boolean get() = id != NOT_SAVED

    /** Kept out of logs and crash reports. */
    override fun toString(): String = "Server(id=$id, label=$label, host=$host, port=$port, username=$username)"

    companion object {
        const val NOT_SAVED = -1L
    }
}
