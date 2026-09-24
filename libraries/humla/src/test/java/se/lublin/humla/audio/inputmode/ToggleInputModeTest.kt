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

package se.lublin.humla.audio.inputmode

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.lang.reflect.Modifier

class ToggleInputModeTest {
    @Test
    fun `transmits only while toggled on, ignoring audio and probability`() {
        val mode = ToggleInputMode()
        assertThat(mode.shouldTransmit(ShortArray(480) { 32767 }, 480, 1.0f)).isFalse()
        mode.setTalkingOn(true)
        assertThat(mode.shouldTransmit(ShortArray(480), 480, 0.0f)).isTrue()
        mode.toggleTalkingOn()
        assertThat(mode.isTalkingOn()).isFalse()
        assertThat(mode.shouldTransmit(ShortArray(480) { 32767 }, 480, 1.0f)).isFalse()
    }

    /**
     * The flag is written by the button's thread and read unsynchronised by the capture thread. A
     * missed publication cannot be provoked on demand, so the `@Volatile` declaration is pinned.
     */
    @Test
    fun `the talking flag is published`() {
        val field = ToggleInputMode::class.java.getDeclaredField("inputOn")
        assertThat(Modifier.isVolatile(field.modifiers)).isTrue()
    }

    @Test
    fun `continuous mode always transmits`() {
        assertThat(ContinuousInputMode().shouldTransmit(ShortArray(480), 480, null)).isTrue()
        assertThat(ContinuousInputMode().shouldTransmit(ShortArray(0), 0, 0.0f)).isTrue()
    }
}
