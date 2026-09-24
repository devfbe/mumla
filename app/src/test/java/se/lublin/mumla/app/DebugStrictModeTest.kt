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
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.BuildConfig

/**
 * A debug build reports a leaked Closeable with the stack trace of where it was opened, instead
 * of the finalizer's bare "A resource failed to call close".
 */
@RunWith(RobolectricTestRunner::class)
class DebugStrictModeTest {
    /** The policy's flag set, as its `toString` spells it: `[StrictMode.VmPolicy; mask=N]`. */
    private fun mask(policy: StrictMode.VmPolicy): Int =
        Regex("mask=(-?\\d+)").find(policy.toString())!!.groupValues[1].toInt()

    private val closableLeaksLogged: Int
        get() = mask(StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build())

    @After
    fun tearDown() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
    }

    @Test
    fun theDebugApplicationLogsLeakedClosables() {
        // Unit tests run the debug variant, so this checks the MumlaApplication wiring too.
        assertThat(BuildConfig.DEBUG).isTrue()
        assertThat(closableLeaksLogged).isNotEqualTo(0)

        assertThat(mask(StrictMode.getVmPolicy()) and closableLeaksLogged).isEqualTo(closableLeaksLogged)
    }

    @Test
    fun aReleaseBuildLeavesThePolicyAlone() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)

        DebugStrictMode.install(debug = false)

        assertThat(mask(StrictMode.getVmPolicy())).isEqualTo(mask(StrictMode.VmPolicy.LAX))
    }

    @Test
    fun installingKeepsWhatThePolicyAlreadyDetected() {
        val existing = StrictMode.VmPolicy.Builder().detectLeakedSqlLiteObjects().build()
        StrictMode.setVmPolicy(existing)

        DebugStrictMode.install(debug = true)

        val mask = mask(StrictMode.getVmPolicy())
        assertThat(mask and mask(existing)).isEqualTo(mask(existing))
        assertThat(mask and closableLeaksLogged).isEqualTo(closableLeaksLogged)
    }
}
