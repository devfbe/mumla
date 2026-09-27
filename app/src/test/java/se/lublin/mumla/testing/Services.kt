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

import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import com.google.common.truth.Truth.assertThat
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import se.lublin.mumla.service.MumlaService

/**
 * A created [MumlaService]. [beforeCreate] runs on the bare instance first, where the seams that
 * `onCreate` reads (connection factory, reconnect policy, communication devices) must be set.
 */
fun createMumlaService(beforeCreate: MumlaService.() -> Unit = {}): ServiceController<MumlaService> =
    Robolectric.buildService(MumlaService::class.java).also { it.get().beforeCreate() }.create()

/** From here on the platform refuses every foreground start, as with the screen off. */
fun Service.refuseForegroundStarts() {
    shadowOf(this).setThrowInStartForeground(
        ForegroundServiceStartNotAllowedException("startForeground() not allowed from the background"),
    )
}

/** [intent] is a broadcast only this app ([packageName]) receives, and nobody can alter it. */
fun assertOwnImmutableBroadcast(intent: PendingIntent, packageName: String) {
    val pending = shadowOf(intent)
    assertThat(pending.isBroadcast).isTrue()
    assertThat(pending.isImmutable).isTrue()
    assertThat(pending.savedIntent.`package`).isEqualTo(packageName)
}
