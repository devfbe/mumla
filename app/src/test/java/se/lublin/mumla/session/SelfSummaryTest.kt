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
package se.lublin.mumla.session

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.lublin.humla.model.UserState
import se.lublin.mumla.R
import se.lublin.mumla.testing.serverState

@RunWith(RobolectricTestRunner::class)
class SelfSummaryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun text(channel: String?, muted: Boolean, deafened: Boolean) =
        SelfSummary(channel, muted, deafened).text(context)

    @Test
    fun theChannelAloneWhileNeitherMutedNorDeafened() {
        assertThat(text("Lobby", muted = false, deafened = false)).isEqualTo("Lobby")
    }

    @Test
    fun theStrongestOwnStateFollowsTheChannel() {
        assertThat(text("Lobby", muted = true, deafened = false))
            .isEqualTo("Lobby · " + context.getString(R.string.self_status_muted))
        assertThat(text("Lobby", muted = false, deafened = true))
            .isEqualTo("Lobby · " + context.getString(R.string.self_status_deafened))
        assertThat(text("Lobby", muted = true, deafened = true))
            .isEqualTo("Lobby · " + context.getString(R.string.self_status_muted_deafened))
    }

    @Test
    fun withoutAChannelNameOnlyTheStateOrNothing() {
        assertThat(text("", muted = true, deafened = false)).isEqualTo(context.getString(R.string.self_status_muted))
        assertThat(text(null, muted = false, deafened = false)).isNull()
    }

    @Test
    fun ourSummaryIsReadFromTheModelOnceWeAreInIt() {
        assertThat(SelfSummary.of(null)).isNull()
        assertThat(SelfSummary.of(serverState(self = null) { channel(0, "Root") })).isNull()

        val model = serverState(self = 3) {
            channel(0, "Root")
            user(UserState(3, "me", 0, isSelfMuted = true))
        }

        assertThat(SelfSummary.of(model)).isEqualTo(SelfSummary("Root", isSelfMuted = true, isSelfDeafened = false))
    }
}
