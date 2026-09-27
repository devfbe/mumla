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

/**
 * An entry of the public server list. Not a data class: it is a [Server], whose username the
 * user edits before connecting, so structural equality would not hold.
 */
@Suppress("LongParameterList") // One parameter per attribute of the list's entries.
class PublicServer(
    name: String?,
    val ca: String?,
    val country: String?,
    val countryCode: String?,
    ip: String,
    port: Int,
    val region: String?,
    val url: String?,
) : Server(-1, name, ip, port, "", "")
