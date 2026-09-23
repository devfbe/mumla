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

package se.lublin.mumla.app

import android.os.StrictMode
import se.lublin.mumla.BuildConfig

/**
 * StrictMode for debug builds: a leaked Closeable -- stream, socket, cursor -- is logged with the
 * stack trace of where it was opened. Without it the finalizer only says "A resource failed to
 * call close", which names no culprit. Release builds are left alone.
 */
object DebugStrictMode {
    @JvmStatic
    @JvmOverloads
    fun install(debug: Boolean = BuildConfig.DEBUG) {
        if (!debug) return
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder(StrictMode.getVmPolicy())
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
    }
}
