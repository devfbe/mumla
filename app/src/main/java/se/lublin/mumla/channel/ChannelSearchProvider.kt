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
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.annotation.VisibleForTesting
import se.lublin.humla.IHumlaService
import se.lublin.humla.model.IChannel
import se.lublin.humla.model.IUser
import se.lublin.mumla.R
import se.lublin.mumla.service.MumlaService
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Search suggestions for the channel list: the channels and users of the connected server whose
 * names contain the query, ignoring case.
 */
class ChannelSearchProvider : ContentProvider() {

    @Volatile
    private var service: IHumlaService? = null

    /** Opened when the service is connected; a new one after it disconnects. */
    @Volatile
    private var bound = CountDownLatch(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as MumlaService.MumlaBinder).service
            bound.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            bound = CountDownLatch(1)
            service = null
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val service = connectedService()?.takeIf { it.isConnected } ?: return null
        val context = requireContext()
        val query = selectionArgs.orEmpty().joinToString(" ").lowercase(Locale.getDefault())
        val root = service.session.rootChannel
        val cursor = MatrixCursor(COLUMNS)
        channelsMatching(root, query).forEachIndexed { index, channel ->
            val users = channel.subchannelUserCount
            cursor.addRow(
                arrayOf<Any?>(
                    index, INTENT_DATA_CHANNEL, channel.name, R.drawable.ic_action_channels,
                    context.resources.getQuantityString(R.plurals.search_channel_users, users, users), channel.id,
                ),
            )
        }
        usersMatching(root, query).forEachIndexed { index, user ->
            cursor.addRow(
                arrayOf<Any?>(
                    index, INTENT_DATA_USER, user.name, R.drawable.ic_action_user_dark,
                    context.getString(R.string.user), user.session,
                ),
            )
        }
        return cursor
    }

    /** The service, binding to it and waiting a while if this is the first query. */
    private fun connectedService(): IHumlaService? {
        service?.let { return it }
        requireContext().bindService(Intent(context, MumlaService::class.java), connection, 0)
        try {
            bound.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Log.d(TAG, "interrupted while binding: $e")
            Thread.currentThread().interrupt()
        }
        return service.also { if (it == null) Log.v(TAG, "Failed to connect to service from search provider!") }
    }

    /** The channels at or below [root] whose names contain the lower-case [query]. */
    @VisibleForTesting
    internal fun channelsMatching(root: IChannel?, query: String): List<IChannel> = buildList {
        fun visit(channel: IChannel) {
            if (channel.name.orEmpty().lowercase().contains(query.lowercase())) add(channel)
            channel.subchannels.forEach(::visit)
        }
        root?.let(::visit)
    }

    /** The users at or below [root] whose names contain the lower-case [query]. */
    @VisibleForTesting
    internal fun usersMatching(root: IChannel?, query: String): List<IUser> = buildList {
        fun visit(channel: IChannel) {
            channel.users.filterTo(this) { it.name?.lowercase()?.contains(query.lowercase()) == true }
            channel.subchannels.forEach(::visit)
        }
        root?.let(::visit)
    }

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

        private val TAG: String = ChannelSearchProvider::class.java.name
        private const val BIND_TIMEOUT_SECONDS = 5L
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
