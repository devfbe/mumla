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

package se.lublin.mumla.service

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.SessionState
import se.lublin.mumla.R
import se.lublin.mumla.session.SessionManager

/**
 * A Quick Settings tile that mutes and unmutes us. It is unavailable while not connected, and
 * follows the current session only while the panel shows it.
 */
class MuteTileService : TileService() {
    private var scope: CoroutineScope? = null
    private val sessions get() = SessionManager.get(this)

    override fun onStartListening() {
        super.onStartListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        render(null)
        scope.launch {
            sessions.session.collectLatest { session ->
                if (session == null) return@collectLatest
                combine(session.state, session.model) { state, model ->
                    model?.self?.isSelfMuted?.takeIf { state == SessionState.Connected }
                }.distinctUntilChanged().collect(::render)
            }
        }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        sessions.connected?.let(::toggleSelfMute)
    }

    private fun render(selfMuted: Boolean?) {
        val tile = qsTile ?: return
        val model = MuteTileModel.of(selfMuted)
        tile.state = model.state
        tile.subtitle = model.subtitle?.let(::getString)
        tile.updateTile()
    }
}

/** What the mute tile shows for our mute state, or for no session with `selfMuted == null`. */
data class MuteTileModel(val state: Int, @param:StringRes val subtitle: Int?) {
    companion object {
        fun of(selfMuted: Boolean?): MuteTileModel = when (selfMuted) {
            null -> MuteTileModel(Tile.STATE_UNAVAILABLE, R.string.drawer_not_connected)
            true -> MuteTileModel(Tile.STATE_ACTIVE, R.string.a11y_state_muted)
            false -> MuteTileModel(Tile.STATE_INACTIVE, null)
        }
    }
}

/** Flips our mute as the mute button does: unmuting undeafens too. */
fun toggleSelfMute(session: IHumlaSession) {
    val self = session.model.value?.self ?: return
    val muted = !self.isSelfMuted
    session.actions.setSelfMuteDeafState(muted, self.isSelfDeafened && muted)
}

/** Flips our deafness as the deafen button does: deafening mutes too, undeafening unmutes. */
fun toggleSelfDeafen(session: IHumlaSession) {
    val self = session.model.value?.self ?: return
    val deafened = !self.isSelfDeafened
    session.actions.setSelfMuteDeafState(deafened, deafened)
}
