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

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.lublin.mumla.R
import se.lublin.mumla.app.MumlaActivity
import se.lublin.mumla.servers.FavouriteServerListFragment

@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule
    val permissions = grant()

    @Test
    fun theAppOpensOnTheServerList() {
        ActivityScenario.launch(MumlaActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.supportFragmentManager.executePendingTransactions()
                val screen = activity.supportFragmentManager.findFragmentById(R.id.content_frame)
                assertThat(screen).isInstanceOf(FavouriteServerListFragment::class.java)
                assertThat(screen!!.requireView().isShown).isTrue()
            }
        }
    }
}
