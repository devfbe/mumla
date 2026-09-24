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

import android.content.Context
import android.view.View
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemBarInsetsTest {
    private val view = View(ApplicationProvider.getApplicationContext<Context>()).apply { setPadding(1, 2, 3, 4) }

    private fun dispatch(bars: Insets, ime: Insets = Insets.NONE) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), bars)
            .setInsets(WindowInsetsCompat.Type.ime(), ime)
            .build()
        ViewCompat.dispatchApplyWindowInsets(view, insets)
    }

    private fun padding() = listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)

    @Test
    fun onlyTheRequestedEdgesArePaddedOnTopOfTheViewsOwnPadding() {
        view.padForSystemBars(Edge.TOP, Edge.BOTTOM)

        dispatch(Insets.of(10, 20, 30, 40))

        assertThat(padding()).containsExactly(1, 22, 3, 44).inOrder()
    }

    @Test
    fun aSecondDispatchReplacesTheInsetsInsteadOfAddingUp() {
        view.padForSystemBars(Edge.BOTTOM)

        dispatch(Insets.of(0, 0, 0, 40))
        dispatch(Insets.of(0, 0, 0, 10))

        assertThat(view.paddingBottom).isEqualTo(14)
    }

    @Test
    fun startAndEndFollowTheLayoutDirection() {
        view.layoutDirection = View.LAYOUT_DIRECTION_RTL
        view.padForSystemBars(Edge.START)

        dispatch(Insets.of(10, 0, 30, 0))

        assertThat(view.paddingRight).isEqualTo(3 + 30)
        assertThat(view.paddingLeft).isEqualTo(1)
    }

    @Test
    fun theKeyboardCountsOnlyWhenAsked() {
        val withIme = View(view.context)
        withIme.padForSystemBars(Edge.BOTTOM, ime = true)
        view.padForSystemBars(Edge.BOTTOM)
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 0, 0, 40))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 300))
            .build()

        ViewCompat.dispatchApplyWindowInsets(view, insets)
        ViewCompat.dispatchApplyWindowInsets(withIme, insets)

        assertThat(view.paddingBottom).isEqualTo(4 + 40)
        assertThat(withIme.paddingBottom).isEqualTo(300)
    }
}
