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
package se.lublin.humla.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import se.lublin.humla.protobuf.Mumble

class ServerSettingsTest {
    @Test
    fun recordingIsAllowedUnlessTheServerSaysOtherwise() {
        assertThat(ServerSettings.from(Mumble.ServerConfig.getDefaultInstance()).recordingAllowed).isTrue()
        val allowing = Mumble.ServerConfig.newBuilder().setRecordingAllowed(true).build()
        val refusing = Mumble.ServerConfig.newBuilder().setRecordingAllowed(false).build()
        assertThat(ServerSettings.from(allowing).recordingAllowed).isTrue()
        assertThat(ServerSettings.from(refusing).recordingAllowed).isFalse()
    }
}
