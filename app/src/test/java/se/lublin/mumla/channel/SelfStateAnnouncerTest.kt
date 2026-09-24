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

package se.lublin.mumla.channel

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.lublin.mumla.R
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class SelfStateAnnouncerTest {
    private val spoken = mutableListOf<Int>()
    private val announcer = SelfStateAnnouncer { spoken += it }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(SelfStateAnnouncer.SETTLE_MS))

    @Test
    fun theFirstStateIsABaselineAndAChangeIsSpokenOnceSettled() {
        announcer.onMuteState(muted = false, deafened = false)
        settle()
        assertThat(spoken).isEmpty()

        announcer.onMuteState(muted = true, deafened = false)
        assertThat(spoken).isEmpty()
        settle()

        assertThat(spoken).containsExactly(R.string.chat_notify_muted)
    }

    @Test
    fun aBurstOfFlipsSaysOnlyWhereItEnded() {
        announcer.onTalking(false, announce = true)
        repeat(5) {
            announcer.onTalking(true, announce = true)
            announcer.onTalking(false, announce = true)
        }
        announcer.onTalking(true, announce = true)
        settle()

        assertThat(spoken).containsExactly(R.string.a11y_transmitting)
    }

    @Test
    fun aFlipBackBeforeSettlingSaysNothing() {
        announcer.onTalking(false, announce = true)
        announcer.onTalking(true, announce = true)
        announcer.onTalking(false, announce = true)
        settle()

        assertThat(spoken).isEmpty()
    }

    @Test
    fun unannouncedChangesMoveTheBaselineSilently() {
        announcer.onTalking(false, announce = false)
        announcer.onTalking(true, announce = false)
        settle()
        announcer.onTalking(true, announce = true)
        settle()

        assertThat(spoken).isEmpty()
    }

    @Test
    fun talkAndMuteAreSpokenIndependently() {
        announcer.onTalking(false, announce = true)
        announcer.onMuteState(muted = false, deafened = false)
        announcer.onTalking(true, announce = true)
        announcer.onMuteState(muted = true, deafened = true)
        settle()

        assertThat(spoken).containsExactly(R.string.a11y_transmitting, R.string.chat_notify_muted_deafened)
    }

    @Test
    fun resetDropsWhatIsPending() {
        announcer.onMuteState(muted = false, deafened = false)
        announcer.onMuteState(muted = true, deafened = false)
        announcer.reset()
        settle()

        assertThat(spoken).isEmpty()
    }
}
