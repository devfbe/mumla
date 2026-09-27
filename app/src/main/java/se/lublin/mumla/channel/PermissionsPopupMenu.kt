/*
 * Copyright (C) 2015 Andrew Comminos <andrew@comminos.com>
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

import android.content.Context
import android.view.Menu
import android.view.View
import androidx.appcompat.widget.PopupMenu
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import se.lublin.humla.IHumlaSession
import se.lublin.humla.model.IChannel
import se.lublin.humla.session.HumlaEvent
import se.lublin.mumla.session.isConnected
import se.lublin.mumla.util.collectEvents

/**
 * A popup menu whose items depend on the permissions in [channel]; it asks for them when they are
 * not known yet and prepares the menu again once they arrive.
 */
class PermissionsPopupMenu(
    context: Context,
    anchor: View,
    menuRes: Int,
    private val prepareListener: IOnMenuPrepareListener,
    private val channel: IChannel,
    private val session: IHumlaSession,
) : PopupMenu.OnDismissListener {

    private val menu = PopupMenu(context, anchor).apply {
        inflate(menuRes)
        setOnDismissListener(this@PermissionsPopupMenu)
        setOnMenuItemClickListener(prepareListener)
    }

    /** Listens for the permissions while the menu is shown. */
    private var permissionUpdates: Job? = null

    private val permissions: Int
        get() = when {
            !session.isConnected -> 0
            channel.id == 0 -> session.permissions
            else -> channel.permissions
        }

    fun show() {
        permissionUpdates?.cancel()
        permissionUpdates = collectEvents(MainScope(), session) { event ->
            if (event is HumlaEvent.ChannelPermissionsUpdated && event.channel == channel) {
                prepareListener.onMenuPrepare(menu.menu, permissions)
            }
        }
        if (permissions == 0) {
            // onMenuPrepare will be called once more once permissions have loaded.
            if (session.isConnected) session.requestPermissions(channel.id)
        } else {
            prepareListener.onMenuPrepare(menu.menu, permissions)
        }
        menu.show()
    }

    fun dismiss() {
        menu.dismiss()
    }

    override fun onDismiss(popupMenu: PopupMenu) {
        permissionUpdates?.cancel()
        permissionUpdates = null
    }

    /** Handles the menu's items and enforces the channel permissions, disabling items as appropriate. */
    interface IOnMenuPrepareListener : PopupMenu.OnMenuItemClickListener {
        fun onMenuPrepare(menu: Menu, permissions: Int)
    }
}
