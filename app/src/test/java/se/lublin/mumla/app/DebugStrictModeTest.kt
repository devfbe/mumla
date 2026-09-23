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
 * A debug build reports a leaked Closeable with the stack trace of where it was opened. Without
 * this the finalizer only logs "A resource failed to call close" -- no trace, so no way to tell
 * which of the app's streams, cursors or sockets it was.
 */
@RunWith(RobolectricTestRunner::class)
class DebugStrictModeTest {
    private fun flag(name: String): Int =
        StrictMode::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(null)

    private fun mask(policy: StrictMode.VmPolicy): Int =
        StrictMode.VmPolicy::class.java.getDeclaredField("mask").apply { isAccessible = true }.getInt(policy)

    private val leakDetection get() = flag("DETECT_VM_CLOSABLE_LEAKS")
    private val penaltyLog get() = flag("PENALTY_LOG")

    @After
    fun tearDown() {
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
    }

    @Test
    fun theDebugApplicationLogsLeakedClosables() {
        // Robolectric has created MumlaApplication for this test, and unit tests run the debug
        // variant -- so this is the wiring, not only the helper.
        assertThat(BuildConfig.DEBUG).isTrue()

        val mask = mask(StrictMode.getVmPolicy())
        assertThat(mask and leakDetection).isEqualTo(leakDetection)
        assertThat(mask and penaltyLog).isEqualTo(penaltyLog)
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
        assertThat(mask and leakDetection).isEqualTo(leakDetection)
    }
}
