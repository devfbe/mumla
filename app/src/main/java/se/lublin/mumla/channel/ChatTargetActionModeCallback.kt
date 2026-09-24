/*
 * Copyright (C) 2014 Andrew Comminos
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

import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.view.ActionMode
import se.lublin.mumla.R

/**
 * An action mode that makes [target] the chat target while it is open, and resets the target
 * (to the user's current channel) when it closes; then [onDestroyed] runs.
 */
class ChatTargetActionModeCallback(
    private val targets: ChatTargetViewModel,
    private val target: ChatTarget,
    private val onDestroyed: () -> Unit,
) : ActionMode.Callback {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.title = target.name
        mode.setSubtitle(R.string.current_chat_target)
        targets.select(target)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

    override fun onDestroyActionMode(mode: ActionMode) {
        targets.select(null)
        onDestroyed()
    }
}
