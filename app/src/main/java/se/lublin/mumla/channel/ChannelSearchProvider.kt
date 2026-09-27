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

import android.app.SearchManager
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.annotation.VisibleForTesting
import se.lublin.humla.model.ChannelState
import se.lublin.humla.model.ServerState
import se.lublin.humla.model.UserState
import se.lublin.mumla.R
import se.lublin.mumla.session.SessionManager
import java.util.Locale

/**
 * Search suggestions for the channel list: the channels and users of the connected server whose
 * names contain the query, ignoring case.
 */
class ChannelSearchProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val context = requireContext()
        val model = SessionManager.get(context).connected?.model?.value ?: return null
        val query = selectionArgs.orEmpty().joinToString(" ").lowercase(Locale.getDefault())
        val cursor = MatrixCursor(COLUMNS)
        channelsMatching(model, query).forEachIndexed { index, channel ->
            val users = model.subtreeUserCount(channel.id)
            cursor.addRow(
                arrayOf<Any?>(
                    index, INTENT_DATA_CHANNEL, channel.name, R.drawable.ic_action_channels,
                    context.resources.getQuantityString(R.plurals.search_channel_users, users, users), channel.id,
                ),
            )
        }
        usersMatching(model, query).forEachIndexed { index, user ->
            cursor.addRow(
                arrayOf<Any?>(
                    index, INTENT_DATA_USER, user.name, R.drawable.ic_action_user_dark,
                    context.getString(R.string.user), user.session,
                ),
            )
        }
        return cursor
    }

    /** The channels in the tree of [model] whose names contain [query], ignoring case, in tree order. */
    @VisibleForTesting
    internal fun channelsMatching(model: ServerState, query: String): List<ChannelState> =
        model.flatten().filter { it.name.orEmpty().contains(query, ignoreCase = true) }

    /** The users in the tree of [model] whose names contain [query], ignoring case, in tree order. */
    @VisibleForTesting
    internal fun usersMatching(model: ServerState, query: String): List<UserState> =
        model.flatten().flatMap { model.usersIn(it.id) }.filter { it.name?.contains(query, ignoreCase = true) == true }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        const val INTENT_DATA_CHANNEL = "channel"
        const val INTENT_DATA_USER = "user"

        private val COLUMNS = arrayOf(
            "_ID",
            SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA,
            SearchManager.SUGGEST_COLUMN_TEXT_1,
            SearchManager.SUGGEST_COLUMN_ICON_1,
            SearchManager.SUGGEST_COLUMN_TEXT_2,
            SearchManager.SUGGEST_COLUMN_INTENT_DATA,
        )
    }
}
