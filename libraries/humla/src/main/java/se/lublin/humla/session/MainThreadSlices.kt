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
package se.lublin.humla.session

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.yield

/** Items one main-looper task may deliver before [inMainThreadSlices] hands the looper back. */
const val MAX_EVENTS_PER_SLICE = 64

/**
 * Hands the main looper back after [maxPerSlice] items delivered within one looper task, so a
 * burst (a large server's channel tree, say) cannot stall the UI. Collect on the main thread.
 * Items emitted one at a time from the main thread stay inline.
 */
fun <T> Flow<T>.inMainThreadSlices(maxPerSlice: Int = MAX_EVENTS_PER_SLICE): Flow<T> = flow {
    require(maxPerSlice > 0) { "maxPerSlice must be positive, was $maxPerSlice" }
    val handler = Handler(Looper.getMainLooper())
    var delivered = 0
    // Runs once the looper gets control back, which ends the slice.
    val sliceEnd = Runnable { delivered = 0 }
    collect { value ->
        if (delivered == 0) handler.post(sliceEnd)
        emit(value)
        if (++delivered >= maxPerSlice) {
            handler.removeCallbacks(sliceEnd)
            delivered = 0
            yield()
        }
    }
}
