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

package se.lublin.mumla.servers

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The public server list download. Neither the response stream nor the connection was ever
 * released, so every visit to the list left both to the finalizer.
 */
@RunWith(RobolectricTestRunner::class)
class PublicServerFetchTaskTest {
    private class RecordingStream(text: String) : ByteArrayInputStream(text.toByteArray()) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    private class FakeConnection(private val body: InputStream) : HttpURLConnection(URL("https://example.invalid/")) {
        var disconnected = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() {
            disconnected = true
        }
        override fun getInputStream(): InputStream = body
    }

    private fun fetch(connection: HttpURLConnection) =
        object : PublicServerFetchTask(ApplicationProvider.getApplicationContext()) {
            override fun openConnection(): HttpURLConnection = connection
        }.doInBackground()

    @Test
    fun aListThatParsesReleasesItsStreamAndConnection() {
        val body = RecordingStream(
            """<servers><server name="a" ca="0" continent_code="EU" country="Sweden" country_code="SE" """ +
                """ip="a.example" port="64738" region="x" url="https://a.example"/></servers>""",
        )
        val connection = FakeConnection(body)

        val servers = fetch(connection)

        assertThat(servers!!.map { it.name }).containsExactly("a")
        assertThat(body.closed).isTrue()
        assertThat(connection.disconnected).isTrue()
    }

    @Test
    fun aListThatFailsToParseReleasesItsStreamAndConnection() {
        val body = RecordingStream("not xml")
        val connection = FakeConnection(body)

        val servers = fetch(connection)

        assertThat(servers).isNull()
        assertThat(body.closed).isTrue()
        assertThat(connection.disconnected).isTrue()
    }
}
