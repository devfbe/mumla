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

import android.os.Handler
import android.os.Looper
import androidx.annotation.StringRes
import se.lublin.mumla.R

/**
 * Speaks changes of our own talk and mute state through [speak]. A state is spoken once it has
 * held for [SETTLE_MS], so a burst of flips says only where it ended, and only if that differs
 * from what was last spoken for the same kind of state. The first state seen of each kind is the
 * baseline and is not spoken.
 */
class SelfStateAnnouncer(private val speak: (Int) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val talk = Topic()
    private val mute = Topic()

    /** Our talk state changed; [announce] is false where the change is not the user's doing. */
    fun onTalking(talking: Boolean, announce: Boolean) =
        talk.update(if (talking) R.string.a11y_transmitting else R.string.a11y_not_transmitting, announce)

    fun onMuteState(muted: Boolean, deafened: Boolean) = mute.update(
        when {
            deafened -> R.string.chat_notify_muted_deafened
            muted -> R.string.chat_notify_muted
            else -> R.string.chat_notify_unmuted
        },
        announce = true,
    )

    /** Drops pending announcements and the baselines: the next state of each kind is a baseline again. */
    fun reset() {
        talk.reset()
        mute.reset()
    }

    private inner class Topic {
        @StringRes private var spoken = 0

        @StringRes private var pending = 0
        private val run = Runnable {
            if (pending != spoken) speak(pending)
            spoken = pending
        }

        fun update(@StringRes text: Int, announce: Boolean) {
            handler.removeCallbacks(run)
            if (spoken == 0 || !announce) {
                spoken = text
                pending = text
                return
            }
            pending = text
            handler.postDelayed(run, SETTLE_MS)
        }

        fun reset() {
            handler.removeCallbacks(run)
            spoken = 0
            pending = 0
        }
    }

    companion object {
        const val SETTLE_MS = 500L
    }
}
