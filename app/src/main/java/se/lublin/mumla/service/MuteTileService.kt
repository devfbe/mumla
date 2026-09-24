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

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.humla.session.HumlaEvent
import se.lublin.mumla.R
import se.lublin.mumla.util.collectEvents

/**
 * A Quick Settings tile that mutes and unmutes us. It is unavailable while not connected, and
 * follows [MumlaService] only while the panel shows it, bound without creating the service.
 */
class MuteTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var service: IMumlaService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? MumlaService.MumlaBinder)?.getService() ?: return
            this@MuteTileService.service = service
            val scope = scope ?: return
            scope.launch { service.sessionState.collect { render() } }
            collectEvents(scope, service) { event -> if (event is HumlaEvent.UserStateUpdated) render() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            render()
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        render()
        bound = bindService(Intent(this, MumlaService::class.java), connection, 0)
    }

    override fun onStopListening() {
        if (bound) unbindService(connection)
        bound = false
        scope?.cancel()
        scope = null
        service = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        connectedSession()?.let(::toggleSelfMute)
        render()
    }

    private fun connectedSession(): IHumlaSession? = service?.takeIf { it.isConnected }?.session

    private fun render() {
        val tile = qsTile ?: return
        val model = MuteTileModel.of(connectedSession()?.sessionUser?.isSelfMuted)
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
    val self = session.sessionUser ?: return
    val muted = !self.isSelfMuted
    session.setSelfMuteDeafState(muted, self.isSelfDeafened && muted)
}
