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
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.Server

@RunWith(RobolectricTestRunner::class)
class ServerBundlesTest {
    @Test
    fun aServerSurvivesTheBundleWithEveryField() {
        val server = Server(7L, "Home", "mumble.example.org", 64738, "alice", "s3cret")
        val bundle = Bundle().apply { putServer("server", server) }

        assertThat(bundle.getServer("server")).isEqualTo(server)
    }

    @Test
    fun nullFieldsStayNull() {
        val server = Server(Server.NOT_SAVED, null, "mumble.example.org", 0, null, null)
        val bundle = Bundle().apply { putServer("server", server) }

        assertThat(bundle.getServer("server")).isEqualTo(server)
        assertThat(bundle.getServer("other")).isNull()
    }
}
