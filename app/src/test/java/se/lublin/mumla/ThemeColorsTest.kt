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

package se.lublin.mumla

import android.content.Context
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.annotation.AttrRes
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app theme's colour scheme and the colours derived from it, in both modes. */
@RunWith(RobolectricTestRunner::class)
class ThemeColorsTest {
    private val context: Context =
        ContextThemeWrapper(ApplicationProvider.getApplicationContext(), R.style.Theme_Mumla)

    private fun attr(@AttrRes attr: Int): Int {
        val value = TypedValue()
        check(context.theme.resolveAttribute(attr, value, true))
        return value.data
    }

    private fun ptt(vararg state: Int): Int =
        context.getColorStateList(R.color.ptt_background).getColorForState(state, 0)

    @Test
    fun theBrandColourIsThePrimaryContainerInTheLightScheme() {
        assertThat(attr(com.google.android.material.R.attr.colorPrimaryContainer)).isEqualTo(BRAND)
        assertThat(context.getColor(R.color.app_bar_background))
            .isEqualTo(attr(androidx.appcompat.R.attr.colorPrimary))
        assertThat(ptt()).isEqualTo(attr(androidx.appcompat.R.attr.colorPrimary))
        assertThat(ptt(android.R.attr.state_pressed)).isEqualTo(BRAND)
    }

    @Test
    @Config(qualifiers = "night")
    fun theDarkSchemeKeepsTheBrandAndPutsTheAppBarOnASurface() {
        assertThat(attr(com.google.android.material.R.attr.colorPrimaryContainer)).isEqualTo(BRAND)
        assertThat(attr(androidx.appcompat.R.attr.colorPrimary)).isEqualTo(0xFFBBC3FF.toInt())
        assertThat(context.getColor(R.color.app_bar_background))
            .isEqualTo(attr(com.google.android.material.R.attr.colorSurfaceContainer))
        assertThat(ptt()).isEqualTo(BRAND)
        assertThat(ptt(android.R.attr.state_activated)).isEqualTo(attr(androidx.appcompat.R.attr.colorPrimary))
    }

    private companion object {
        const val BRAND = 0xFF3949AB.toInt()
    }
}
