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

package se.lublin.mumla.util

import android.os.Bundle
import androidx.core.os.bundleOf
import se.lublin.humla.model.Server

private const val ID = "id"
private const val LABEL = "label"
private const val HOST = "host"
private const val PORT = "port"
private const val USERNAME = "username"
private const val PASSWORD = "password"

/** Stores [server] under [key], field by field. */
fun Bundle.putServer(key: String, server: Server) = putBundle(
    key,
    bundleOf(
        ID to server.id,
        LABEL to server.label,
        HOST to server.host,
        PORT to server.port,
        USERNAME to server.username,
        PASSWORD to server.password,
    ),
)

/** The server [putServer] stored under [key], or null. */
fun Bundle.getServer(key: String): Server? = getBundle(key)?.let {
    Server(
        it.getLong(ID),
        it.getString(LABEL),
        it.getString(HOST).orEmpty(),
        it.getInt(PORT),
        it.getString(USERNAME),
        it.getString(PASSWORD),
    )
}
