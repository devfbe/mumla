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

class UserTest {

    /**
     * `equals` compares the session, so `hashCode` must too: the user id is -1 for every
     * unregistered user and assigned later, so hash-based collections would hold duplicates.
     */
    @Test
    fun usersWithTheSameSessionAreEqualAndHashAlike() {
        val a = User(7, "a").apply { userId = 1 }
        val b = User(7, "b").apply { userId = 2 }

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(setOf(a, b)).hasSize(1)
    }

    @Test
    fun movingChannelsUpdatesBothChannelsUserLists() {
        val root = Channel(0, false)
        val sub = Channel(1, false)
        val user = User(1, "u")

        user.channel = root
        user.channel = sub

        assertThat(root.users).isEmpty()
        assertThat(sub.users).containsExactly(user)
        assertThat(user.channel).isEqualTo(sub)
    }

    @Test
    fun usersSortCaseInsensitivelyAndTolerateMissingNames() {
        val upper = User(1, "Bob")
        val lower = User(2, "alice")
        val unnamed = User(3, null)

        assertThat(listOf(upper, lower, unnamed).sorted().map { it.session })
            .containsExactly(3, 2, 1).inOrder()
    }
}
