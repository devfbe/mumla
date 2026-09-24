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

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** A side of a view, relative to its layout direction. */
enum class Edge { START, TOP, END, BOTTOM }

/**
 * Keeps this view's content clear of the system bars and display cutout on [edges], and of the
 * keyboard at the bottom if [ime], by adding their insets to the padding it has now.
 */
fun View.padForSystemBars(vararg edges: Edge, ime: Boolean = false) {
    val start = paddingStart
    val top = paddingTop
    val end = paddingEnd
    val bottom = paddingBottom
    val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
        if (ime) WindowInsetsCompat.Type.ime() else 0
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(types)
        val rtl = view.layoutDirection == View.LAYOUT_DIRECTION_RTL
        fun inset(edge: Edge, value: Int) = if (edge in edges) value else 0
        view.setPaddingRelative(
            start + inset(Edge.START, if (rtl) bars.right else bars.left),
            top + inset(Edge.TOP, bars.top),
            end + inset(Edge.END, if (rtl) bars.left else bars.right),
            bottom + inset(Edge.BOTTOM, bars.bottom),
        )
        insets
    }
    if (isAttachedToWindow) ViewCompat.requestApplyInsets(this)
}
