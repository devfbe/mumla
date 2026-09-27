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

package se.lublin.mumla.smoke

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.test.rule.GrantPermissionRule

private const val POLL_MILLIS = 50L

/** Polls [condition] until it holds, failing after [timeoutMillis]. */
fun waitFor(description: String, timeoutMillis: Long = 20_000L, condition: () -> Boolean) {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (!condition()) {
        if (SystemClock.uptimeMillis() > deadline) throw AssertionError("timed out waiting for $description")
        SystemClock.sleep(POLL_MILLIS)
    }
}

/**
 * Grants [permissions], plus the notification permission where there is one, so no system dialog
 * covers the app.
 */
fun grant(vararg permissions: String): GrantPermissionRule {
    val notifications = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    return GrantPermissionRule.grant(
        *permissions,
        *if (notifications) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray(),
    )
}
