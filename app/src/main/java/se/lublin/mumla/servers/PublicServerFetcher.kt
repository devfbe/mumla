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

package se.lublin.mumla.servers

import android.util.Xml
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import se.lublin.humla.Constants
import se.lublin.mumla.db.PublicServer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the public Mumble server list. */
class PublicServerFetcher(
    private val openConnection: () -> HttpURLConnection = {
        URL(MUMBLE_PUBLIC_URL).openConnection() as HttpURLConnection
    },
) {
    /** Returns the parsed list, or null when it could not be fetched or parsed. */
    suspend fun fetch(): List<PublicServer>? = withContext(Dispatchers.IO) { fetchBlocking() }

    @VisibleForTesting
    internal fun fetchBlocking(): List<PublicServer>? = try {
        val connection = openConnection()
        try {
            connection.requestMethod = "GET"
            connection.addRequestProperty("version", Constants.PROTOCOL_STRING)
            connection.connect()
            connection.inputStream.use { stream ->
                val parser = Xml.newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(stream, "UTF-8")
                parser.nextTag()
                parseServers(parser)
            }
        } finally {
            connection.disconnect()
        }
    } catch (e: IOException) {
        e.printStackTrace()
        null
    } catch (e: XmlPullParserException) {
        e.printStackTrace()
        null
    }

    private fun parseServers(parser: XmlPullParser): List<PublicServer> {
        val servers = ArrayList<PublicServer>()
        parser.require(XmlPullParser.START_TAG, null, "servers")
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            readEntry(parser)?.let(servers::add)
        }
        parser.require(XmlPullParser.END_TAG, null, "servers")
        return servers
    }

    /** Reads one server element; null when it has no address or its port is not a number. */
    private fun readEntry(parser: XmlPullParser): PublicServer? {
        fun attr(name: String): String? = parser.getAttributeValue(null, name)
        val port = attr("port")?.toIntOrNull()
        val ip = attr("ip")
        val server = if (port != null && ip != null) {
            PublicServer(
                attr("name"), attr("ca"), attr("continent_code"), attr("country"),
                attr("country_code"), ip, port, attr("region"), attr("url"),
            )
        } else {
            null
        }
        parser.nextTag()
        return server
    }

    private companion object {
        const val MUMBLE_PUBLIC_URL = "https://mumble.info/list2.cgi"
    }
}
