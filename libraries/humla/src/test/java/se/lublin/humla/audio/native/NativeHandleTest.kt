/*
 * Copyright (C) 2026 The Mumla Authors
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
package se.lublin.humla.audio.native

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NativeHandleTest {
    private val destroyed = mutableListOf<Long>()

    @Test
    fun closeDestroysTheHandleOnceAndZeroesIt() {
        val handle = NativeHandle({ 42L }, destroyed::add)
        assertThat(handle.value).isEqualTo(42L)

        handle.close()
        handle.close()

        assertThat(destroyed).containsExactly(42L)
        assertThat(handle.value).isEqualTo(0L)
    }

    @Test
    fun aHandleThatWasNeverCreatedIsNotDestroyed() {
        NativeHandle({ 0L }, destroyed::add).close()

        assertThat(destroyed).isEmpty()
    }
}
