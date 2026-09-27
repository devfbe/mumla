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
package se.lublin.mumla.app

import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.Settings
import se.lublin.mumla.databinding.ConnectionBannerBinding
import se.lublin.mumla.session.SessionManager
import se.lublin.mumla.util.Edge
import se.lublin.mumla.util.padForSystemBars

private const val SECOND_MS = 1000L

/**
 * A strip above the content of [activity] while a connection is set up or re-established, with a
 * Cancel that stops it. It blocks nothing, and only its Cancel stops the connection. Follows the
 * session's state while [activity] is started. Create it in `onCreate`; main thread only.
 */
class ConnectionBanner(
    private val activity: AppCompatActivity,
    private val binding: ConnectionBannerBinding,
    private val settings: Settings,
    private val sessions: SessionManager,
) {
    private val countdown = ViewModelProvider(activity)[ReconnectCountdown::class.java]

    init {
        binding.root.padForSystemBars(Edge.START, Edge.END)
        binding.connectionBannerCancel.setOnClickListener { cancel() }
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.STARTED) { sessions.state.collectLatest(::show) }
        }
    }

    private suspend fun show(state: SessionState) {
        when (state) {
            SessionState.Connecting -> showProgress(R.string.connecting_to_server)
            is SessionState.Reconnecting -> showProgress(R.string.connection_reconnecting_to)
            is SessionState.ConnectionLost -> countDown(state)
            SessionState.Connected, is SessionState.Disconnected -> binding.root.isVisible = false
        }
    }

    /** Shows [text] with the server's host; not its port, which the SRV lookup that comes later may change. */
    private fun showProgress(@StringRes text: Int) {
        val host = sessions.session.value?.targetServer?.host
        showText(activity.getString(text, host) + if (settings.isTorEnabled) " (Tor)" else "")
    }

    /** Counts the seconds down to the reconnect; returns when it is due. */
    private suspend fun countDown(state: SessionState.ConnectionLost) {
        val deadline = countdown.deadline(state, SystemClock.elapsedRealtime())
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            val seconds = (left + SECOND_MS - 1) / SECOND_MS
            showText(activity.getString(R.string.connection_lost_retrying_in, seconds))
            delay(left - (seconds - 1) * SECOND_MS)
        }
        // Due now, or later once the session sees the network again.
        showText(activity.getString(R.string.connection_lost_reconnecting))
    }

    private fun showText(text: String) {
        if (binding.connectionBannerText.text.toString() != text) binding.connectionBannerText.text = text
        binding.root.isVisible = true
    }

    private fun cancel() {
        when (sessions.currentState) {
            SessionState.Connecting -> sessions.disconnect()
            is SessionState.ConnectionLost, is SessionState.Reconnecting -> sessions.cancelReconnect()
            SessionState.Connected, is SessionState.Disconnected -> Unit
        }
    }
}

/** When the reconnect after each lost connection is due, kept across configuration changes. */
class ReconnectCountdown : ViewModel() {
    private var lost: SessionState.ConnectionLost? = null
    private var deadline = 0L

    /** The [SystemClock.elapsedRealtime] at which [state]'s reconnect is due, counted from [now] if [state] is new. */
    fun deadline(state: SessionState.ConnectionLost, now: Long): Long {
        if (state != lost) {
            lost = state
            deadline = now + state.reconnectInMillis
        }
        return deadline
    }
}
