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

import se.lublin.humla.model.Server

/** The app's persistent store. Every call blocks on disk I/O; see [MumlaRepository]. */
@Suppress("TooManyFunctions") // One call per stored item kind and operation.
interface MumlaDatabase {
    fun open()
    fun close()

    fun getServers(): List<Server>
    fun addServer(server: Server)
    fun updateServer(server: Server)
    fun removeServer(server: Server)

    fun isCommentSeen(hash: String, commentHash: ByteArray): Boolean
    fun markCommentSeen(hash: String, commentHash: ByteArray)

    fun getPinnedChannels(serverId: Long): List<Int>
    fun addPinnedChannel(serverId: Long, channelId: Int)
    fun removePinnedChannel(serverId: Long, channelId: Int)
    fun isChannelPinned(serverId: Long, channelId: Int): Boolean

    fun getAccessTokens(serverId: Long): List<String>
    fun addAccessToken(serverId: Long, token: String)
    fun removeAccessToken(serverId: Long, token: String)

    fun getLocalMutedUsers(serverId: Long): List<Int>
    fun addLocalMutedUser(serverId: Long, userId: Int)
    fun removeLocalMutedUser(serverId: Long, userId: Int)

    fun getLocalIgnoredUsers(serverId: Long): List<Int>
    fun addLocalIgnoredUser(serverId: Long, userId: Int)
    fun removeLocalIgnoredUser(serverId: Long, userId: Int)

    /** Local playback volumes by `LocalVolumes.keyOf`. */
    fun getLocalVolumes(): Map<String, Float>

    /** Stores [volume] for [key]; a volume of 1 removes it. */
    fun setLocalVolume(key: String, volume: Float)

    /** Stores the PKCS#12 blob [certificate] under the user-readable [name]. */
    fun addCertificate(name: String, certificate: ByteArray): DatabaseCertificate
    fun getCertificates(): List<DatabaseCertificate>

    /** The PKCS#12 blob of the certificate [id]. */
    fun getCertificateData(id: Long): ByteArray?
    fun removeCertificate(id: Long)
}
