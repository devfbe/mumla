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
import androidx.core.view.MenuCompat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * A popup menu whose items depend on our [permissions] in a channel: asked for with
 * [requestPermissions] when they are not known yet, and the menu prepared again whenever they
 * change while it is shown.
 */
class PermissionsPopupMenu(
    context: Context,
    anchor: View,
    menuRes: Int,
    private val prepareListener: IOnMenuPrepareListener,
    private val permissions: Flow<Int>,
    private val requestPermissions: () -> Unit,
) : PopupMenu.OnDismissListener {

    private val menu = PopupMenu(context, anchor).apply {
        inflate(menuRes)
        MenuCompat.setGroupDividerEnabled(menu, true)
        setOnDismissListener(this@PermissionsPopupMenu)
        setOnMenuItemClickListener(prepareListener)
    }

    /** Follows the permissions while the menu is shown. */
    private var permissionUpdates: Job? = null

    fun show() {
        permissionUpdates?.cancel()
        permissionUpdates = MainScope().launch(Dispatchers.Main.immediate, CoroutineStart.UNDISPATCHED) {
            var known = false
            permissions.distinctUntilChanged().collect { permissions ->
                // Unknown at first: prepared once they arrive.
                if (!known && permissions == 0) {
                    requestPermissions()
                } else {
                    prepareListener.onMenuPrepare(menu.menu, permissions)
                }
                known = true
            }
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
