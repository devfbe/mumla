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

package se.lublin.mumla.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import se.lublin.mumla.R

/**
 * Shows [text] at the bottom of [view]'s window; [action] labels a button that runs [onAction].
 * With an action, accessibility services get the time they need to reach it.
 */
fun showSnackbar(view: View, text: CharSequence, @StringRes action: Int = 0, onAction: () -> Unit = {}): Snackbar =
    Snackbar.make(view, text, Snackbar.LENGTH_LONG).apply {
        if (action != 0) setAction(action) { onAction() }
        show()
    }

fun Activity.showSnackbar(text: CharSequence, @StringRes action: Int = 0, onAction: () -> Unit = {}): Snackbar =
    showSnackbar(findViewById(android.R.id.content), text, action, onAction)

fun Activity.showSnackbar(@StringRes text: Int, @StringRes action: Int = 0, onAction: () -> Unit = {}): Snackbar =
    showSnackbar(getString(text), action, onAction)

fun Fragment.showSnackbar(text: CharSequence, @StringRes action: Int = 0, onAction: () -> Unit = {}): Snackbar =
    requireActivity().showSnackbar(text, action, onAction)

fun Fragment.showSnackbar(@StringRes text: Int, @StringRes action: Int = 0, onAction: () -> Unit = {}): Snackbar =
    requireActivity().showSnackbar(getString(text), action, onAction)

/** For a permission the user denied: a snackbar whose action opens the app's system settings. */
fun Activity.showPermissionDeniedSnackbar(@StringRes text: Int): Snackbar =
    showSnackbar(text, R.string.open_settings) {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    }

/**
 * Messages from where no screen is at hand (the service, app-wide listeners, screens that finish
 * right away), for the next activity that is started to show as a snackbar. Main thread.
 */
class AppMessages {
    private val pending = Channel<String>(Channel.BUFFERED)

    /** Each message once, to one collector. */
    val messages: Flow<String> = pending.receiveAsFlow()

    fun post(text: String) {
        pending.trySend(text)
    }

    interface Owner {
        val appMessages: AppMessages
    }

    companion object {
        fun get(context: Context): AppMessages = (context.applicationContext as Owner).appMessages

        fun post(context: Context, @StringRes text: Int) = get(context).post(context.getString(text))
    }
}

/** Shows the [AppMessages] while this activity is started. */
fun AppCompatActivity.showAppMessages() {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            AppMessages.get(this@showAppMessages).messages.collect { showSnackbar(it) }
        }
    }
}
