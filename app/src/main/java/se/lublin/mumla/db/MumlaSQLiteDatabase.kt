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

package se.lublin.mumla.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import se.lublin.humla.model.Server

/**
 * @param cursorFactory test seam: builds every cursor a query returns, so a test can check that
 * each one was closed.
 */
class MumlaSQLiteDatabase @JvmOverloads constructor(
    context: Context,
    name: String = DATABASE_NAME,
    cursorFactory: SQLiteDatabase.CursorFactory? = null,
) : SQLiteOpenHelper(context, name, cursorFactory, CURRENT_DB_VERSION), MumlaDatabase {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(TABLE_SERVER_CREATE_SQL)
        db.execSQL(TABLE_FAVOURITES_CREATE_SQL)
        db.execSQL(TABLE_TOKENS_CREATE_SQL)
        db.execSQL(TABLE_COMMENTS_CREATE_SQL)
        db.execSQL(TABLE_LOCAL_MUTE_CREATE_SQL)
        db.execSQL(TABLE_LOCAL_IGNORE_CREATE_SQL)
        db.execSQL(TABLE_CERTIFICATES_CREATE_SQL)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "Database upgrade from $oldVersion to $newVersion")
        if (oldVersion <= PRE_FAVOURITES_DB_VERSION) db.execSQL(TABLE_FAVOURITES_CREATE_SQL)
        if (oldVersion <= PRE_TOKENS_DB_VERSION) db.execSQL(TABLE_TOKENS_CREATE_SQL)
        if (oldVersion <= PRE_COMMENTS_DB_VERSION) db.execSQL(TABLE_COMMENTS_CREATE_SQL)
        if (oldVersion <= PRE_LOCAL_MUTE_DB_VERSION) db.execSQL(TABLE_LOCAL_MUTE_CREATE_SQL)
        if (oldVersion <= PRE_LOCAL_IGNORE_DB_VERSION) db.execSQL(TABLE_LOCAL_IGNORE_CREATE_SQL)
        if (oldVersion <= PRE_CERTIFICATES_DB_VERSION) db.execSQL(TABLE_CERTIFICATES_CREATE_SQL)
    }

    override fun open() {
        // Do nothing. Database will be opened automatically when accessing it.
    }

    override fun getServers(): List<Server> =
        readableDatabase.query(
            TABLE_SERVER,
            arrayOf(SERVER_ID, SERVER_NAME, SERVER_HOST, SERVER_PORT, SERVER_USERNAME, SERVER_PASSWORD),
            null, null, null, null, null,
        ).use { c ->
            c.readAll {
                Server(
                    c.getInt(c.getColumnIndexOrThrow(SERVER_ID)).toLong(),
                    c.getString(c.getColumnIndexOrThrow(SERVER_NAME)),
                    c.getString(c.getColumnIndexOrThrow(SERVER_HOST)),
                    c.getInt(c.getColumnIndexOrThrow(SERVER_PORT)),
                    c.getString(c.getColumnIndexOrThrow(SERVER_USERNAME)),
                    c.getString(c.getColumnIndexOrThrow(SERVER_PASSWORD)),
                )
            }
        }

    override fun addServer(server: Server) {
        server.id = writableDatabase.insert(TABLE_SERVER, null, serverValues(server))
    }

    override fun updateServer(server: Server) {
        writableDatabase.update(TABLE_SERVER, serverValues(server), "$SERVER_ID=?", arrayOf(server.id.toString()))
    }

    private fun serverValues(server: Server) = ContentValues().apply {
        put(SERVER_NAME, server.name)
        put(SERVER_HOST, server.host)
        put(SERVER_PORT, server.port)
        put(SERVER_USERNAME, server.username)
        put(SERVER_PASSWORD, server.password)
    }

    override fun removeServer(server: Server) {
        val id = arrayOf(server.id.toString())
        writableDatabase.delete(TABLE_SERVER, "$SERVER_ID=?", id)
        // Clean up server-specific entries
        writableDatabase.delete(TABLE_FAVOURITES, "$FAVOURITES_SERVER=?", id)
        writableDatabase.delete(TABLE_TOKENS, "$TOKENS_SERVER=?", id)
        writableDatabase.delete(TABLE_LOCAL_MUTE, "$LOCAL_MUTE_SERVER=?", id)
        writableDatabase.delete(TABLE_LOCAL_IGNORE, "$LOCAL_IGNORE_SERVER=?", id)
    }

    override fun getPinnedChannels(serverId: Long): List<Int> =
        readableDatabase.query(
            TABLE_FAVOURITES, arrayOf(FAVOURITES_CHANNEL),
            "$FAVOURITES_SERVER=?", arrayOf(serverId.toString()), null, null, null,
        ).use { c -> c.readAll { c.getInt(0) } }

    override fun addPinnedChannel(serverId: Long, channelId: Int) {
        val values = ContentValues().apply {
            put(FAVOURITES_CHANNEL, channelId)
            put(FAVOURITES_SERVER, serverId)
        }
        writableDatabase.insert(TABLE_FAVOURITES, null, values)
    }

    override fun isChannelPinned(serverId: Long, channelId: Int): Boolean =
        readableDatabase.query(
            TABLE_FAVOURITES, arrayOf(FAVOURITES_CHANNEL),
            "$FAVOURITES_SERVER=? AND $FAVOURITES_CHANNEL=?",
            arrayOf(serverId.toString(), channelId.toString()), null, null, null,
        ).use { it.moveToFirst() }

    override fun removePinnedChannel(serverId: Long, channelId: Int) {
        writableDatabase.delete(
            TABLE_FAVOURITES, "server = ? AND channel = ?", arrayOf(serverId.toString(), channelId.toString()),
        )
    }

    override fun getAccessTokens(serverId: Long): List<String> =
        readableDatabase.query(
            TABLE_TOKENS, arrayOf(TOKENS_VALUE), "$TOKENS_SERVER=?", arrayOf(serverId.toString()), null, null, null,
        ).use { c -> c.readAll { c.getString(0) } }

    override fun addAccessToken(serverId: Long, token: String) {
        val values = ContentValues().apply {
            put(TOKENS_SERVER, serverId)
            put(TOKENS_VALUE, token)
        }
        writableDatabase.insert(TABLE_TOKENS, null, values)
    }

    override fun removeAccessToken(serverId: Long, token: String) {
        writableDatabase.delete(TABLE_TOKENS, "$TOKENS_SERVER=? AND $TOKENS_VALUE=?", arrayOf(serverId.toString(), token))
    }

    override fun getLocalMutedUsers(serverId: Long): List<Int> =
        readableDatabase.query(
            TABLE_LOCAL_MUTE, arrayOf(LOCAL_MUTE_USER),
            "$LOCAL_MUTE_SERVER=?", arrayOf(serverId.toString()), null, null, null,
        ).use { c -> c.readAll { c.getInt(0) } }

    override fun addLocalMutedUser(serverId: Long, userId: Int) {
        val values = ContentValues().apply {
            put(LOCAL_MUTE_SERVER, serverId)
            put(LOCAL_MUTE_USER, userId)
        }
        writableDatabase.insert(TABLE_LOCAL_MUTE, null, values)
    }

    override fun removeLocalMutedUser(serverId: Long, userId: Int) {
        writableDatabase.delete(
            TABLE_LOCAL_MUTE, "$LOCAL_MUTE_SERVER=? AND $LOCAL_MUTE_USER=?",
            arrayOf(serverId.toString(), userId.toString()),
        )
    }

    override fun getLocalIgnoredUsers(serverId: Long): List<Int> =
        readableDatabase.query(
            TABLE_LOCAL_IGNORE, arrayOf(LOCAL_IGNORE_USER),
            "$LOCAL_IGNORE_SERVER=?", arrayOf(serverId.toString()), null, null, null,
        ).use { c -> c.readAll { c.getInt(0) } }

    override fun addLocalIgnoredUser(serverId: Long, userId: Int) {
        val values = ContentValues().apply {
            put(LOCAL_IGNORE_SERVER, serverId)
            put(LOCAL_IGNORE_USER, userId)
        }
        writableDatabase.insert(TABLE_LOCAL_IGNORE, null, values)
    }

    override fun removeLocalIgnoredUser(serverId: Long, userId: Int) {
        writableDatabase.delete(
            TABLE_LOCAL_IGNORE, "$LOCAL_IGNORE_SERVER=? AND $LOCAL_IGNORE_USER=?",
            arrayOf(serverId.toString(), userId.toString()),
        )
    }

    override fun addCertificate(name: String, certificate: ByteArray): DatabaseCertificate {
        val values = ContentValues().apply {
            put(COLUMN_CERTIFICATES_NAME, name)
            put(COLUMN_CERTIFICATES_DATA, certificate)
        }
        val id = writableDatabase.insert(TABLE_CERTIFICATES, null, values)
        return DatabaseCertificate(id, name)
    }

    override fun getCertificates(): List<DatabaseCertificate> =
        readableDatabase.query(
            TABLE_CERTIFICATES, arrayOf(COLUMN_CERTIFICATES_ID, COLUMN_CERTIFICATES_NAME),
            null, null, null, null, null,
        ).use { c -> c.readAll { DatabaseCertificate(c.getLong(0), c.getString(1)) } }

    override fun getCertificateData(id: Long): ByteArray? =
        readableDatabase.query(
            TABLE_CERTIFICATES, arrayOf(COLUMN_CERTIFICATES_DATA),
            "$COLUMN_CERTIFICATES_ID=?", arrayOf(id.toString()), null, null, null,
        ).use { if (it.moveToFirst()) it.getBlob(0) else null }

    override fun removeCertificate(id: Long) {
        writableDatabase.delete(TABLE_CERTIFICATES, "$COLUMN_CERTIFICATES_ID=?", arrayOf(id.toString()))
    }

    override fun isCommentSeen(hash: String, commentHash: ByteArray): Boolean =
        readableDatabase.query(
            TABLE_COMMENTS, arrayOf(COMMENTS_WHO, COMMENTS_COMMENT, COMMENTS_SEEN),
            "$COMMENTS_WHO=? AND $COMMENTS_COMMENT=?",
            arrayOf(hash, String(commentHash)), null, null, null,
        ).use { it.moveToNext() }

    override fun markCommentSeen(hash: String, commentHash: ByteArray) {
        val values = ContentValues().apply {
            put(COMMENTS_WHO, hash)
            put(COMMENTS_COMMENT, commentHash)
            put(COMMENTS_SEEN, "datetime('now')")
        }
        writableDatabase.replace(TABLE_COMMENTS, null, values)
    }

    /** Every row, in order. The caller owns the cursor and closes it. */
    private inline fun <T> Cursor.readAll(row: () -> T): List<T> {
        val result = ArrayList<T>(count)
        while (moveToNext()) result += row()
        return result
    }

    companion object {
        private val TAG = MumlaSQLiteDatabase::class.java.name

        const val DATABASE_NAME = "mumble.db"

        const val TABLE_SERVER = "server"
        const val SERVER_ID = "_id"
        const val SERVER_NAME = "name"
        const val SERVER_HOST = "host"
        const val SERVER_PORT = "port"
        const val SERVER_USERNAME = "username"
        const val SERVER_PASSWORD = "password"
        const val TABLE_SERVER_CREATE_SQL = "CREATE TABLE IF NOT EXISTS `" + TABLE_SERVER + "` (" +
            "`" + SERVER_ID + "` INTEGER PRIMARY KEY AUTOINCREMENT," +
            "`" + SERVER_NAME + "` TEXT NOT NULL," +
            "`" + SERVER_HOST + "` TEXT NOT NULL," +
            "`" + SERVER_PORT + "` INTEGER," +
            "`" + SERVER_USERNAME + "` TEXT NOT NULL," +
            "`" + SERVER_PASSWORD + "` TEXT" +
            ");"

        const val TABLE_FAVOURITES = "favourites"
        const val FAVOURITES_ID = "_id"
        const val FAVOURITES_CHANNEL = "channel"
        const val FAVOURITES_SERVER = "server"
        const val TABLE_FAVOURITES_CREATE_SQL = "CREATE TABLE IF NOT EXISTS `" + TABLE_FAVOURITES + "` (" +
            "`" + FAVOURITES_ID + "` INTEGER PRIMARY KEY AUTOINCREMENT," +
            "`" + FAVOURITES_CHANNEL + "` TEXT NOT NULL," +
            "`" + FAVOURITES_SERVER + "` INTEGER NOT NULL" +
            ");"

        const val TABLE_TOKENS = "tokens"
        const val TOKENS_ID = "_id"
        const val TOKENS_VALUE = "value"
        const val TOKENS_SERVER = "server"
        const val TABLE_TOKENS_CREATE_SQL = "CREATE TABLE IF NOT EXISTS `" + TABLE_TOKENS + "` (" +
            "`" + TOKENS_ID + "` INTEGER PRIMARY KEY AUTOINCREMENT," +
            "`" + TOKENS_VALUE + "` TEXT NOT NULL," +
            "`" + TOKENS_SERVER + "` INTEGER NOT NULL" +
            ");"

        const val TABLE_COMMENTS = "comments"
        const val COMMENTS_WHO = "who"
        const val COMMENTS_COMMENT = "comment"
        const val COMMENTS_SEEN = "seen"
        const val TABLE_COMMENTS_CREATE_SQL = "CREATE TABLE IF NOT EXISTS `" + TABLE_COMMENTS + "` (" +
            "`" + COMMENTS_WHO + "` TEXT NOT NULL," +
            "`" + COMMENTS_COMMENT + "` TEXT NOT NULL," +
            "`" + COMMENTS_SEEN + "` DATE NOT NULL" +
            ");"

        const val TABLE_LOCAL_MUTE = "local_mute"
        const val LOCAL_MUTE_SERVER = "server"
        const val LOCAL_MUTE_USER = "user"
        const val TABLE_LOCAL_MUTE_CREATE_SQL = "CREATE TABLE IF NOT EXISTS " + TABLE_LOCAL_MUTE + " (" +
            "`" + LOCAL_MUTE_SERVER + "` INTEGER NOT NULL," +
            "`" + LOCAL_MUTE_USER + "` INTEGER NOT NULL," +
            "CONSTRAINT server_user UNIQUE(" + LOCAL_MUTE_SERVER + "," + LOCAL_MUTE_USER + ")" +
            ");"

        const val TABLE_LOCAL_IGNORE = "local_ignore"
        const val LOCAL_IGNORE_SERVER = "server"
        const val LOCAL_IGNORE_USER = "user"
        const val TABLE_LOCAL_IGNORE_CREATE_SQL = "CREATE TABLE IF NOT EXISTS " + TABLE_LOCAL_IGNORE + " (" +
            "`" + LOCAL_IGNORE_SERVER + "` INTEGER NOT NULL," +
            "`" + LOCAL_IGNORE_USER + "` INTEGER NOT NULL," +
            "CONSTRAINT server_user UNIQUE(" + LOCAL_IGNORE_SERVER + "," + LOCAL_IGNORE_USER + ")" +
            ");"

        const val TABLE_CERTIFICATES = "certificates"
        const val COLUMN_CERTIFICATES_ID = "_id"
        const val COLUMN_CERTIFICATES_DATA = "data"
        const val COLUMN_CERTIFICATES_NAME = "name"
        const val TABLE_CERTIFICATES_CREATE_SQL = "CREATE TABLE IF NOT EXISTS " + TABLE_CERTIFICATES + " (" +
            "`" + COLUMN_CERTIFICATES_ID + "` INTEGER PRIMARY KEY AUTOINCREMENT," +
            "`" + COLUMN_CERTIFICATES_DATA + "` BLOB NOT NULL," +
            "`" + COLUMN_CERTIFICATES_NAME + "` TEXT NOT NULL" +
            ");"

        const val PRE_FAVOURITES_DB_VERSION = 2
        const val PRE_TOKENS_DB_VERSION = 3
        const val PRE_COMMENTS_DB_VERSION = 4
        const val PRE_LOCAL_MUTE_DB_VERSION = 5
        const val PRE_LOCAL_IGNORE_DB_VERSION = 6
        const val PRE_CERTIFICATES_DB_VERSION = 7
        const val CURRENT_DB_VERSION = 8
    }
}
