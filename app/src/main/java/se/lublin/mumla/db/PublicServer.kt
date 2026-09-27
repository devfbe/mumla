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
package se.lublin.mumla.db

import se.lublin.humla.model.Server

/** An entry of the public server list: the [server] to connect to and where it is. */
data class PublicServer(
    val server: Server,
    val ca: String?,
    val country: String?,
    val countryCode: String?,
    val region: String?,
    val url: String?,
) {
    @Suppress("LongParameterList") // One parameter per attribute of the list's entries.
    constructor(
        name: String?,
        ca: String?,
        country: String?,
        countryCode: String?,
        ip: String,
        port: Int,
        region: String?,
        url: String?,
    ) : this(Server(Server.NOT_SAVED, name, ip, port, "", ""), ca, country, countryCode, region, url)

    val name: String get() = server.name
}
