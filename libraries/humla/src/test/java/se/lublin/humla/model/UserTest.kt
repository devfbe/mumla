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
     * `equals` compares the session and `hashCode` returned the user id, which is -1 for every
     * unregistered user and is assigned later than the session for everyone else. Two equal users
     * therefore hashed differently, so a `HashSet` or `HashMap` keyed on users silently held
     * duplicates.
     */
    @Test
    fun usersWithTheSameSessionAreEqualAndHashAlike() {
        val a = User(7, "a").apply { setUserId(1) }
        val b = User(7, "b").apply { setUserId(2) }

        assertThat(a).isEqualTo(b)
        assertThat(a.hashCode()).isEqualTo(b.hashCode())
        assertThat(setOf(a, b)).hasSize(1)
    }

    @Test
    fun movingChannelsUpdatesBothChannelsUserLists() {
        val root = Channel(0, false)
        val sub = Channel(1, false)
        val user = User(1, "u")

        user.setChannel(root)
        user.setChannel(sub)

        assertThat(root.getUsers()).isEmpty()
        assertThat(sub.getUsers()).containsExactly(user)
        assertThat(user.getChannel()).isEqualTo(sub)
    }

    @Test
    fun usersSortCaseInsensitivelyAndTolerateMissingNames() {
        val upper = User(1, "Bob")
        val lower = User(2, "alice")
        val unnamed = User(3, null)

        assertThat(listOf(upper, lower, unnamed).sorted().map { it.getSession() })
            .containsExactly(3, 2, 1).inOrder()
    }
}
