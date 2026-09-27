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
package se.lublin.mumla.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.mumla.testing.idleMainLooper

@RunWith(RobolectricTestRunner::class)
class PreferenceChangesTest {
    private val prefs: SharedPreferences =
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("changes", Context.MODE_PRIVATE)
    private val scope = MainScope()
    private val received = mutableListOf<String>()

    private fun collect(vararg keys: String) = scope.launch(Dispatchers.Main.immediate, CoroutineStart.UNDISPATCHED) {
        prefs.changes(*keys).collect(received::add)
    }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun onlyTheWatchedKeysAreEmitted() {
        collect("a", "b")

        prefs.edit { putInt("a", 1) }
        prefs.edit { putInt("other", 1) }
        prefs.edit { putInt("b", 1) }
        idleMainLooper()

        assertThat(received).containsExactly("a", "b").inOrder()
    }

    @Test
    fun clearingEmitsEveryWatchedKey() {
        collect("a", "b")

        prefs.edit { clear() }
        idleMainLooper()

        assertThat(received).containsExactly("a", "b")
    }

    @Test
    fun theListenerIsUnregisteredWhenTheCollectorStops() {
        val job = collect("a")
        job.cancel()
        idleMainLooper()

        prefs.edit { putInt("a", 2) }
        idleMainLooper()

        assertThat(received).isEmpty()
    }
}
