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
package se.lublin.humla.model

import com.google.protobuf.ByteString

/**
 * A user of the server tree. Mutated on the protocol and audio threads and read from the main
 * thread, so every field is volatile. The list a user appears in belongs to its [Channel].
 */
class User @JvmOverloads constructor(session: Int = 0, name: String? = null) : IUser, Comparable<User> {
    override val session: Int = session

    /** Setting it moves the user out of their last channel's list and into the new one's. */
    @Volatile override var channel: Channel? = null
        set(value) {
            field?.removeUser(this)
            field = value
            value?.addUser(this)
        }

    @Volatile override var userId = -1
    @Volatile override var name: String? = name
    @Volatile override var comment: String? = null
    @Volatile override var hash: String? = null

    @Volatile private var mCommentHash: ByteString? = null
    @Volatile private var mTexture: ByteString? = null
    @Volatile private var mTextureHash: ByteString? = null

    override val commentHash: ByteArray? get() = mCommentHash?.toByteArray()
    override val texture: ByteArray? get() = mTexture?.toByteArray()
    override val textureHash: ByteArray? get() = mTextureHash?.toByteArray()

    fun setCommentHash(commentHash: ByteString?) {
        mCommentHash = commentHash
    }

    fun setTexture(texture: ByteString?) {
        mTexture = texture
    }

    fun setTextureHash(textureHash: ByteString?) {
        mTextureHash = textureHash
    }

    @Volatile override var isMuted = false
    @Volatile override var isDeafened = false
    @Volatile override var isSuppressed = false
    @Volatile override var isSelfMuted = false
    @Volatile override var isSelfDeafened = false
    @Volatile override var isPrioritySpeaker = false
    @Volatile override var isRecording = false
    @Volatile override var talkState: TalkState = TalkState.PASSIVE

    @Volatile override var isLocalMuted = false
    @Volatile override var isLocalIgnored = false
    @Volatile override var localVolume = 1f

    /** The number of samples normally available from the user. */
    @Volatile var averageAvailable = 0f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        return session == (other as User).session
    }

    /** The session, consistent with [equals]. The user id is -1 until the server assigns one. */
    override fun hashCode(): Int = session

    /** Orders case-insensitively by name, with nameless users first. */
    override fun compareTo(other: User): Int =
        (name ?: "").lowercase().compareTo((other.name ?: "").lowercase())
}
