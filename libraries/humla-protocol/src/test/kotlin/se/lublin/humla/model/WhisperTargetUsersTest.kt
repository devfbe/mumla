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

class WhisperTargetUsersTest {
    @Test
    fun `builds a target carrying the session ids and no channel`() {
        val target = WhisperTargetUsers(listOf(3, 7), "Alice, Bob")

        assertThat(target.name).isEqualTo("Alice, Bob")
        val built = target.createTarget()
        assertThat(built.sessionList).containsExactly(3, 7).inOrder()
        assertThat(built.hasChannelId()).isFalse()
        assertThat(built.hasGroup()).isFalse()
    }

    @Test
    fun `an empty session list builds a target with no sessions`() {
        assertThat(WhisperTargetUsers(emptyList(), null).createTarget().sessionList).isEmpty()
    }
}
