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

package se.lublin.humla.audio

import kotlin.math.pow
import kotlin.math.roundToInt

/** A frame of [size] samples all equal to [value]. */
internal fun constant(value: Int, size: Int = 480) = ShortArray(size) { value.toShort() }

/** A constant frame whose RMS is exactly [dbfs] relative to full scale. */
internal fun frameAt(dbfs: Float, length: Int = 480): ShortArray {
    val amplitude = (32768.0 * 10.0.pow(dbfs / 20.0)).roundToInt().coerceIn(0, 32767)
    return ShortArray(length) { amplitude.toShort() }
}
