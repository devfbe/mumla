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

import se.lublin.humla.util.Constants
import java.net.MalformedURLException

/** Parses `mumble://` URLs (https://wiki.mumble.info/wiki/Mumble_URL). */
public object MumbleURLParser {
    private val URL_PATTERN =
        Regex("mumble://((?<user>[^:]+)?(:(?<password>.+?))?@)?(?<host>.+?)(:(?<port>[0-9]+?))?/")
    private const val MAX_PORT = 65535

    /**
     * @return a server with the user, password, host and port of [url].
     * @throws MalformedURLException if the URL is null, cannot be parsed or has a port outside 1..65535.
     */
    public fun parseURL(url: String?): Server {
        if (url == null) throw MalformedURLException("null URL")
        val groups = (URL_PATTERN.find(url) ?: throw MalformedURLException()).groups
        val port = groups["port"]?.value?.let(::parsePort) ?: Constants.DEFAULT_PORT
        val host = checkNotNull(groups["host"]).value
        return Server(-1, null, host, port, groups["user"]?.value, groups["password"]?.value)
    }

    private fun parsePort(port: String): Int {
        val value = port.toIntOrNull() ?: throw MalformedURLException("Invalid port: $port")
        if (value !in 1..MAX_PORT) throw MalformedURLException("Port out of range: $value")
        return value
    }
}
