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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.lublin.humla.IHumlaSession
import se.lublin.mumla.session.isConnected

/**
 * A popup menu whose items depend on the permissions in [channel]; it asks for them when they are
 * not known yet and prepares the menu again whenever they change.
 */
class PermissionsPopupMenu(
    context: Context,
    anchor: View,
    menuRes: Int,
    private val prepareListener: IOnMenuPrepareListener,
    private val channel: Int,
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
        get() = if (session.isConnected) session.model.value?.permissionsIn(channel) ?: 0 else 0

    fun show() {
        permissionUpdates?.cancel()
        permissionUpdates = MainScope().launch(Dispatchers.Main.immediate, CoroutineStart.UNDISPATCHED) {
            session.model.map { it?.permissionsIn(channel) ?: 0 }.distinctUntilChanged().drop(1)
                .collect { prepareListener.onMenuPrepare(menu.menu, it) }
        }
        if (permissions == 0) {
            // onMenuPrepare will be called once more once permissions have loaded.
            session.actions.requestPermissions(channel)
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
