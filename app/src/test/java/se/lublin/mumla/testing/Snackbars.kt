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

package se.lublin.mumla.testing

import android.app.Activity
import android.view.View
import android.widget.TextView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import se.lublin.humla.testutil.idleMainLooper
import se.lublin.mumla.ui.AppMessages
import com.google.android.material.R as MaterialR

/** The text of the snackbar in [root]'s window, or null if none is shown; showing one is posted. */
fun snackbarText(root: View): String? {
    idleMainLooper()
    return root.rootView.findViewById<TextView>(MaterialR.id.snackbar_text)?.text?.toString()
}

fun Activity.snackbarText(): String? = snackbarText(window.decorView)

/** The snackbar's action button in [root]'s window. */
fun snackbarAction(root: View): TextView =
    root.rootView.findViewById(MaterialR.id.snackbar_action)

/**
 * Forgets any snackbar an earlier test left current. `SnackbarManager` is a process-wide singleton,
 * and a snackbar whose activity went away with the test's looper stays current, queueing every
 * later one behind it.
 */
fun resetSnackbars() {
    val manager = Class.forName("com.google.android.material.snackbar.SnackbarManager")
    val instance = manager.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
    for (field in listOf("currentSnackbar", "nextSnackbar")) {
        manager.getDeclaredField(field).apply { isAccessible = true }.set(instance, null)
    }
}

/** The next message posted to [messages], or null if none is waiting. */
fun nextMessage(messages: AppMessages): String? =
    runBlocking { withTimeoutOrNull(NO_MESSAGE_WAIT_MS) { messages.messages.first() } }

private const val NO_MESSAGE_WAIT_MS = 50L
