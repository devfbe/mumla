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

package se.lublin.mumla.testing

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import se.lublin.humla.testutil.idleMainLooper

/**
 * A tap on [target]'s centre that enters at [root] and is hit-tested down, so disabled, `GONE` and
 * unlaid-out (0x0) targets are missed as they would be by a finger; [View.performClick] and a touch
 * sent straight to the target would see none of these.
 */
fun tapThrough(root: View, target: View) {
    var x = target.width / 2f
    var y = target.height / 2f
    var view = target
    while (view !== root) {
        x += view.left
        y += view.top
        view = view.parent as View
    }
    val now = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
    val up = MotionEvent.obtain(now, now + 10, MotionEvent.ACTION_UP, x, y, 0)
    root.dispatchTouchEvent(down)
    root.dispatchTouchEvent(up)
    down.recycle()
    up.recycle()
    idleMainLooper()
}
