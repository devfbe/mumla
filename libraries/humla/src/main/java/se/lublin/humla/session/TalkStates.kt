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
package se.lublin.humla.session

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import se.lublin.humla.model.TalkState

/**
 * The talk state of every user who is not silent, by session. The audio threads [report] changes
 * with a pooled [android.os.Message], so reporting allocates nothing on them; [looper]'s thread
 * applies them and publishes [states].
 */
internal class TalkStates(looper: Looper, private val onChanged: (Int, TalkState) -> Unit = { _, _ -> }) {
    private val mutableStates = MutableStateFlow<PersistentMap<Int, TalkState>>(persistentMapOf())

    val states: StateFlow<Map<Int, TalkState>> = mutableStates

    private val handler = Handler(looper) { message ->
        apply(message.arg1, TalkState.entries[message.arg2])
        true
    }

    /** Any thread. */
    fun report(session: Int, state: TalkState) {
        handler.obtainMessage(REPORT, session, state.ordinal).sendToTarget()
    }

    /** Everybody is silent, including reports still on their way. [looper]'s thread. */
    fun clear() {
        handler.removeMessages(REPORT)
        mutableStates.value = persistentMapOf()
    }

    private fun apply(session: Int, state: TalkState) {
        val current = mutableStates.value
        val next = if (state == TalkState.PASSIVE) current.remove(session) else current.put(session, state)
        if (next === current) return
        mutableStates.value = next
        onChanged(session, state)
    }

    private companion object {
        const val REPORT = 1
    }
}
